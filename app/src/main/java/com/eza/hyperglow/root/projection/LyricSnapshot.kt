package com.eza.hyperglow.root.projection

import com.eza.hyperglow.aod.AodStateWireLimits
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.aod.normalizeAodBurnInInterval
import com.eza.hyperglow.aod.normalizeAodBurnInPattern
import com.eza.hyperglow.aod.normalizePauseLingerMs

internal data class LyricWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

internal data class LyricRuby(val start: Int, val end: Int, val reading: String)

internal data class LyricLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

internal data class LyricSnapshot(
    val revision: Long = 0L,
    val userId: Int = 0,
    val trackGeneration: Long = 0L,
    val updatedAtElapsedMs: Long = 0L,
    val transportGapStartedAtElapsedMs: Long = 0L,
    val visible: Boolean = false,
    val playbackActive: Boolean = false,
    val pauseRetentionEligible: Boolean = false,
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
    val words: List<LyricWord> = emptyList(),
    val ruby: List<LyricRuby> = emptyList(),
    val layoutGroups: List<LyricLayoutGroup> = emptyList(),
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
) {
    fun renderContent(): LyricRenderContent = LyricRenderContent(
        trackGeneration,
        original,
        romanized,
        translated,
        metadata,
        alignedRight,
        lineLevelSync,
        lineStartMs,
        lineEndMs,
        durationMs,
        positionMs,
        sampledAtElapsedMs,
        speed,
        words,
        ruby,
        layoutGroups,
        weight,
        textSizeMode,
        textSizeCustom,
        secondaryMode,
        animationMode,
        glowMode,
        motionMode,
        lineSyncFillMode,
        overflowMode,
        transitionMode,
        fontFamily,
        alignmentMode,
        metadataVisible,
        metadataAnchor,
        adaptiveSectioning
    )
}

internal data class LyricRenderContent(
    val trackGeneration: Long,
    val original: String,
    val romanized: String,
    val translated: String,
    val metadata: String,
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val durationMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<LyricWord>,
    val ruby: List<LyricRuby>,
    val layoutGroups: List<LyricLayoutGroup>,
    val weight: String,
    val textSizeMode: String,
    val textSizeCustom: Int,
    val secondaryMode: String,
    val animationMode: String,
    val glowMode: String,
    val motionMode: String,
    val lineSyncFillMode: String,
    val overflowMode: String,
    val transitionMode: String,
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val adaptiveSectioning: Boolean
)

internal data class LyricKeepAliveSignal(
    val revision: Long,
    val updatedAtElapsedMs: Long,
    val keepAlive: Boolean,
    val wakeSignal: Long,
    val playbackActive: Boolean = false,
    val pauseRetentionEligible: Boolean = false,
    val userId: Int = 0
)

internal fun LyricSnapshot.freezeAt(
    nowElapsedMs: Long,
    keepAliveWhileFrozen: Boolean
): LyricSnapshot {
    val elapsedMs = (nowElapsedMs - sampledAtElapsedMs).coerceAtLeast(0L)
    val projectedPosition = positionMs + (elapsedMs * speed.coerceAtLeast(0f)).toLong()
    val frozenPosition = if (durationMs > 0L) {
        projectedPosition.coerceIn(0L, durationMs)
    } else {
        projectedPosition.coerceAtLeast(0L)
    }
    return copy(
        updatedAtElapsedMs = nowElapsedMs,
        visible = true,
        keepAlive = keepAliveWhileFrozen,
        positionMs = frozenPosition,
        sampledAtElapsedMs = nowElapsedMs,
        speed = 0f
    )
}

/**
 * The hidden edge a retention episode is measured from, and which kind of retention it opened.
 *
 * The published `updatedAtElapsedMs` is the time the producer sent the message, not the time
 * playback stopped, and the producer republishes the same paused state whenever Spotify revises it —
 * a position update, another app taking the media session. Anchoring the frozen snapshot to each
 * arriving message therefore restarted the finite timer forever: a lyric paused long ago reappeared
 * for another full linger every time something else touched the session, which is how a stale
 * Spotify lyric ended up drawn over an AOD showing a different player. The episode keeps its first
 * edge until a visible snapshot or a terminal hidden state ends it.
 */
