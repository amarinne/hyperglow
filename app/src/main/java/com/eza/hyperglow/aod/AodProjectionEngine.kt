package com.eza.hyperglow.aod

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.producer.LyricProducerArbiter
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
        fun from(state: SpicyBridgeState) = ProjectionSessionIdentity(
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

internal class ProjectionPublicationGuard {
    private var generation = 0L
    private var activeSession: ProjectionSessionIdentity? = null

    @Synchronized
    fun begin(state: SpicyBridgeState?): ProjectionPublicationToken? {
        generation++
        activeSession = state?.let(ProjectionSessionIdentity::from)
        return activeSession?.let { ProjectionPublicationToken(generation, it) }
    }

    @Synchronized
    fun current(state: SpicyBridgeState): ProjectionPublicationToken? {
        val session = ProjectionSessionIdentity.from(state)
        if (session != activeSession) return null
        return ProjectionPublicationToken(generation, session)
    }

    @Synchronized
    fun invalidate() {
        generation++
        activeSession = null
    }

    @Synchronized
    fun canPublish(
        token: ProjectionPublicationToken,
        candidate: SpicyBridgeState,
        current: SpicyBridgeState?,
        capturedDocument: Any?,
        currentDocument: Any?
    ): Boolean = token.generation == generation &&
        token.session == activeSession &&
        candidate === current &&
        ProjectionSessionIdentity.from(candidate) == token.session &&
        capturedDocument === currentDocument
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
    private var lastKeepAliveIntent = false
    private var lastCustomizationPublishAt = 0L
    private var started = false
    private var appContext: Context? = null
    private val producers = LyricProducerArbiter.Default
    private val publicationGuard = ProjectionPublicationGuard()
    private val releaseGate = ProjectionReleaseGate()
    private val powerSessionPolicy = AodPowerSessionPolicy()
    private val metadataIntroPolicy = SongMetadataIntroPolicy()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        started = true
        publishCustomizationIfDue(SystemClock.elapsedRealtime())
        producers.start()
        scope.launch {
            producers.updates.collect(::handleState)
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                producers.expireIfStale()
            }
        }
    }

    @Synchronized
    private fun handleState(state: SpicyBridgeState?) {
        val publicationToken = publicationGuard.begin(state)
        if (state == null) {
            stopScheduler()
            cancelPauseConfirmation()
            scheduleRelease()
            producers.clearDocument()
            return
        }
        cancelRelease()
        if (!producers.isCurrentActive(state)) {
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

    @Synchronized
    private fun ensureScheduler(state: SpicyBridgeState) {
        val key = "${state.producerId}:${state.generation}:${state.trackUri}"
        if (scheduler?.isActive == true && sessionKey == key) return
        stopScheduler()
        val expectedSession = ProjectionSessionIdentity.from(state)
        sessionKey = key
        scheduler = scope.launch {
            while (true) {
                val current = producers.currentState() ?: break
                if (!producers.isCurrentActive(current) ||
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
    private fun startStatusKeepAlive(state: SpicyBridgeState) {
        if (!AodStateBridge.hasVisibleState()) return
        val expected = fallbackRefreshSession(state)
        if (transitionKeepAlive?.isActive == true && fallbackSession == expected) return
        stopStatusKeepAlive()
        fallbackSession = expected
        transitionKeepAlive = scope.launch {
            while (true) {
                delay(FALLBACK_REFRESH_INTERVAL_MS)
                val current = producers.currentState() ?: break
                if (!producers.isCurrentActive(current) ||
                    !canRefreshFallback(expected, current)
                ) break
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
        val current = producers.currentState()
        if (!shouldCommitPauseRetention(
                pendingSession = session,
                current = current,
                currentActive = current != null && producers.isCurrentActive(current)
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
        val current = producers.currentState()
        if (current != null && producers.isCurrentActive(current) && current.playing) return
        releaseJob = null
        // The producer clears the old session at a song boundary before the next one exists, so an
        // absent session means playback state is unknown, not stopped. Asserting a stop there
        // withdrew Xiaomi lifetime suppression for the length of every boundary that outran the
        // transition grace. A session that is present and not playing is a real pause and still
        // releases. Holding across an absent session is bounded by the SystemUI power grace, which
        // expires on its own if no next session arrives.
        releaseNow(playbackActive = current == null && lastKeepAliveIntent)
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
        val keepAlive = retainedTransportGapKeepAlive(playbackActive, lastKeepAliveIntent)
        if (!keepAlive) lastKeepAliveIntent = false
        AodStateBridge.publish(
            AodDisplayState(
                visible = false,
                playbackActive = playbackActive,
                pauseRetentionEligible = pauseRetentionEligible,
                keepAlive = keepAlive,
                userId = currentProcessUserId()
            )
        )
    }

    private fun project(
        state: SpicyBridgeState,
        now: Long = SystemClock.elapsedRealtime(),
        publicationToken: ProjectionPublicationToken
    ) {
        publishCustomizationIfDue(now)
        val capturedDocument = producers.currentDocument()
        val document = capturedDocument?.takeIf { it.matches(state) }
        val prefs = appContext?.let(AodRenderPreferences::read) ?: AodRenderConfig()
        val compiled = appContext?.let(CustomizationRepository::loadCompiled)
        val aodProfile = compiled?.profiles?.get(SceneCompiler.SURFACE_AOD)
        val aodEnabled = aodProfile?.enabled ?: prefs.aodEnabled
        val lockscreenEnabled = compiled?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)?.enabled
            ?: prefs.lockscreenEnabled
        val projectedState = projectToDisplay(
            state = state,
            document = document,
            context = AodProjectionContext(
                userId = currentProcessUserId(),
                nowElapsedMs = now,
                positionMs = projectedPosition(state, now),
                prefs = prefs,
                aodEnabled = aodEnabled,
                lockscreenEnabled = lockscreenEnabled,
                metadataVisible = aodProfile?.metadataVisible ?: (prefs.metadataVisible != "hide")
            ),
            metadataIntroPolicy = metadataIntroPolicy,
            powerSessionPolicy = powerSessionPolicy
        )
        if (!publicationGuard.canPublish(
                token = publicationToken,
                candidate = state,
                current = producers.currentState(),
                capturedDocument = capturedDocument,
                currentDocument = producers.currentDocument()
            ) || !producers.isCurrentActive(state) || !state.playing
        ) return
        lastKeepAliveIntent = projectedState.keepAlive
        AodStateBridge.publish(projectedState)
    }

    /**
     * Keepalive intent carried into a release.
     *
     * A non-playing edge inside the confirmation window is published as a still-playing transport
     * gap, and the spec is explicit that song changes never drop AOD. Publishing `keepAlive=false`
     * there contradicted that: the SystemUI coordinator reads `playbackActive` with no keepalive as
     * a withdrawal and releases Xiaomi's lifetime suppression, so every song change unguarded the
     * gap. On a long boundary Xiaomi's hide fired inside it, the panel went dark, and the surface
     * had to be woken and re-placed. Holding the last intent across the gap is what makes the
     * SystemUI-side grace a backstop instead of the only defence.
     *
     * A real release — a confirmed pause, a lost session — carries no intent and withdraws normally.
     */
    internal fun retainedTransportGapKeepAlive(
        playbackActive: Boolean,
        lastKeepAliveIntent: Boolean
    ): Boolean = playbackActive && lastKeepAliveIntent

    private fun publishCustomizationIfDue(now: Long) {
        if (now - lastCustomizationPublishAt < CUSTOMIZATION_REFRESH_MS) return
        val context = appContext ?: return
        lastCustomizationPublishAt = now
        AodStateBridge.publishConfiguration(
            RuntimeCustomization.loadCompiled(context),
            currentProcessUserId()
        )
    }

    @Synchronized
    private fun stopScheduler() {
        scheduler?.cancel()
        scheduler = null
        sessionKey = ""
        lastKeepAliveAt = 0L
    }

    fun projectedPosition(state: SpicyBridgeState, now: Long): Long {
        val projected = if (state.playing) {
            state.positionMs + ((now - state.sampledAtElapsedMs).coerceAtLeast(0L) * state.speed).toLong()
        } else state.positionMs
        return projected.coerceIn(0L, state.durationMs)
    }

    fun keepAliveDue(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= KEEP_ALIVE_INTERVAL_MS

    internal fun fallbackRefreshSession(state: SpicyBridgeState) = FallbackRefreshSession(
        state.producerId,
        state.generation,
        state.trackUri,
        state.status
    )

    internal fun canRefreshFallback(
        expected: FallbackRefreshSession,
        current: SpicyBridgeState
    ): Boolean = current.playing &&
        shouldShowPlaybackFallback(current.status, current.playing) &&
        fallbackRefreshSession(current) == expected

    internal fun fallbackRefreshIntervalMs(): Long = FALLBACK_REFRESH_INTERVAL_MS

    fun shouldShowPlaybackFallback(status: String, playing: Boolean): Boolean =
        playing && (status == "loading" || status == "no_lyrics")

    internal fun isPlayingTransportGap(state: SpicyBridgeState): Boolean =
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
        current: SpicyBridgeState?,
        currentActive: Boolean
    ): Boolean = current != null && currentActive && !current.playing &&
        ProjectionSessionIdentity.from(current) == pendingSession

    fun staticPlaybackPlaceholder(status: String): String? =
        "♪".takeIf { status == "no_lyrics" }

    internal fun playbackFallback(status: String, line: String, metadata: String): String? =
        if (status == "loading") metadata.takeIf { it.isNotBlank() }
        else line.takeIf { it.isNotBlank() }

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

    internal fun sessionWakeSignal(state: SpicyBridgeState, hasTimedLyrics: Boolean): Long {
        val phase = if (hasTimedLyrics) "timed" else "song"
        return "${state.producerId}|${state.generation}|${state.trackUri}|$phase"
            .hashCode()
            .toLong()
            .takeUnless { it == 0L } ?: 1L
    }

    internal fun trackGeneration(state: SpicyBridgeState): Long {
        val identity = "${state.producerId}\u0000${state.generation}\u0000${state.trackUri}"
        return identity.hashCode().toLong() and Long.MAX_VALUE
    }

    private const val KEEP_ALIVE_INTERVAL_MS = 4_000L
    private const val FALLBACK_REFRESH_INTERVAL_MS = 1_000L
    private const val TRANSITION_GRACE_MS = 1_500L
    internal const val PAUSE_CONFIRM_MS = 1_500L
    private const val CUSTOMIZATION_REFRESH_MS = 1_000L
}
