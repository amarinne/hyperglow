package com.eza.hyperglow.aod

import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import kotlin.math.abs

data class AodDisplayState(
    val visible: Boolean,
    val playbackActive: Boolean = false,
    val userId: Int = 0,
    val trackGeneration: Long = 0L,
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val seamlessTransitionEnabled: Boolean = true,
    val keepAlive: Boolean = false,
    val positionFollowingEnabled: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val wakeSignal: Long = 0L,
    val original: String = "",
    val romanized: String = "",
    val translated: String = "",
    val metadata: String = "",
    val alignedRight: Boolean = false,
    val lineLevelSync: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val sampledAtElapsedMs: Long = 0L,
    val speed: Float = 1f,
    val words: List<AodDisplayWord> = emptyList(),
    val ruby: List<AodDisplayRuby> = emptyList(),
    val layoutGroups: List<AodDisplayLayoutGroup> = emptyList(),
    val weight: String = "Medium",
    val textSizeMode: String = "normal",
    val textSizeCustom: Int = 100,
    val secondaryMode: String = "Main only",
    val animationMode: String = "Gradient",
    val glowMode: String = "Off",
    val motionMode: String = "Fluid",
    val lineSyncFillMode: String = "Top to bottom",
    val overflowMode: String = "Wrap",
    val transitionMode: String = "Fade up",
    val fontFamily: String = "noto",
    val alignmentMode: String = "auto",
    val metadataVisible: Boolean = true,
    val metadataAnchor: String = "top",
    val adaptiveSectioning: Boolean = true
)

data class AodDisplayWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

data class AodDisplayRuby(val start: Int, val end: Int, val reading: String)

data class AodDisplayLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

fun shouldRepublish(lastPublished: AodDisplayState?, next: AodDisplayState): Boolean {
    if (lastPublished == null) return true
    if (lastPublished.copy(positionMs = 0L, sampledAtElapsedMs = 0L) !=
        next.copy(positionMs = 0L, sampledAtElapsedMs = 0L)
    ) return true
    val expectedPosition = lastPublished.positionMs +
        ((next.sampledAtElapsedMs - lastPublished.sampledAtElapsedMs) * lastPublished.speed).toLong()
    return abs(next.positionMs - expectedPosition) > 750L
}

object AodStateBridge {
    private val callbacks = RemoteCallbackList<IAodLyricCallback>()
    private var currentRevision = 0L
    private var latestMessage: AodStateWireMessage = AodStateWireMessage.Hidden(
        revision = 0L,
        userId = 0,
        updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        keepAlive = false,
        wakeSignal = 0L
    )
    private var latest = AodStateWireBundleCodec.toBundle(
        requireNotNull(AodStateWireCodec.encode(latestMessage))
    )
    private var latestConfiguration: Bundle? = null
    private var lastConfigurationHash = ""
    private var lastPublished: AodDisplayState? = null

    @Synchronized
    fun register(callback: IAodLyricCallback) {
        callbacks.register(callback)
        latestConfiguration?.let { configuration ->
            try {
                callback.onConfiguration(Bundle(configuration))
            } catch (error: Exception) {
                AppLog.w(TAG, "Initial configuration delivery failed", error)
            }
        }
        val replayEnvelope = AodStateWireCodec.encode(latestMessage) ?: return
        try {
            callback.onState(AodStateWireBundleCodec.toBundle(replayEnvelope))
        } catch (error: Exception) {
            AppLog.w(TAG, "Initial state delivery failed", error)
        }
    }

    @Synchronized
    fun unregister(callback: IAodLyricCallback) {
        callbacks.unregister(callback)
    }