internal data class LyricRetentionAnchor(
    val pauseRetentionEligible: Boolean,
    val atElapsedMs: Long
)

internal fun nextLyricRetentionAnchor(
    incoming: LyricSnapshot,
    anchor: LyricRetentionAnchor?,
    nowElapsedMs: Long
): LyricRetentionAnchor? {
    if (incoming.visible) return null
    val eligible = when {
        incoming.pauseRetentionEligible -> true
        incoming.playbackActive -> false
        else -> return null
    }
    return anchor?.takeIf { it.pauseRetentionEligible == eligible }
        ?: LyricRetentionAnchor(
            eligible,
            if (eligible) {
                incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
            } else {
                incoming.transportGapStartedAtElapsedMs
                    .takeIf { it > 0L }
                    ?.coerceAtMost(nowElapsedMs)
                    ?: incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
            }
        )
}

internal fun stampTransportGapEdge(
    current: LyricSnapshot?,
    incoming: LyricSnapshot
): LyricSnapshot {
    if (incoming.visible || !incoming.playbackActive || incoming.pauseRetentionEligible) {
        return incoming.copy(transportGapStartedAtElapsedMs = 0L)
    }
    val sameGap = current?.takeIf {
        !it.visible && it.playbackActive && !it.pauseRetentionEligible &&
            it.trackGeneration == incoming.trackGeneration
    }
    val edge = sameGap?.transportGapStartedAtElapsedMs?.takeIf { it > 0L }
        ?: incoming.updatedAtElapsedMs
    return incoming.copy(transportGapStartedAtElapsedMs = edge)
}

internal fun LyricRetentionAnchor?.edgeFor(
    pauseRetentionEligible: Boolean,
    fallbackElapsedMs: Long
): Long = this?.takeIf { it.pauseRetentionEligible == pauseRetentionEligible }?.atElapsedMs
    ?: fallbackElapsedMs

/**
 * A hidden state carrying neither playback nor pause retention. The shared snapshot contract calls
 * this terminal: nothing may be presented from it, and the cached visible snapshot it would have
 * been rebuilt from is dropped with it.
 */
internal fun LyricSnapshot.isTerminalHidden(): Boolean =
    !visible && !playbackActive && !pauseRetentionEligible

internal fun pauseLingerRemainingMs(
    pausedAtElapsedMs: Long,
    configuredDurationMs: Long,
    nowElapsedMs: Long
): Long? {
    val durationMs = normalizePauseLingerMs(configuredDurationMs)
    if (durationMs < 0L) return Long.MAX_VALUE
    val elapsedMs = (nowElapsedMs - pausedAtElapsedMs).coerceAtLeast(0L)
    return (durationMs - elapsedMs).takeIf { it > 0L }
}

internal fun LyricSnapshot.isAuthorizedForPresentation(): Boolean =
    playbackActive || pauseRetentionEligible && speed == 0f

internal sealed interface LyricProjectionMessage {
    val revision: Long
    val updatedAtElapsedMs: Long
    val userId: Int

    data class Snapshot(val value: LyricSnapshot) : LyricProjectionMessage {
        override val revision: Long get() = value.revision
        override val updatedAtElapsedMs: Long get() = value.updatedAtElapsedMs
        override val userId: Int get() = value.userId
    }

    data class KeepAlive(val value: LyricKeepAliveSignal) : LyricProjectionMessage {
        override val revision: Long get() = value.revision
        override val updatedAtElapsedMs: Long get() = value.updatedAtElapsedMs
        override val userId: Int get() = value.userId
    }
}

