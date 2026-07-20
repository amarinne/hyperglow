package com.eza.hyperglow.aod

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.projection.currentProcessUserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
    private var sessionKey = ""
    private var lastKeepAliveAt = 0L
    private var lastCustomizationPublishAt = 0L
    private var started = false
    private var appContext: Context? = null
    private val publicationGuard = ProjectionPublicationGuard()
    private val releaseGate = ProjectionReleaseGate()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        started = true
        publishCustomizationIfDue(SystemClock.elapsedRealtime())
        scope.launch {
            combine(SpicyBridgeStore.state, SpicyBridgeDocumentStore.state) { state, _ -> state }
                .collect(::handleState)
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                SpicyBridgeStore.expireIfStale()
            }
        }
    }

    @Synchronized
    private fun handleState(state: SpicyBridgeState?) {
        val publicationToken = publicationGuard.begin(state)
        if (state == null) {
            stopScheduler()
            scheduleRelease()
            SpicyBridgeDocumentStore.clear()
            return
        }
        cancelRelease()
        if (!SpicyBridgeStore.isCurrentActive(state) || !state.playing) {
            releaseNow()
            return
        }
        if (shouldShowPlaybackFallback(state.status, state.playing)) {
            stopScheduler()
            project(state, publicationToken = requireNotNull(publicationToken))
            startStatusKeepAlive(state)
            return
        }
        if (state.status != "ready") {
            releaseNow()
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
                val current = SpicyBridgeStore.state.value ?: break
                if (!SpicyBridgeStore.isCurrentActive(current) ||
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
                val current = SpicyBridgeStore.state.value ?: break
                if (!SpicyBridgeStore.isCurrentActive(current) ||
                    !canRefreshFallback(expected, current)
                ) break
                AodStateBridge.refreshVisibleState()
            }
        }
    }

    @Synchronized
    private fun stopStatusKeepAlive() {
        transitionKeepAlive?.cancel()
        transitionKeepAlive = null
        fallbackSession = null
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
        val current = SpicyBridgeStore.state.value
        if (current != null && SpicyBridgeStore.isCurrentActive(current) && current.playing) return
        releaseJob = null
        releaseNow()
    }

    @Synchronized
    private fun cancelRelease() {
        releaseGate.cancel()
        releaseJob?.cancel()
        releaseJob = null
    }

    @Synchronized
    private fun releaseNow() {
        cancelRelease()
        publicationGuard.invalidate()
        stopStatusKeepAlive()
        stopScheduler()
        AodStateBridge.publish(
            AodDisplayState(
                visible = false,
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
        val position = projectedPosition(state, now)
        val capturedDocument = SpicyBridgeDocumentStore.state.value
        val document = capturedDocument?.takeIf { it.matches(state) }
        val timedDocument = document?.takeIf { isTimedDocumentType(it.type) }
        val unsynced = document != null && timedDocument == null
        val noLyrics = state.status == "no_lyrics"
        val row = timedDocument?.primaryRowAt(position).takeUnless { noLyrics }
        val fallback = state.line.takeIf { it.isNotBlank() }
            ?: state.title.takeIf { state.status == "loading" }
        val original = if (unsynced || noLyrics) "♪" else (row?.text ?: fallback).orEmpty()
        val romanized = if (unsynced || noLyrics) "" else
            (row?.romanized ?: state.romanizedLine.takeIf { document == null }).orEmpty()
        val translated = if (unsynced || noLyrics) "" else (row?.translated
            ?: state.translatedLine.takeIf { it.isNotBlank() }).orEmpty()
        val prefs = appContext?.let(AodRenderPreferences::read) ?: AodRenderConfig()
        val compiled = appContext?.let(CustomizationRepository::loadCompiled)
        val aodProfile = compiled?.profiles?.get(SceneCompiler.SURFACE_AOD)
        val aodEnabled = aodProfile?.enabled ?: prefs.aodEnabled
        val lockscreenEnabled = compiled?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)?.enabled
            ?: prefs.lockscreenEnabled
        val projectedState = AodDisplayState(
            visible = original.isNotBlank(),
            userId = currentProcessUserId(),
            trackGeneration = trackGeneration(state),
            aodEnabled = aodEnabled,
            lockscreenEnabled = lockscreenEnabled,
            seamlessTransitionEnabled = prefs.seamlessTransitionEnabled,
            keepAlive = state.playing && prefs.keepAwake && aodEnabled,
            positionFollowingEnabled = prefs.experimentalPositionFollowing,
            burnInPattern = prefs.burnInPattern,
            burnInIntervalMs = prefs.burnInIntervalMs,
            wakeSignal = sessionWakeSignal(state),
            original = original,
            romanized = romanized,
            translated = translated,
            metadata = if (noLyrics) "" else listOf(state.title, state.artist)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            alignedRight = row?.alignedRight == true,
            lineLevelSync = document != null && row != null &&
                isEffectiveLineLevelSync(document.type, row.words.size),
            lineStartMs = row?.startMs ?: 0L,
            lineEndMs = row?.fillEndMs ?: 0L,
            durationMs = state.durationMs,
            positionMs = position,
            sampledAtElapsedMs = now,
            speed = state.speed,
            words = row?.words.orEmpty().map {
                AodDisplayWord(
                    it.text,
                    it.romanized,
                    it.startMs,
                    it.endMs,
                    it.partOfWord,
                    it.sourceStart,
                    it.sourceEnd
                )
            },
            ruby = row?.ruby.orEmpty().map { AodDisplayRuby(it.start, it.end, it.reading) },
            layoutGroups = row?.layoutGroups.orEmpty().map {
                AodDisplayLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
            },
            weight = prefs.weight,
            textSizeMode = prefs.textSize,
            textSizeCustom = prefs.textSizeCustom,
            secondaryMode = prefs.secondaryMode,
            animationMode = prefs.animation,
            glowMode = prefs.glow,
            lineSyncFillMode = state.liveCardLineSyncFill,
            overflowMode = prefs.overflowMode,
            transitionMode = if (noLyrics) "None" else state.liveCardTransition,
            fontFamily = prefs.fontFamily,
            alignmentMode = prefs.alignment,
            metadataVisible = !noLyrics &&
                (aodProfile?.metadataVisible ?: (prefs.metadataVisible != "hide")),
            metadataAnchor = prefs.metadataAnchor,
            adaptiveSectioning = prefs.adaptiveSectioning
        )
        if (!publicationGuard.canPublish(
                token = publicationToken,
                candidate = state,
                current = SpicyBridgeStore.state.value,
                capturedDocument = capturedDocument,
                currentDocument = SpicyBridgeDocumentStore.state.value
            ) || !SpicyBridgeStore.isCurrentActive(state) || !state.playing
        ) return
        AodStateBridge.publish(projectedState)
    }

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

    fun staticPlaybackPlaceholder(status: String): String? =
        "♪".takeIf { status == "no_lyrics" }

    fun isTimedDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true) || type.equals("Syllable", ignoreCase = true)

    fun isLineLevelDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true)

    fun isEffectiveLineLevelSync(type: String, wordCount: Int): Boolean =
        isLineLevelDocumentType(type) ||
            type.equals("Syllable", ignoreCase = true) && wordCount <= 0

    private fun sessionWakeSignal(state: SpicyBridgeState): Long =
        "${state.producerId}|${state.generation}|${state.trackUri}|${state.status}".hashCode().toLong()

    internal fun trackGeneration(state: SpicyBridgeState): Long {
        val identity = "${state.producerId}\u0000${state.generation}\u0000${state.trackUri}"
        return identity.hashCode().toLong() and Long.MAX_VALUE
    }

    private const val KEEP_ALIVE_INTERVAL_MS = 4_000L
    private const val FALLBACK_REFRESH_INTERVAL_MS = 4_000L
    private const val TRANSITION_GRACE_MS = 1_500L
    private const val CUSTOMIZATION_REFRESH_MS = 1_000L
}
