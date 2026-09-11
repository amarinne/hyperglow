package com.eza.hyperglow.aod

import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import kotlin.math.abs
import kotlin.math.roundToInt

data class AodDisplayState(
    val visible: Boolean,
    val playbackActive: Boolean = false,
    val pauseRetentionEligible: Boolean = false,
    val userId: Int = 0,
    val trackGeneration: Long = 0L,
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val seamlessTransitionEnabled: Boolean = true,
    val keepAlive: Boolean = false,
    val positionFollowingEnabled: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val suppressStockAodContent: Boolean = false,
    val aodRotateWithDevice: Boolean = false,
    val aodRotationMode: String = AOD_ROTATION_MODE_PORTRAIT,
    val aodCanvasAnchor: Float = 0.5f,
    val aodRotationSettleMs: Long = 1_000L,
    val aodCanvasAnchorLandscape: Float = 0.5f,
    val aodLandscapeTextScale: Float = 1f,
    val aodCanvasPaddingPortraitXPercent: Float = 2f,
    val aodCanvasPaddingPortraitYPercent: Float = 2f,
    val aodCanvasPaddingLandscapeXPercent: Float = 2f,
    val aodCanvasPaddingLandscapeYPercent: Float = 2f,
    val secondLine: AodDisplaySecondLine? = null,
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

/**
 * One overlapping sung line kept on screen next to the primary: same timed
 * word data, own text/secondaries, own active window. Null renders solo.
 */
data class AodDisplaySecondLine(
    val text: String = "",
    val romanized: String = "",
    val translated: String = "",
    val alignedRight: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val words: List<AodDisplayWord> = emptyList(),
    val ruby: List<AodDisplayRuby> = emptyList(),
    val layoutGroups: List<AodDisplayLayoutGroup> = emptyList()
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
    private var lastFullPublishAtElapsedMs = Long.MIN_VALUE

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
    fun hasSystemUiCallback(): Boolean = callbacks.registeredCallbackCount > 0

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
        lastFullPublishAtElapsedMs = publication.message.updatedAtElapsedMs
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
        val refreshed = refreshAodStateWireSnapshot(current, updatedAt)
        latestMessage = refreshed
        val republish = shouldRepublishFullSnapshot(updatedAt, lastFullPublishAtElapsedMs)
        val message: AodStateWireMessage = if (republish) refreshed else AodStateWireMessage.KeepAlive(
            revision = current.revision,
            userId = current.userId,
            updatedAtElapsedMs = updatedAt,
            keepAlive = current.keepAlive,
            wakeSignal = current.wakeSignal,
            playbackActive = current.playbackActive,
            pauseRetentionEligible = current.pauseRetentionEligible
        )
        val envelope = AodStateWireCodec.encode(message) ?: return
        if (republish) lastFullPublishAtElapsedMs = updatedAt
        broadcast(AodStateWireBundleCodec.toBundle(envelope))
    }

    @Synchronized
    fun hasVisibleState(): Boolean = latestMessage is AodStateWireMessage.Snapshot

    private fun broadcast(state: Bundle) {
        val count = callbacks.beginBroadcast()
        // Publishing into an empty room looks identical to publishing normally from this side, and
        // the consumer's projection expires a few seconds later as if the app had gone quiet.
        if (count != lastBroadcastCount) {
            lastBroadcastCount = count
            AppLog.i(TAG, "State subscribers=$count")
        }
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

    private var lastBroadcastCount = -1

    private const val TAG = "AodStateBridge"
}

internal data class AodStatePublication(
    val message: AodStateWireMessage,
    val envelope: AodStateWireEnvelope
)

/**
 * Whether a heartbeat should carry the whole snapshot instead of a keepalive.
 *
 * A keepalive can only renew a projection the consumer still holds: one that expired has no
 * revision to match and rejects every heartbeat, so the consumer stays dark until the song happens
 * to change a line. Re-sending the full snapshot on a slow cadence bounds that recovery without
 * paying the payload on every beat.
 */
internal fun shouldRepublishFullSnapshot(
    nowElapsedMs: Long,
    lastFullPublishAtElapsedMs: Long,
    intervalMs: Long = FULL_SNAPSHOT_REPUBLISH_MS
): Boolean = lastFullPublishAtElapsedMs == Long.MIN_VALUE ||
    nowElapsedMs - lastFullPublishAtElapsedMs >= intervalMs

/** Three heartbeats of margin against the consumer's freshness window. */
internal const val FULL_SNAPSHOT_REPUBLISH_MS = 4_500L

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
            playbackActive = state.playbackActive,
            pauseRetentionEligible = state.pauseRetentionEligible
        )
    } else {
        intendedMessage
    }
    val envelope = intendedEnvelope ?: requireNotNull(AodStateWireCodec.encode(deliveredMessage))
    return AodStatePublication(deliveredMessage, envelope)
}