internal fun normalizeLyricSnapshot(snapshot: LyricSnapshot): LyricSnapshot {
    val original = snapshot.original.trim().take(MAX_LYRIC_LENGTH)
    val trimOffset = snapshot.original.length - snapshot.original.trimStart().length
    val words = snapshot.words.asSequence().take(MAX_WORDS).map { word ->
        val range = normalizeSourceRange(
            snapshot.original.length,
            original.length,
            trimOffset,
            word.sourceStart,
            word.sourceEnd
        )
        word.copy(
            text = word.text.take(MAX_LYRIC_LENGTH),
            romanized = word.romanized.take(MAX_LYRIC_LENGTH),
            startMs = word.startMs.coerceAtLeast(0L),
            endMs = word.endMs.coerceAtLeast(word.startMs.coerceAtLeast(0L)),
            sourceStart = range.first,
            sourceEnd = range.second
        )
    }.toList()
    val ruby = snapshot.ruby.asSequence().take(MAX_RUBY).mapNotNull { item ->
        val start = (item.start - trimOffset).coerceAtLeast(0)
        val end = (item.end - trimOffset).coerceAtMost(original.length)
        if (end <= start) null else item.copy(
            start = start,
            end = end,
            reading = item.reading.take(MAX_LYRIC_LENGTH)
        )
    }.toList()
    val layoutGroups = snapshot.layoutGroups.asSequence().take(MAX_LAYOUT_GROUPS).mapNotNull { group ->
        val start = (group.start - trimOffset).coerceAtLeast(0)
        val end = (group.end - trimOffset).coerceAtMost(original.length)
        if (end <= start) null else group.copy(
            start = start,
            end = end,
            kind = group.kind.take(MAX_METADATA_LENGTH),
            confidence = group.confidence.coerceIn(0.0, 1.0)
        )
    }.toList()
    val position = snapshot.positionMs.coerceAtLeast(0L)
    val duration = snapshot.durationMs.coerceAtLeast(0L)
    return snapshot.copy(
        revision = snapshot.revision.coerceAtLeast(0L),
        trackGeneration = snapshot.trackGeneration.coerceAtLeast(0L),
        updatedAtElapsedMs = snapshot.updatedAtElapsedMs.coerceAtLeast(0L),
        visible = snapshot.visible && original.isNotEmpty(),
        original = original,
        romanized = snapshot.romanized.trim().take(MAX_LYRIC_LENGTH),
        translated = snapshot.translated.trim().take(MAX_LYRIC_LENGTH),
        metadata = snapshot.metadata.trim().take(MAX_METADATA_LENGTH),
        lineStartMs = snapshot.lineStartMs.coerceAtLeast(0L),
        lineEndMs = snapshot.lineEndMs.coerceAtLeast(snapshot.lineStartMs.coerceAtLeast(0L)),
        durationMs = duration,
        positionMs = if (duration > 0L) position.coerceAtMost(duration) else position,
        sampledAtElapsedMs = snapshot.sampledAtElapsedMs.coerceAtLeast(0L),
        speed = snapshot.speed.takeIf { it.isFinite() && it >= 0f } ?: 1f,
        burnInPattern = normalizeAodBurnInPattern(snapshot.burnInPattern),
        burnInIntervalMs = normalizeAodBurnInInterval(snapshot.burnInIntervalMs),
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups,
        textSizeCustom = snapshot.textSizeCustom.coerceIn(0, 500)
    )
}