    @Synchronized
    fun publish(state: AodDisplayState) {
        val publishedState = normalizeAodDisplayState(state)
        if (!shouldRepublish(lastPublished, publishedState)) return
        lastPublished = publishedState
        currentRevision++
        val publication = encodeNormalizedAodStatePublication(
            state = publishedState,
            revision = currentRevision,
            updatedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        latestMessage = publication.message
        latest = AodStateWireBundleCodec.toBundle(publication.envelope)
        broadcast(latest)
    }

    @Synchronized
    fun publishConfiguration(configuration: CompiledCustomization, userId: Int) {
        if (configuration.hash == lastConfigurationHash) return
        val bundle = CompiledCustomizationBundleCodec.toBundle(configuration, userId)
        lastConfigurationHash = configuration.hash
        latestConfiguration = bundle
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                try {
                    callbacks.getBroadcastItem(index).onConfiguration(Bundle(bundle))
                } catch (error: Exception) {
                    AppLog.w(TAG, "Configuration delivery failed", error)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    @Synchronized
    fun refreshVisibleState() {
        val current = latestMessage as? AodStateWireMessage.Snapshot ?: return
        val updatedAt = SystemClock.elapsedRealtime()
        latestMessage = refreshAodStateWireSnapshot(current, updatedAt)
        val keepAlive = AodStateWireMessage.KeepAlive(
            revision = current.revision,
            userId = current.userId,
            updatedAtElapsedMs = updatedAt,
            keepAlive = current.keepAlive,
            wakeSignal = current.wakeSignal,
            playbackActive = current.playbackActive
        )
        val envelope = AodStateWireCodec.encode(keepAlive) ?: return
        broadcast(AodStateWireBundleCodec.toBundle(envelope))
    }

    @Synchronized
    fun hasVisibleState(): Boolean = latestMessage is AodStateWireMessage.Snapshot

    private fun broadcast(state: Bundle) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                try {
                    callbacks.getBroadcastItem(index).onState(Bundle(state))
                } catch (error: Exception) {
                    AppLog.w(TAG, "State delivery failed", error)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private const val TAG = "AodStateBridge"
}

internal data class AodStatePublication(
    val message: AodStateWireMessage,
    val envelope: AodStateWireEnvelope
)

internal fun refreshAodStateWireSnapshot(
    snapshot: AodStateWireMessage.Snapshot,
    updatedAtElapsedMs: Long
): AodStateWireMessage.Snapshot = snapshot.copy(
    updatedAtElapsedMs = updatedAtElapsedMs.coerceAtLeast(snapshot.updatedAtElapsedMs)
)

internal fun encodeNormalizedAodStatePublication(
    state: AodDisplayState,
    revision: Long,
    updatedAtElapsedMs: Long
): AodStatePublication {
    val intendedMessage = state.toWireMessage(revision, updatedAtElapsedMs)
    val intendedEnvelope = AodStateWireCodec.encode(intendedMessage)
    val deliveredMessage = if (intendedEnvelope == null) {
        AodStateWireMessage.Hidden(
            revision = revision,
            userId = state.userId,
            updatedAtElapsedMs = updatedAtElapsedMs,
            keepAlive = false,
            wakeSignal = state.wakeSignal,
            playbackActive = state.playbackActive
        )
    } else {
        intendedMessage
    }
    val envelope = intendedEnvelope ?: requireNotNull(AodStateWireCodec.encode(deliveredMessage))
    return AodStatePublication(deliveredMessage, envelope)
}

internal fun normalizeAodDisplayState(state: AodDisplayState): AodDisplayState {
    val original = state.original.trim().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
    val trimOffset = state.original.length - state.original.trimStart().length
    val words = state.words.asSequence()
        .take(AodStateWireLimits.MAX_WORDS)
        .map { word ->
            val range = trimAodSourceRange(
                sourceTextLength = state.original.length,
                trimmedTextLength = original.length,
                trimOffset = trimOffset,
                start = word.sourceStart,
                end = word.sourceEnd
            )
            val startMs = word.startMs.coerceAtLeast(0L)
            word.copy(
                text = word.text.takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                romanized = word.romanized.takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                startMs = startMs,
                endMs = word.endMs.coerceAtLeast(startMs),
                sourceStart = range.first,
                sourceEnd = range.second
            )
        }
        .toList()
    val ruby = state.ruby.asSequence()
        .take(AodStateWireLimits.MAX_RUBY)
        .mapNotNull { item ->
            val start = (item.start - trimOffset).coerceAtLeast(0)
            val end = (item.end - trimOffset).coerceAtMost(original.length)
            if (end <= start) null else item.copy(
                start = start,
                end = end,
                reading = item.reading.takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
            )
        }
        .toList()
    val layoutGroups = state.layoutGroups.asSequence()
        .take(AodStateWireLimits.MAX_LAYOUT_GROUPS)
        .mapNotNull { group ->
            val start = (group.start - trimOffset).coerceAtLeast(0)
            val end = (group.end - trimOffset).coerceAtMost(original.length)
            if (end <= start) null else group.copy(
                start = start,
                end = end,
                kind = group.kind.takeUtf16Prefix(AodStateWireLimits.MAX_METADATA_CHARS)
            )
        }
        .toList()
    val lineStart = state.lineStartMs.coerceAtLeast(0L)
    val duration = state.durationMs.coerceIn(0L, AodStateWireLimits.MAX_MEDIA_DURATION_MS)
    val position = state.positionMs.coerceAtLeast(0L).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
    return state.copy(
        visible = state.visible && original.isNotEmpty(),
        userId = state.userId.coerceAtLeast(0),
        trackGeneration = state.trackGeneration.coerceAtLeast(0L),
        burnInPattern = normalizeAodBurnInPattern(state.burnInPattern),
        burnInIntervalMs = normalizeAodBurnInInterval(state.burnInIntervalMs),
        original = original,
        romanized = state.romanized.trim().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
        translated = state.translated.trim().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
        metadata = state.metadata.trim().takeUtf16Prefix(AodStateWireLimits.MAX_METADATA_CHARS),
        lineStartMs = lineStart,
        lineEndMs = state.lineEndMs.coerceAtLeast(lineStart),
        durationMs = duration,
        positionMs = position,
        sampledAtElapsedMs = state.sampledAtElapsedMs.coerceAtLeast(0L),
        speed = state.speed.takeIf {
            it.isFinite() && it in 0f..AodStateWireLimits.MAX_PLAYBACK_SPEED
        } ?: 1f,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups,
        weight = normalizeAodWeight(state.weight),
        textSizeMode = normalizeAodTextSize(state.textSizeMode),
        textSizeCustom = state.textSizeCustom.coerceIn(0, 500),
        secondaryMode = normalizeAodSecondary(state.secondaryMode),
        animationMode = normalizeAodAnimation(state.animationMode),
        glowMode = normalizeAodGlow(state.glowMode),
        motionMode = "Fluid",
        lineSyncFillMode = normalizeAodLineSyncFill(state.lineSyncFillMode.trim()),
        overflowMode = normalizeAodOverflow(state.overflowMode),
        transitionMode = normalizeAodTransition(state.transitionMode.trim()),
        fontFamily = normalizeAodFontFamily(state.fontFamily),
        alignmentMode = normalizeAodAlignment(state.alignmentMode),
        metadataAnchor = normalizeAodMetadataAnchor(state.metadataAnchor)
    )
}

private fun AodDisplayState.toWireMessage(
    revision: Long,
    updatedAtElapsedMs: Long
): AodStateWireMessage = if (!visible) {
    AodStateWireMessage.Hidden(
        revision = revision,
        userId = userId,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive
    )
} else {
    AodStateWireMessage.Snapshot(
        revision = revision,
        userId = userId,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive,
        value = AodStateWireSnapshot(
            trackGeneration = trackGeneration,
            aodEnabled = aodEnabled,
            lockscreenEnabled = lockscreenEnabled,
            seamlessTransitionEnabled = seamlessTransitionEnabled,
            positionFollowingEnabled = positionFollowingEnabled,
            burnInPattern = burnInPattern,
            burnInIntervalMs = burnInIntervalMs,
            original = original,
            romanized = romanized,
            translated = translated,
            metadata = metadata,
            alignedRight = alignedRight,
            lineLevelSync = lineLevelSync,
            lineStartMs = lineStartMs,
            lineEndMs = lineEndMs,
            durationMs = durationMs,
            positionMs = positionMs,
            sampledAtElapsedMs = sampledAtElapsedMs,
            speed = speed,
            words = words.map { word ->
                AodStateWireWord(
                    text = word.text,
                    romanized = word.romanized,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    boundaryAfter = word.boundaryAfter,
                    sourceStart = word.sourceStart,
                    sourceEnd = word.sourceEnd
                )
            },
            ruby = ruby.map { item ->
                AodStateWireRuby(item.start, item.end, item.reading)
            },
            layoutGroups = layoutGroups.map { group ->
                AodStateWireLayoutGroup(
                    start = group.start,
                    end = group.end,
                    kind = group.kind,
                    keepTogether = group.keepTogether,
                    confidence = group.confidence
                )
            },
            weight = weight,
            textSizeMode = textSizeMode,
            textSizeCustom = textSizeCustom,
            secondaryMode = secondaryMode,
            animationMode = animationMode,
            glowMode = glowMode,
            motionMode = motionMode,
            lineSyncFillMode = lineSyncFillMode,
            overflowMode = overflowMode,
            transitionMode = transitionMode,
            fontFamily = fontFamily,
            alignmentMode = alignmentMode,
            metadataVisible = metadataVisible,
            metadataAnchor = metadataAnchor,
            adaptiveSectioning = adaptiveSectioning
        )
    )
}

internal fun trimAodSourceRange(
    sourceTextLength: Int,
    trimmedTextLength: Int,
    trimOffset: Int,
    start: Int,
    end: Int
): Pair<Int, Int> {
    if (start == -1 && end == -1) return -1 to -1
    if (start < 0 || start >= end || end > sourceTextLength) return -1 to -1
    val trimmedStart = (start - trimOffset).coerceAtLeast(0)
    val trimmedEnd = (end - trimOffset).coerceAtMost(trimmedTextLength)
    return if (trimmedStart < trimmedEnd) trimmedStart to trimmedEnd else -1 to -1
}