private fun normalizedDisplayWords(
    words: List<AodDisplayWord>,
    sourceTextLength: Int,
    trimmedTextLength: Int,
    trimOffset: Int,
    maxWords: Int
): List<AodDisplayWord> = words.asSequence()
    .take(maxWords.coerceAtLeast(0))
    .map { word ->
        val range = trimAodSourceRange(
            sourceTextLength = sourceTextLength,
            trimmedTextLength = trimmedTextLength,
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

private fun normalizedDisplayRuby(
    ruby: List<AodDisplayRuby>,
    trimmedTextLength: Int,
    trimOffset: Int,
    maxRuby: Int
): List<AodDisplayRuby> = ruby.asSequence()
    .take(maxRuby.coerceAtLeast(0))
    .mapNotNull { item ->
        val start = (item.start - trimOffset).coerceAtLeast(0)
        val end = (item.end - trimOffset).coerceAtMost(trimmedTextLength)
        if (end <= start) null else item.copy(
            start = start,
            end = end,
            reading = item.reading.takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
        )
    }
    .toList()

private fun normalizedDisplayLayoutGroups(
    layoutGroups: List<AodDisplayLayoutGroup>,
    trimmedTextLength: Int,
    trimOffset: Int,
    maxGroups: Int
): List<AodDisplayLayoutGroup> = layoutGroups.asSequence()
    .take(maxGroups.coerceAtLeast(0))
    .mapNotNull { group ->
        val start = (group.start - trimOffset).coerceAtLeast(0)
        val end = (group.end - trimOffset).coerceAtMost(trimmedTextLength)
        if (end <= start) null else group.copy(
            start = start,
            end = end,
            kind = group.kind.takeUtf16Prefix(AodStateWireLimits.MAX_METADATA_CHARS)
        )
    }
    .toList()

internal fun normalizeAodDisplayState(state: AodDisplayState): AodDisplayState {
    val original = state.original.trim().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
    val trimOffset = state.original.length - state.original.trimStart().length
    val words = normalizedDisplayWords(
        state.words, state.original.length, original.length, trimOffset,
        AodStateWireLimits.MAX_WORDS
    )
    val ruby = normalizedDisplayRuby(
        state.ruby, original.length, trimOffset, AodStateWireLimits.MAX_RUBY
    )
    val layoutGroups = normalizedDisplayLayoutGroups(
        state.layoutGroups, original.length, trimOffset,
        AodStateWireLimits.MAX_LAYOUT_GROUPS
    )
    // The concurrent line shares the wire budgets with the primary, so it is
    // capped against the remainder to keep the combined snapshot encodable.
    val incomingSecond = state.secondLine?.takeIf { it.text.isNotBlank() }
    val secondText = incomingSecond?.text?.trim()
        ?.takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS).orEmpty()
    val secondTrimOffset = (incomingSecond?.text?.length ?: 0) -
        (incomingSecond?.text?.trimStart()?.length ?: 0)
    val secondLine = if (incomingSecond == null || secondText.isBlank()) {
        null
    } else {
        val secondStart = incomingSecond.lineStartMs.coerceAtLeast(0L)
        incomingSecond.copy(
            text = secondText,
            romanized = incomingSecond.romanized.trim()
                .takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
            translated = incomingSecond.translated.trim()
                .takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
            lineStartMs = secondStart,
            lineEndMs = incomingSecond.lineEndMs.coerceAtLeast(secondStart),
            words = normalizedDisplayWords(
                incomingSecond.words, incomingSecond.text.length, secondText.length,
                secondTrimOffset,
                AodStateWireLimits.MAX_WORDS - words.size
            ),
            ruby = normalizedDisplayRuby(
                incomingSecond.ruby, secondText.length, secondTrimOffset,
                AodStateWireLimits.MAX_RUBY - ruby.size
            ),
            layoutGroups = normalizedDisplayLayoutGroups(
                incomingSecond.layoutGroups, secondText.length, secondTrimOffset,
                AodStateWireLimits.MAX_LAYOUT_GROUPS - layoutGroups.size
            )
        )
    }
    val lineStart = state.lineStartMs.coerceAtLeast(0L)
    val duration = state.durationMs.coerceIn(0L, AodStateWireLimits.MAX_MEDIA_DURATION_MS)
    val position = state.positionMs.coerceAtLeast(0L).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
    return state.copy(
        visible = state.visible && original.isNotEmpty(),
        pauseRetentionEligible = state.pauseRetentionEligible &&
            !state.visible && !state.playbackActive,
        userId = state.userId.coerceAtLeast(0),
        trackGeneration = state.trackGeneration.coerceAtLeast(0L),
        burnInPattern = normalizeAodBurnInPattern(state.burnInPattern),
        burnInIntervalMs = normalizeAodBurnInInterval(state.burnInIntervalMs),
        aodRotationMode = normalizeAodRotationMode(state.aodRotationMode),
        aodCanvasAnchor = normalizeAodCanvasAnchor(state.aodCanvasAnchor),
        aodRotationSettleMs = normalizeAodRotationSettleMs(state.aodRotationSettleMs),
        aodCanvasAnchorLandscape = normalizeAodCanvasAnchor(state.aodCanvasAnchorLandscape),
        aodLandscapeTextScale = normalizeAodLandscapeTextScale(state.aodLandscapeTextScale),
        aodCanvasPaddingPortraitXPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingPortraitXPercent
        ),
        aodCanvasPaddingPortraitYPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingPortraitYPercent
        ),
        aodCanvasPaddingLandscapeXPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingLandscapeXPercent
        ),
        aodCanvasPaddingLandscapeYPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingLandscapeYPercent
        ),
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
        secondLine = secondLine,
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
        playbackActive = playbackActive,
        pauseRetentionEligible = pauseRetentionEligible
    )
} else {
    AodStateWireMessage.Snapshot(
        revision = revision,
        userId = userId,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive,
        pauseRetentionEligible = pauseRetentionEligible,
        value = AodStateWireSnapshot(
            trackGeneration = trackGeneration,
            aodEnabled = aodEnabled,
            lockscreenEnabled = lockscreenEnabled,
            seamlessTransitionEnabled = seamlessTransitionEnabled,
            positionFollowingEnabled = positionFollowingEnabled,
            burnInPattern = burnInPattern,
            burnInIntervalMs = burnInIntervalMs,
            suppressStockAodContent = suppressStockAodContent,
            aodRotateWithDevice = aodRotateWithDevice,
            aodRotationMode = aodRotationMode,
            aodCanvasAnchor = aodCanvasAnchor,
            aodRotationSettleMs = aodRotationSettleMs,
            aodCanvasAnchorLandscape = aodCanvasAnchorLandscape,
            aodLandscapeTextScale = aodLandscapeTextScale,
            aodCanvasPaddingDp = (aodCanvasPaddingPortraitXPercent * 4f).roundToInt()
                .coerceIn(0, 64),
            aodCanvasPaddingXPercent = aodCanvasPaddingPortraitXPercent,
            aodCanvasPaddingYPercent = aodCanvasPaddingPortraitYPercent,
            aodCanvasPaddingPortraitXPercent = aodCanvasPaddingPortraitXPercent,
            aodCanvasPaddingPortraitYPercent = aodCanvasPaddingPortraitYPercent,
            aodCanvasPaddingLandscapeXPercent = aodCanvasPaddingLandscapeXPercent,
            aodCanvasPaddingLandscapeYPercent = aodCanvasPaddingLandscapeYPercent,
            secondLine = secondLine?.let { second ->
                AodStateWireSecondLine(
                    text = second.text,
                    romanized = second.romanized,
                    translated = second.translated,
                    alignedRight = second.alignedRight,
                    lineStartMs = second.lineStartMs,
                    lineEndMs = second.lineEndMs,
                    words = second.words.map { word ->
                        AodStateWireWord(
                            word.text, word.romanized, word.startMs, word.endMs,
                            word.boundaryAfter, word.sourceStart, word.sourceEnd
                        )
                    },
                    ruby = second.ruby.map { item ->
                        AodStateWireRuby(item.start, item.end, item.reading)
                    },
                    layoutGroups = second.layoutGroups.map { group ->
                        AodStateWireLayoutGroup(
                            group.start, group.end, group.kind, group.keepTogether,
                            group.confidence
                        )
                    }
                )
            },
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
