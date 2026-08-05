package com.eza.hyperglow.aod

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.root.projection.currentProcessUserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FallbackRefreshSession(
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val status: String
)

internal data class ProjectionSessionIdentity(
    val producerId: String,
    val generation: Int,
    val trackUri: String
) {
    companion object {
        /** [SpicyBridgeState] 适配——保留供 bridge 包测试使用。 */
        fun from(state: SpicyBridgeState) = ProjectionSessionIdentity(
            producerId = state.producerId,
            generation = state.generation,
            trackUri = state.trackUri
        )

        /** [LyricProducerState] 适配——Phase 3 引擎切换后的主入口。 */
        fun from(state: LyricProducerState) = ProjectionSessionIdentity(
            producerId = state.producerId,
            generation = state.generation,
            trackUri = state.trackUri
        )
    }
}

internal data class ProjectionPublicationToken(
    val generation: Long,
    val session: ProjectionSessionIdentity
)

/**
 * Guards projection publication against stale/in-flight state. Phase 3: operates on
 * [LyricProducerState] (the producer boundary); the document-reference checks the Spicy path
 * needed are gone because producers now select the active row before emitting (spec clause 8).
 */
internal class ProjectionPublicationGuard {
    private var generation = 0L
    private var activeSession: ProjectionSessionIdentity? = null

    @Synchronized
    fun begin(state: LyricProducerState?): ProjectionPublicationToken? {
        generation++
        activeSession = state?.let(ProjectionSessionIdentity::from)
        return activeSession?.let { ProjectionPublicationToken(generation, it) }
    }

    @Synchronized
    fun current(state: LyricProducerState): ProjectionPublicationToken? {
        val session = ProjectionSessionIdentity.from(state)
        if (session != activeSession) return null
        return ProjectionPublicationToken(generation, session)
    }

    @Synchronized
    fun invalidate() {
        generation++
        activeSession = null
    }

    /**
     * True only when [candidate] is still the arbiter's current active state ([current]) and the
     * token matches the live generation/session. The document checks the Spicy path needed are
     * dropped: the active row is now part of [LyricProducerState] itself, so state-reference
     * equality is sufficient to reject a layout built from superseded state.
     */
    @Synchronized
    fun canPublish(
        token: ProjectionPublicationToken,
        candidate: LyricProducerState,
        current: LyricProducerState?
    ): Boolean = token.generation == generation &&
        token.session == activeSession &&
        candidate === current &&
        ProjectionSessionIdentity.from(candidate) == token.session
}

internal class ProjectionReleaseGate {
    private var generation = 0L

    @Synchronized
    fun schedule(): Long = ++generation

    @Synchronized
    fun cancel() {
        generation++
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation
}

object AodProjectionEngine {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduler: Job? = null
    private var transitionKeepAlive: Job? = null
    private var fallbackSession: FallbackRefreshSession? = null
    private var releaseJob: Job? = null
    private var pauseConfirmJob: Job? = null
    private var pendingPauseSession: ProjectionSessionIdentity? = null
    private var confirmedPauseSession: ProjectionSessionIdentity? = null
    private var sessionKey = ""
    private var lastKeepAliveAt = 0L
    private var lastCustomizationPublishAt = 0L
    private var started = false
    private var appContext: Context? = null
    private val publicationGuard = ProjectionPublicationGuard()
    private val releaseGate = ProjectionReleaseGate()
    private val powerSessionPolicy = AodPowerSessionPolicy()
    private val metadataIntroPolicy = SongMetadataIntroPolicy()