internal fun AodStateWireMessage.toLyricProjectionMessage(): LyricProjectionMessage = when (this) {
    is AodStateWireMessage.Snapshot -> LyricProjectionMessage.Snapshot(
        LyricSnapshot(
            revision = revision,
            userId = userId,
            trackGeneration = value.trackGeneration,
            updatedAtElapsedMs = updatedAtElapsedMs,
            visible = true,
            playbackActive = playbackActive,
            pauseRetentionEligible = false,
            aodEnabled = value.aodEnabled,
            lockscreenEnabled = value.lockscreenEnabled,
            seamlessTransitionEnabled = value.seamlessTransitionEnabled,
            keepAlive = keepAlive,
            positionFollowingEnabled = value.positionFollowingEnabled,
            burnInPattern = value.burnInPattern,
            burnInIntervalMs = value.burnInIntervalMs,
            wakeSignal = wakeSignal,
            original = value.original,
            romanized = value.romanized,
            translated = value.translated,
            metadata = value.metadata,
            alignedRight = value.alignedRight,
            lineLevelSync = value.lineLevelSync,
            lineStartMs = value.lineStartMs,
            lineEndMs = value.lineEndMs,
            durationMs = value.durationMs,
            positionMs = value.positionMs,
            sampledAtElapsedMs = value.sampledAtElapsedMs,
            speed = value.speed,
            words = value.words.map { word ->
                LyricWord(
                    text = word.text,
                    romanized = word.romanized,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    boundaryAfter = word.boundaryAfter,
                    sourceStart = word.sourceStart,
                    sourceEnd = word.sourceEnd
                )
            },
            ruby = value.ruby.map { item ->
                LyricRuby(item.start, item.end, item.reading)
            },
            layoutGroups = value.layoutGroups.map { group ->
                LyricLayoutGroup(
                    start = group.start,
                    end = group.end,
                    kind = group.kind,
                    keepTogether = group.keepTogether,
                    confidence = group.confidence
                )
            },
            weight = value.weight,
            textSizeMode = value.textSizeMode,
            textSizeCustom = value.textSizeCustom,
            secondaryMode = value.secondaryMode,
            animationMode = value.animationMode,
            glowMode = value.glowMode,
            motionMode = value.motionMode,
            lineSyncFillMode = value.lineSyncFillMode,
            overflowMode = value.overflowMode,
            transitionMode = value.transitionMode,
            fontFamily = value.fontFamily,
            alignmentMode = value.alignmentMode,
            metadataVisible = value.metadataVisible,
            metadataAnchor = value.metadataAnchor,
            adaptiveSectioning = value.adaptiveSectioning
        )
    )
    is AodStateWireMessage.Hidden -> LyricProjectionMessage.Snapshot(
        LyricSnapshot(
            revision = revision,
            userId = userId,
            updatedAtElapsedMs = updatedAtElapsedMs,
            visible = false,
            playbackActive = playbackActive,
            pauseRetentionEligible = pauseRetentionEligible && !playbackActive,
            keepAlive = keepAlive,
            wakeSignal = wakeSignal
        )
    )
    is AodStateWireMessage.KeepAlive -> LyricProjectionMessage.KeepAlive(
        LyricKeepAliveSignal(
            revision = revision,
            updatedAtElapsedMs = updatedAtElapsedMs,
            keepAlive = keepAlive,
            wakeSignal = wakeSignal,
            playbackActive = playbackActive,
            pauseRetentionEligible = false,
            userId = userId
        )
    )
}

private fun normalizeSourceRange(
    sourceLength: Int,
    trimmedLength: Int,
    trimOffset: Int,
    start: Int,
    end: Int
): Pair<Int, Int> {
    if (start == -1 && end == -1) return -1 to -1
    if (start < 0 || start >= end || end > sourceLength) return -1 to -1
    val trimmedStart = (start - trimOffset).coerceAtLeast(0)
    val trimmedEnd = (end - trimOffset).coerceAtMost(trimmedLength)
    return if (trimmedStart < trimmedEnd) trimmedStart to trimmedEnd else -1 to -1
}

private const val MAX_LYRIC_LENGTH = AodStateWireLimits.MAX_LYRIC_CHARS
private const val MAX_METADATA_LENGTH = AodStateWireLimits.MAX_METADATA_CHARS
private const val MAX_WORDS = AodStateWireLimits.MAX_WORDS
private const val MAX_RUBY = AodStateWireLimits.MAX_RUBY
private const val MAX_LAYOUT_GROUPS = AodStateWireLimits.MAX_LAYOUT_GROUPS