    /**
     * The single ingress: [LyricProducers.arbiter].[LyricProducerArbiter.active]. Phase 3 engine
     * switch — the engine no longer reads `SpicyBridgeStore.state` / `SpicyBridgeDocumentStore`
     * for projection (spec clause 30). A background loop still expires stale Spicy bridge state
     * to keep that store's lifecycle intact, but it does not feed projection.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        started = true
        publishCustomizationIfDue(SystemClock.elapsedRealtime())
        scope.launch {
            LyricProducers.arbiter.active.collect(::handleState)
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                SpicyBridgeStore.expireIfStale()
            }
        }
    }

    @Synchronized
    private fun handleState(state: LyricProducerState?) {
        val publicationToken = publicationGuard.begin(state)
        if (state == null) {
            stopScheduler()
            cancelPauseConfirmation()
            scheduleRelease()
            return
        }
        cancelRelease()
        if (!isCurrentActive(state)) {
            cancelPauseConfirmation()
            releaseNow(playbackActive = false)
            return
        }
        if (!state.playing) {
            val session = ProjectionSessionIdentity.from(state)
            if (!shouldOpenPauseGrace(session, confirmedPauseSession)) {
                cancelPendingPauseConfirmation()
                releaseNow(playbackActive = false, pauseRetentionEligible = true)
            } else if (isPlayingTransportGap(state)) {
                cancelPendingPauseConfirmation()
                releaseNow(playbackActive = true)
            } else {
                schedulePauseConfirmation(session)
            }
            return
        }
        cancelPauseConfirmation()
        if (shouldShowPlaybackFallback(state.status, state.playing)) {
            stopScheduler()
            project(state, publicationToken = requireNotNull(publicationToken))
            startStatusKeepAlive(state)
            return
        }
        if (state.status != "ready") {
            releaseNow(playbackActive = state.playing)
            return
        }
        stopStatusKeepAlive()
        ensureScheduler(state)
        project(state, publicationToken = requireNotNull(publicationToken))
    }

    /**
     * Whether [state] is still the arbiter's current active state. Replaces the Spicy path's
     * `SpicyBridgeStore.isCurrentActive`: the arbiter clears `active` on disconnect/staleness, so
     * reference equality against [LyricProducerArbiter.active].value is the authoritative check.
     */
    private fun isCurrentActive(state: LyricProducerState): Boolean =
        LyricProducers.arbiter.active.value === state

    @Synchronized
    private fun ensureScheduler(state: LyricProducerState) {
        val key = "${state.producerId}:${state.generation}:${state.trackUri}"
        if (scheduler?.isActive == true && sessionKey == key) return
        stopScheduler()
        val expectedSession = ProjectionSessionIdentity.from(state)
        sessionKey = key
        scheduler = scope.launch {
            while (true) {
                val current = LyricProducers.arbiter.active.value ?: break
                if (!isCurrentActive(current) ||
                    current.status != "ready" || !current.playing ||
                    ProjectionSessionIdentity.from(current) != expectedSession
                ) break
                val publicationToken = publicationGuard.current(current) ?: break
                val now = SystemClock.elapsedRealtime()
                project(current, now, publicationToken)
                if (keepAliveDue(lastKeepAliveAt, now)) {
                    AodStateBridge.refreshVisibleState()
                    lastKeepAliveAt = now
                }
                delay(100L)
            }
        }
    }

    @Synchronized
    private fun startStatusKeepAlive(state: LyricProducerState) {
        if (!AodStateBridge.hasVisibleState()) return
        val expected = fallbackRefreshSession(state)
        if (transitionKeepAlive?.isActive == true && fallbackSession == expected) return
        stopStatusKeepAlive()
        fallbackSession = expected
        transitionKeepAlive = scope.launch {
            while (true) {
                delay(FALLBACK_REFRESH_INTERVAL_MS)
                val current = LyricProducers.arbiter.active.value ?: break
                if (!isCurrentActive(current) || !canRefreshFallback(expected, current)) break
                val publicationToken = publicationGuard.current(current) ?: break
                val now = SystemClock.elapsedRealtime()
                project(current, now, publicationToken)
                if (keepAliveDue(lastKeepAliveAt, now)) {
                    AodStateBridge.refreshVisibleState()
                    lastKeepAliveAt = now
                }
            }
        }
    }

    @Synchronized
    private fun stopStatusKeepAlive() {
        transitionKeepAlive?.cancel()
        transitionKeepAlive = null
        fallbackSession = null
    }

    /**
     * A non-playing edge is provisional. Spotify reports the old generation as not playing about a
     * second before the next track arrives, so an immediate pause classification releases AOD
     * lifetime and replays Xiaomi's hide in the middle of a song change. The edge is published as a
     * still-playing transport gap first; only a producer that is still non-playing on the same
     * session after the bounded window becomes real pause retention.
     */
    @Synchronized
    private fun schedulePauseConfirmation(session: ProjectionSessionIdentity) {
        if (pauseConfirmJob?.isActive == true && pendingPauseSession == session) return
        cancelPendingPauseConfirmation()
        pendingPauseSession = session
        releaseNow(playbackActive = true)
        pauseConfirmJob = scope.launch {
            delay(PAUSE_CONFIRM_MS)
            confirmPause(session)
        }
    }

    @Synchronized
    private fun confirmPause(session: ProjectionSessionIdentity) {
        if (pendingPauseSession != session) return
        pauseConfirmJob = null
        pendingPauseSession = null
        val current = LyricProducers.arbiter.active.value
        if (!shouldCommitPauseRetention(
                pendingSession = session,
                current = current,
                currentActive = current != null && isCurrentActive(current)
            )
        ) return
        confirmedPauseSession = session
        releaseNow(playbackActive = false, pauseRetentionEligible = true)
    }

    @Synchronized
    private fun cancelPendingPauseConfirmation() {
        pauseConfirmJob?.cancel()
        pauseConfirmJob = null
        pendingPauseSession = null
    }

    @Synchronized
    private fun cancelPauseConfirmation() {
        cancelPendingPauseConfirmation()
        confirmedPauseSession = null
    }

    @Synchronized
    private fun scheduleRelease() {
        if (releaseJob?.isActive == true) return
        val releaseToken = releaseGate.schedule()
        releaseJob = scope.launch {
            delay(TRANSITION_GRACE_MS)
            releaseIfCurrent(releaseToken)
        }
    }

    @Synchronized
    private fun releaseIfCurrent(releaseToken: Long) {
        if (!releaseGate.isCurrent(releaseToken)) return
        val current = LyricProducers.arbiter.active.value
        if (current != null && isCurrentActive(current) && current.playing) return
        releaseJob = null
        releaseNow(playbackActive = false)
    }

    @Synchronized
    private fun cancelRelease() {
        releaseGate.cancel()
        releaseJob?.cancel()
        releaseJob = null
    }

    @Synchronized
    private fun releaseNow(
        playbackActive: Boolean = false,
        pauseRetentionEligible: Boolean = false
    ) {
        cancelRelease()
        publicationGuard.invalidate()
        stopStatusKeepAlive()
        stopScheduler()
        if (!playbackActive) powerSessionPolicy.clear()
        AodStateBridge.publish(
            AodDisplayState(
                visible = false,
                playbackActive = playbackActive,
                pauseRetentionEligible = pauseRetentionEligible,
                userId = currentProcessUserId()
            )
        )
    }

    /**
     * Maps [state] to [AodDisplayState] via the pure [projectToDisplay] function (spec clause 8:
     * projection MUST NOT re-select the active line — producers do that). Publication is gated on
     * the state still being the arbiter's current active state and the token matching.
     */
    private fun project(
        state: LyricProducerState,
        now: Long = SystemClock.elapsedRealtime(),
        publicationToken: ProjectionPublicationToken
    ) {
        publishCustomizationIfDue(now)
        val prefs = appContext?.let(AodRenderPreferences::read) ?: AodRenderConfig()
        val compiled = appContext?.let(CustomizationRepository::loadCompiled)
        val projectedState = projectToDisplay(
            state = state,
            now = now,
            prefs = prefs,
            compiled = compiled,
            metadataIntroPolicy = metadataIntroPolicy,
            powerSessionPolicy = powerSessionPolicy,
            userId = currentProcessUserId()
        )
        if (!publicationGuard.canPublish(
                token = publicationToken,
                candidate = state,
                current = LyricProducers.arbiter.active.value
            ) || !isCurrentActive(state) || !state.playing
        ) return
        AodStateBridge.publish(projectedState)
    }

    private fun publishCustomizationIfDue(now: Long) {
        if (now - lastCustomizationPublishAt < CUSTOMIZATION_REFRESH_MS) return
        val context = appContext ?: return
        lastCustomizationPublishAt = now
        AodStateBridge.publishConfiguration(
            RuntimeCustomization.loadCompiled(context),
            currentProcessUserId(),
            experimentalMode = AodRenderPreferences.read(context).experimentalMode
        )
    }

    @Synchronized
    private fun stopScheduler() {
        scheduler?.cancel()
        scheduler = null
        sessionKey = ""
        lastKeepAliveAt = 0L
    }

    fun projectedPosition(state: LyricProducerState, now: Long): Long =
        projectedPosition(
            positionMs = state.positionMs,
            sampledAtElapsedMs = state.sampledAtElapsedMs,
            speed = state.speed,
            playing = state.playing,
            durationMs = state.durationMs,
            now = now
        )

    fun keepAliveDue(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= KEEP_ALIVE_INTERVAL_MS

    internal fun fallbackRefreshSession(state: LyricProducerState) = FallbackRefreshSession(
        state.producerId,
        state.generation,
        state.trackUri,
        state.status
    )

    internal fun canRefreshFallback(
        expected: FallbackRefreshSession,
        current: LyricProducerState
    ): Boolean = current.playing &&
        shouldShowPlaybackFallback(current.status, current.playing) &&
        fallbackRefreshSession(current) == expected

    internal fun fallbackRefreshIntervalMs(): Long = FALLBACK_REFRESH_INTERVAL_MS

    fun shouldShowPlaybackFallback(status: String, playing: Boolean): Boolean =
        playing && (status == "loading" || status == "no_lyrics")

    internal fun isPlayingTransportGap(state: LyricProducerState): Boolean =
        !state.playing && state.status == "loading"

    internal fun pauseConfirmWindowMs(): Long = PAUSE_CONFIRM_MS

    /**
     * The still-playing grace opens once per session. A producer that keeps publishing while paused
     * must not reopen it through either a confirmation window or a `loading` transport gap,
     * otherwise a real pause flaps between keepalive and retention for as long as it lasts.
     */
    internal fun shouldOpenPauseGrace(
        session: ProjectionSessionIdentity,
        confirmedSession: ProjectionSessionIdentity?
    ): Boolean = confirmedSession != session

    /**
     * True only when the producer is still non-playing on the same session after the confirmation
     * window. A resumed producer or a new session means the non-playing edge was a song change.
     */
    internal fun shouldCommitPauseRetention(
        pendingSession: ProjectionSessionIdentity,
        current: LyricProducerState?,
        currentActive: Boolean
    ): Boolean = current != null && currentActive && !current.playing &&
        ProjectionSessionIdentity.from(current) == pendingSession

    fun staticPlaybackPlaceholder(status: String): String? =
        "♪".takeIf { status == "no_lyrics" }

    internal fun playbackFallback(status: String, line: String, metadata: String): String? =
        if (status == "loading") metadata.takeIf { it.isNotBlank() }
        else line.takeIf { it.isNotBlank() }

    // --- Document-level helpers (retained for Spicy document tests; not called by project()
    // after the Phase 3 switch — producers now select the active row before emitting). ---

    fun isTimedDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true) || type.equals("Syllable", ignoreCase = true)

    internal fun hasActualLyricTiming(document: SpicyBridgeDocument): Boolean =
        isTimedDocumentType(document.type) && document.rows.any { it.endMs > it.startMs }

    internal fun shouldKeepAodAlive(
        playing: Boolean,
        aodEnabled: Boolean,
        keepAwake: Boolean,
        keepAwakeUnsynced: Boolean,
        hasTimedLyrics: Boolean
    ): Boolean = playing && aodEnabled && keepAwake &&
        (hasTimedLyrics || keepAwakeUnsynced)

    fun isLineLevelDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true)

    fun isEffectiveLineLevelSync(type: String, wordCount: Int): Boolean =
        isLineLevelDocumentType(type) ||
            type.equals("Syllable", ignoreCase = true) && wordCount <= 0

    /** Delegates to [AodStateProjector.sessionWakeSignal] (LyricProducerState overload). */
    internal fun sessionWakeSignal(state: LyricProducerState, hasTimedLyrics: Boolean): Long =
        com.eza.hyperglow.aod.sessionWakeSignal(state, hasTimedLyrics)

    /** Delegates to [AodStateProjector.trackGeneration] (LyricProducerState overload). */
    internal fun trackGeneration(state: LyricProducerState): Long =
        com.eza.hyperglow.aod.trackGeneration(state)

    private const val KEEP_ALIVE_INTERVAL_MS = 4_000L
    private const val FALLBACK_REFRESH_INTERVAL_MS = 1_000L
    private const val TRANSITION_GRACE_MS = 1_500L
    internal const val PAUSE_CONFIRM_MS = 1_500L
    private const val CUSTOMIZATION_REFRESH_MS = 1_000L
}
