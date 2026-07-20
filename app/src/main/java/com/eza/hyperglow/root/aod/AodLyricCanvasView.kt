package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import com.eza.hyperglow.root.HookLogger
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AodCanvasWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val partOfWord: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

internal data class AodCanvasRuby(val start: Int, val end: Int, val reading: String)

internal data class OriginalTextRun(val start: Int, val end: Int, val x: Float)

internal fun originalTextRuns(
    textLength: Int,
    rubyBaseRuns: List<OriginalTextRun>,
    widthBefore: (Int) -> Float
): List<OriginalTextRun> {
    if (textLength <= 0) return emptyList()
    if (rubyBaseRuns.isEmpty()) return listOf(OriginalTextRun(0, textLength, 0f))
    val runs = ArrayList<OriginalTextRun>(rubyBaseRuns.size * 2 + 1)
    var cursor = 0
    rubyBaseRuns.forEach { rubyRun ->
        val start = rubyRun.start.coerceIn(cursor, textLength)
        val end = rubyRun.end.coerceIn(start, textLength)
        if (cursor < start) runs += OriginalTextRun(cursor, start, widthBefore(cursor))
        if (start < end) runs += OriginalTextRun(start, end, rubyRun.x)
        cursor = end
    }
    if (cursor < textLength) runs += OriginalTextRun(cursor, textLength, widthBefore(cursor))
    return runs
}

internal fun transportedWordOffset(text: String, word: AodCanvasWord): IntRange? =
    if (word.sourceStart >= 0 && word.sourceStart < word.sourceEnd && word.sourceEnd <= text.length) {
        word.sourceStart until word.sourceEnd
    } else {
        null
    }

internal fun aodWordGapAfter(partOfWord: Boolean, gap: Float): Float =
    if (partOfWord) 0f else gap

internal fun attachedWordRanges(words: List<AodCanvasWord>): List<IntRange> {
    if (words.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var start = 0
    words.forEachIndexed { index, word ->
        if (!word.partOfWord || index == words.lastIndex) {
            ranges += start until index + 1
            start = index + 1
        }
    }
    return ranges
}

internal fun authoredWordSeparator(
    text: String,
    current: AodCanvasWord,
    next: AodCanvasWord
): String? {
    val currentRange = transportedWordOffset(text, current) ?: return null
    val nextRange = transportedWordOffset(text, next) ?: return null
    val currentEnd = currentRange.last + 1
    if (currentEnd > nextRange.first) return null
    return text.substring(currentEnd, nextRange.first).takeIf { separator ->
        separator.all(Char::isWhitespace)
    }
}

internal fun coalesceRubyWords(
    text: String,
    words: List<AodCanvasWord>,
    ruby: List<AodCanvasRuby>
): List<AodCanvasWord> {
    val crossings = ruby.asSequence()
        .filter { segment -> segment.start >= 0 && segment.start < segment.end && segment.end <= text.length }
        .mapNotNull { segment ->
        val covered = words.indices.filter { index ->
            val range = transportedWordOffset(text, words[index])
            range != null && segment.start < range.last + 1 && segment.end > range.first
        }
        if (covered.size > 1 && (covered.first()..covered.last()).all {
                transportedWordOffset(text, words[it]) != null
            }) covered.first()..covered.last() else null
    }.sortedBy { it.first }
        .toList()
    if (crossings.isEmpty()) return words

    val merged = ArrayList<IntRange>()
    crossings.forEach { range ->
        if (merged.isEmpty() || range.first > merged.last().last + 1) merged += range
        else merged[merged.lastIndex] = merged.last().first..maxOf(merged.last().last, range.last)
    }
    val output = ArrayList<AodCanvasWord>()
    var index = 0
    while (index < words.size) {
        val range = merged.firstOrNull { it.first == index }
        if (range == null) {
            output += words[index++]
            continue
        }
        val first = words[range.first]
        val last = words[range.last]
        val start = first.sourceStart
        val end = last.sourceEnd
        output += AodCanvasWord(
            text.substring(start, end),
            joinedRomanizedWords(words.subList(range.first, range.last + 1).map { it.romanized to it.partOfWord }),
            first.startMs,
            maxOf(first.startMs + 1L, last.endMs),
            last.partOfWord,
            start,
            end
        )
        index = range.last + 1
    }
    return output
}

internal data class AodCanvasLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

internal data class RubySpanGeometry(
    val spanX: Float,
    val spanWidth: Float,
    val baseX: Float,
    val baseWidth: Float,
    val extraWidth: Float,
    val rubyCenterX: Float
)

internal data class MetadataLayoutBounds(
    val metadataBaseline: Float,
    val lyricStart: Float,
    val lyricEnd: Float
)

internal enum class SpotlightWordState { SUNG, ACTIVE, UNSUNG }

internal data class AodCanvasContent(
    val trackGeneration: Long,
    val metadata: String,
    val original: String,
    val romanized: String,
    val translated: String,
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<AodCanvasWord>,
    val ruby: List<AodCanvasRuby>,
    val layoutGroups: List<AodCanvasLayoutGroup>,
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
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>
)

internal data class AodCanvasLineIdentity(
    val trackGeneration: Long,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val original: String
)

internal fun aodCanvasLineIdentity(content: AodCanvasContent): AodCanvasLineIdentity =
    AodCanvasLineIdentity(
        content.trackGeneration,
        content.lineStartMs,
        content.lineEndMs,
        content.original
    )

internal data class AodResolvedPalette(
    val primaryText: Int,
    val secondaryText: Int,
    val metadataText: Int,
    val sungText: Int,
    val unsungText: Int,
    val glow: Int,
    val accent: Int
)

internal fun resolveAodPalette(tokens: Map<String, String>): AodResolvedPalette =
    AodResolvedPalette(
        primaryText = resolvePaletteColor(tokens["primaryText"], Color.WHITE),
        secondaryText = resolvePaletteColor(tokens["secondaryText"], Color.WHITE),
        metadataText = resolvePaletteColor(tokens["metadataText"], 0xFFB3B3B3.toInt()),
        sungText = resolvePaletteColor(tokens["sungText"], Color.WHITE),
        unsungText = resolvePaletteColor(tokens["unsungText"], Color.WHITE),
        glow = resolvePaletteColor(tokens["glow"], Color.WHITE),
        accent = resolvePaletteColor(tokens["accent"], Color.WHITE)
    )

private fun resolvePaletteColor(token: String?, fallback: Int): Int = when (token) {
    "dimmed" -> opaqueRgb(
        (((fallback ushr 16) and 0xFF) * 0.72f).roundToInt(),
        (((fallback ushr 8) and 0xFF) * 0.72f).roundToInt(),
        ((fallback and 0xFF) * 0.72f).roundToInt()
    )
    else -> fallback
}

private fun opaqueRgb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)

internal fun splitContinuousFill(progress: Float, lineWidths: List<Float>): List<Float> {
    val totalWidth = lineWidths.sumOf { it.coerceAtLeast(0f).toDouble() }.toFloat()
    if (totalWidth <= 0f) return lineWidths.map { 0f }
    var precedingWidth = 0f
    return lineWidths.map { width ->
        val safeWidth = width.coerceAtLeast(0f)
        val local = continuousFillAt(progress, totalWidth, precedingWidth, safeWidth)
        precedingWidth += safeWidth
        local
    }
}

internal fun continuousFillAt(
    progress: Float,
    totalWidth: Float,
    precedingWidth: Float,
    width: Float
): Float = if (width <= 0f || totalWidth <= 0f) {
    0f
} else {
    ((progress.coerceIn(0f, 1f) * totalWidth - precedingWidth) / width).coerceIn(0f, 1f)
}

internal fun rubyLineIndex(start: Int, lineStarts: List<Int>, lineEnds: List<Int>): Int? =
    lineStarts.indices.firstOrNull { index -> start >= lineStarts[index] && start < lineEnds[index] }

internal fun lexicalGroupIds(
    wordOffsets: List<IntRange?>,
    groups: List<AodCanvasLayoutGroup>
): List<Int?> = wordOffsets.map { offset ->
    if (offset == null) null else groups.indexOfFirst { group ->
        group.keepTogether && group.end > offset.first && group.start <= offset.last
    }.takeIf { it >= 0 }
}

internal fun coveredLayoutRanges(text: String, groups: List<AodCanvasLayoutGroup>): List<IntRange> {
    val valid = groups.asSequence()
        .filter { it.keepTogether && it.start >= 0 && it.end > it.start && it.end <= text.length }
        .sortedBy { it.start }
        .toList()
    val ranges = ArrayList<IntRange>()
    var cursor = 0
    var groupIndex = 0
    while (cursor < text.length) {
        while (groupIndex < valid.size && valid[groupIndex].end <= cursor) groupIndex++
        val group = valid.getOrNull(groupIndex)
        if (group != null && group.start < cursor) {
            groupIndex++
            continue
        }
        if (group != null && group.start == cursor) {
            ranges += group.start until group.end
            cursor = group.end
            groupIndex++
            continue
        }
        val stop = group?.start?.coerceAtLeast(cursor) ?: text.length
        while (cursor < stop) {
            while (cursor < stop && text[cursor].isWhitespace()) cursor++
            val start = cursor
            while (cursor < stop && !text[cursor].isWhitespace()) cursor++
            if (cursor > start) ranges += start until cursor
        }
    }
    return ranges
}

internal fun balancedChunkRanges(widths: List<Float>, available: Float, maxLines: Int): List<IntRange> {
    if (widths.isEmpty() || maxLines <= 0) return emptyList()
    if (widths.size == 1 || available <= 0f) return listOf(widths.indices)
    val greedy = ArrayList<IntRange>()
    var start = 0
    var width = 0f
    widths.forEachIndexed { index, item ->
        if (index > start && width + item > available) {
            greedy += start until index
            start = index
            width = 0f
        }
        width += item
    }
    greedy += start until widths.size
    val lineCount = greedy.size.coerceAtMost(maxLines)
    if (lineCount <= 1) return listOf(widths.indices)
    val cappedGreedy = if (greedy.size <= maxLines) greedy else ArrayList<IntRange>(maxLines).apply {
        addAll(greedy.take(maxLines - 1))
        add(greedy[maxLines - 1].first until widths.size)
    }

    val prefix = FloatArray(widths.size + 1)
    widths.indices.forEach { index -> prefix[index + 1] = prefix[index] + widths[index] }
    val target = prefix.last() / lineCount
    val infinity = Float.POSITIVE_INFINITY
    val costs = Array(lineCount + 1) { FloatArray(widths.size + 1) { infinity } }
    val previous = Array(lineCount + 1) { IntArray(widths.size + 1) { -1 } }
    costs[0][0] = 0f
    for (line in 1..lineCount) {
        for (end in line..widths.size) {
            for (candidate in line - 1 until end) {
                val lineWidth = prefix[end] - prefix[candidate]
                val allowOverflow = line == lineCount && end == widths.size && greedy.size > maxLines
                if (lineWidth > available && !allowOverflow) continue
                val previousCost = costs[line - 1][candidate]
                if (!previousCost.isFinite()) continue
                val delta = lineWidth - target
                val cost = previousCost + delta * delta
                if (cost < costs[line][end]) {
                    costs[line][end] = cost
                    previous[line][end] = candidate
                }
            }
        }
    }
    if (previous[lineCount][widths.size] < 0) return cappedGreedy
    val result = ArrayList<IntRange>(lineCount)
    var line = lineCount
    var end = widths.size
    while (line > 0) {
        val candidate = previous[line][end]
        result += candidate until end
        end = candidate
        line--
    }
    result.reverse()
    return result
}

internal fun legacyWordLineRanges(
    wordWidths: List<Float>,
    gapAfters: List<Float>,
    available: Float,
    maxLines: Int
): List<IntRange> {
    if (wordWidths.isEmpty() || wordWidths.size != gapAfters.size || maxLines <= 0) return emptyList()
    val lines = ArrayList<IntRange>(maxLines)
    var start = 0
    var currentWidth = 0f
    wordWidths.forEachIndexed { index, wordWidth ->
        if (index > start && currentWidth + wordWidth > available && lines.size < maxLines - 1) {
            lines += start until index
            start = index
            currentWidth = 0f
        }
        currentWidth += wordWidth + gapAfters[index]
    }
    lines += start until wordWidths.size
    return lines
}

internal fun legacyAttachedWordLineRanges(
    words: List<AodCanvasWord>,
    wordWidths: List<Float>,
    gapAfters: List<Float>,
    available: Float,
    maxLines: Int
): List<IntRange> {
    if (words.size != wordWidths.size || words.size != gapAfters.size) return emptyList()
    val chunks = attachedWordRanges(words)
    if (chunks.isEmpty()) return emptyList()
    val chunkWidths = chunks.map { range ->
        range.sumOf { index -> (wordWidths[index] + gapAfters[index]).toDouble() }.toFloat() -
            gapAfters[range.last]
    }
    val chunkGaps = chunks.map { range -> gapAfters[range.last] }
    return legacyWordLineRanges(chunkWidths, chunkGaps, available, maxLines).map { line ->
        chunks[line.first].first..chunks[line.last].last
    }
}

internal fun secondaryTokens(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

internal data class SecondaryTimedSegment(
    val text: String,
    val width: Float,
    val gapAfter: Float,
    val startMs: Long,
    val endMs: Long
)

internal fun secondaryTimedLineRanges(
    segments: List<SecondaryTimedSegment>,
    available: Float,
    maxLines: Int
): List<IntRange> = balancedChunkRanges(
    segments.map { it.width + it.gapAfter },
    available,
    maxLines
)

internal fun secondaryTimedVisualRanges(
    segments: List<SecondaryTimedSegment>,
    available: Float,
    maxLines: Int,
    wrap: Boolean
): List<IntRange> = when {
    segments.isEmpty() || maxLines <= 0 -> emptyList()
    !wrap -> listOf(segments.indices)
    else -> secondaryTimedLineRanges(segments, available, maxLines)
}

internal fun timedRomanizedWordIndexes(words: List<AodCanvasWord>): List<Int> =
    words.indices.filter { words[it].romanized.isNotBlank() }

internal fun secondaryTimedProgress(positionMs: Long, startMs: Long, endMs: Long): Float =
    timedWordProgress(positionMs, startMs, endMs)

internal fun timedWordProgress(positionMs: Long, startMs: Long, endMs: Long): Float = when {
    endMs <= startMs -> if (positionMs >= endMs) 1f else 0f
    positionMs <= startMs -> 0f
    positionMs >= endMs -> 1f
    else -> (positionMs - startMs).toFloat() / (endMs - startMs).toFloat()
}

internal data class GradientSweepZone(val start: Float, val end: Float)

internal fun gradientSweepZone(
    progress: Float,
    extent: Float,
    bandFraction: Float = 0.4f
): GradientSweepZone {
    val safeExtent = extent.coerceAtLeast(0f)
    val band = (safeExtent * bandFraction.coerceIn(0.1f, 1f)).coerceAtLeast(1f)
    val start = -band + (safeExtent + band) * progress.coerceIn(0f, 1f)
    return GradientSweepZone(start, start + band)
}

internal fun balancedTokenLineTexts(
    tokens: List<String>,
    tokenWidths: List<Float>,
    spaceWidth: Float,
    available: Float,
    maxLines: Int
): List<String> {
    if (tokens.isEmpty() || tokens.size != tokenWidths.size) return emptyList()
    val effectiveWidths = tokenWidths.map { it + spaceWidth }
    return balancedChunkRanges(effectiveWidths, available + spaceWidth, maxLines)
        .map { range -> range.joinToString(" ") { tokens[it] } }
}

internal fun joinedRomanizedWords(words: List<Pair<String, Boolean>>): String = buildString {
    words.forEachIndexed { index, (text, attached) ->
        if (text.isBlank()) return@forEachIndexed
        if (isNotEmpty() && index > 0 && !attached) append(' ')
        append(text)
    }
}

internal fun baseTextSizeSp(text: String): Float = when {
    text.codePointCount(0, text.length) >= 30 -> 23f
    text.codePointCount(0, text.length) >= 22 -> 24f
    text.codePointCount(0, text.length) >= 14 -> 26f
    else -> 28f
} * LIVE_CARD_SIZE_MULTIPLIER

internal fun textSizeModeMultiplier(mode: String, custom: Int): Float = when (mode) {
    "small" -> 0.9f
    "large" -> 1.2f
    "xlarge" -> 1.5f
    "custom" -> (custom / 100f).coerceIn(0f, 5f)
    else -> 1f
}

internal fun normalizeAodOverflow(mode: String): String =
    if (mode == "Clip") "Clip" else "Wrap"

internal fun rubyReservation(baseTextSizePx: Float, rubyAscent: Float): Float =
    -rubyAscent + baseTextSizePx * 0.12f

internal fun rubySpanGeometry(
    baseX: Float,
    baseWidth: Float,
    rubyWidth: Float
): RubySpanGeometry {
    val spanWidth = max(baseWidth, rubyWidth)
    val spanX = baseX - (spanWidth - baseWidth) / 2f
    return RubySpanGeometry(
        spanX = spanX,
        spanWidth = spanWidth,
        baseX = baseX,
        baseWidth = baseWidth,
        extraWidth = 0f,
        rubyCenterX = baseX + baseWidth / 2f
    )
}

internal fun rubyTopShift(rubyClipTop: Float, paddingTop: Float): Float =
    max(0f, paddingTop - rubyClipTop)

internal fun metadataLayoutBounds(
    anchor: String,
    height: Float,
    paddingTop: Float,
    paddingBottom: Float,
    metadataAscent: Float,
    metadataDescent: Float,
    gap: Float
): MetadataLayoutBounds {
    val metadataBaseline = if (anchor == "bottom") {
        height - paddingBottom - metadataDescent
    } else {
        paddingTop - metadataAscent
    }
    return if (anchor == "bottom") {
        MetadataLayoutBounds(metadataBaseline, paddingTop, metadataBaseline + metadataAscent - gap)
    } else {
        MetadataLayoutBounds(metadataBaseline, metadataBaseline + metadataDescent + gap, height - paddingBottom)
    }
}

internal fun originalLineBaseline(
    rowBaseline: Float,
    lineIndex: Int,
    lineHeight: Float,
    precedingRuby: Float,
    rubyHeight: Float,
    lineGap: Float = 0f
): Float = rowBaseline + lineIndex * lineHeight + precedingRuby + rubyHeight +
    lineIndex * lineGap

internal fun originalRowHeight(
    lineHeight: Float,
    lineCount: Int,
    rubyHeight: Float,
    lineGap: Float = 0f
): Float = lineHeight * lineCount + rubyHeight + (lineCount - 1).coerceAtLeast(0) * lineGap

internal fun safeSecondaryLineHeight(ascent: Float, descent: Float, bottom: Float): Float =
    descent - ascent + max(0f, bottom - descent)

internal fun rubyDrawCenterX(lineStartX: Float, rubyCenterX: Float): Float =
    lineStartX + rubyCenterX

internal fun spotlightBrightness(progress: Float): Float {
    val eased = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
    return 0.42f + 0.58f * eased * eased
}

internal fun spotlightAlpha(progress: Float, state: SpotlightWordState): Float = when (state) {
    SpotlightWordState.SUNG -> steadyTextAlpha(1f)
    SpotlightWordState.UNSUNG -> steadyTextAlpha(0.35f)
    SpotlightWordState.ACTIVE -> max(
        steadyTextAlpha(0.35f),
        steadyTextAlpha(1f) * spotlightBrightness(progress)
    )
}

internal fun normalizeAodMotion(mode: String): String = "Fluid"

internal fun normalizeAodAnimation(mode: String): String = when (mode) {
    "Minimal" -> "Minimal"
    else -> "Gradient"
}

internal data class EffectiveCadenceInputs(
    val attached: Boolean,
    val sceneActive: Boolean,
    val ownVisible: Boolean,
    val windowVisible: Boolean,
    val aggregatedVisible: Boolean,
    val effectiveAlpha: Float,
    val timedOrTransitionActive: Boolean,
    val handoffActive: Boolean = false,
    val verifiedDozeHost: Boolean = false
)

internal fun isEffectiveCadenceActive(inputs: EffectiveCadenceInputs): Boolean =
    isEffectiveCadenceActive(
        attached = inputs.attached,
        sceneActive = inputs.sceneActive,
        ownVisible = inputs.ownVisible,
        windowVisible = inputs.windowVisible,
        aggregatedVisible = inputs.aggregatedVisible,
        effectiveAlpha = inputs.effectiveAlpha,
        timedOrTransitionActive = inputs.timedOrTransitionActive,
        handoffActive = inputs.handoffActive,
        verifiedDozeHost = inputs.verifiedDozeHost
    )

private fun isEffectiveCadenceActive(
    attached: Boolean,
    sceneActive: Boolean,
    ownVisible: Boolean,
    windowVisible: Boolean,
    aggregatedVisible: Boolean,
    effectiveAlpha: Float,
    timedOrTransitionActive: Boolean,
    handoffActive: Boolean,
    verifiedDozeHost: Boolean
): Boolean =
    attached &&
        sceneActive &&
        ownVisible &&
        timedOrTransitionActive &&
        (verifiedDozeHost ||
            windowVisible &&
            aggregatedVisible &&
            (handoffActive || effectiveAlpha > EFFECTIVE_ALPHA_THRESHOLD))

internal enum class CadenceChange { NONE, START, STOP }

internal class EffectiveCadenceGate {
    private var active = false

    fun update(nextActive: Boolean): CadenceChange = when {
        nextActive && !active -> {
            active = true
            CadenceChange.START
        }
        !nextActive && active -> {
            active = false
            CadenceChange.STOP
        }
        else -> CadenceChange.NONE
    }
}

internal fun frameIntervalForTiming(
    contentVisible: Boolean,
    timingActive: Boolean,
    exitTransitionActive: Boolean = false
): Long = if (contentVisible && (timingActive || exitTransitionActive)) 16L else 0L

private const val EFFECTIVE_ALPHA_THRESHOLD = 0.01f
private const val SWEEP_BAND_FRACTION = 0.4f

internal fun isExitTransitionExpired(startedAtMs: Long, nowMs: Long, durationMs: Long): Boolean =
    startedAtMs > 0L && nowMs - startedAtMs >= durationMs

internal fun rubyClipTop(baseBaseline: Float, baseAscent: Float, rubyHeight: Float): Float =
    baseBaseline + baseAscent - rubyHeight

internal fun shouldStartLineTransition(
    lineChanged: Boolean,
    transitionMode: String,
    handoffActive: Boolean,
    resuming: Boolean = false
): Boolean = lineChanged && transitionMode != "None" && !handoffActive && !resuming

internal data class AodCanvasVerticalBounds(val top: Float, val bottom: Float)

internal fun unionAodCanvasVerticalBounds(
    first: AodCanvasVerticalBounds?,
    second: AodCanvasVerticalBounds?
): AodCanvasVerticalBounds? = when {
    first == null -> second
    second == null -> first
    else -> AodCanvasVerticalBounds(
        top = minOf(first.top, second.top),
        bottom = maxOf(first.bottom, second.bottom)
    )
}

internal fun edgeSafeAlignedStart(
    canvasWidth: Float,
    paddingLeft: Float,
    paddingRight: Float,
    visualLeft: Float,
    visualRight: Float,
    alignment: String,
    safetyInset: Float = 0f
): Float {
    val leftEdge = paddingLeft + safetyInset
    val rightEdge = canvasWidth - paddingRight - safetyInset
    return when (alignment) {
        "end" -> rightEdge - visualRight
        "center" -> (leftEdge + rightEdge - visualLeft - visualRight) / 2f
        else -> leftEdge - visualLeft
    }
}

internal fun sharedBlockClipBottom(progress: Float, top: Float, bottom: Float): Float =
    top + (bottom - top).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

internal fun shouldUseSharedLineLevelSweep(
    lineLevelSync: Boolean,
    hasOriginalLines: Boolean,
    animationMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): Boolean = lineLevelSync && hasOriginalLines && animationMode != "Minimal" &&
    lineEndMs > lineStartMs

internal fun hasActiveCanvasTiming(
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>,
    speed: Float = 1f
): Boolean {
    if (speed <= 0f) return false
    if (lineLevelSync && resolvedLineSyncFillMode(true, lineSyncFillMode) == "None") return false
    if (lineEndMs > lineStartMs) return true
    return words.any { it.endMs > it.startMs }
}

internal fun resolvedLineSyncFillMode(lineLevelSync: Boolean, configuredMode: String): String =
    if (!lineLevelSync) configuredMode
    else when (configuredMode) {
        "None" -> "None"
        "Left to right (main only)" -> "Left to right (main only)"
        "Left to right",
        "Left to right (sentence)",
        "Left to right (whole block)" -> "Left to right (whole block)"
        else -> "Top to bottom"
    }

internal enum class AodCanvasVerticalAlignment { TOP, CENTER }

private const val LIVE_CARD_SIZE_MULTIPLIER = 0.68f
private const val AOD_DIMMING_BOOST = 1.6f // Sanctioned AOD dimming delta; preserves hardware contrast.

private fun steadyTextAlpha(factor: Float): Float = if (factor < 0.5f) {
    max(0.35f * AOD_DIMMING_BOOST, 0.55f)
} else {
    minOf(1f, 0.85f * AOD_DIMMING_BOOST)
}

/** Bounded Spicy live-card renderer adapted for Xiaomi AOD. */
internal class AodLyricCanvasView(
    context: Context,
    private val useDozeHandlerCadence: Boolean = false
) : View(context) {
    enum class Alignment { START, CENTER, END }

    private var content = AodCanvasContent(
        trackGeneration = 0L,
        metadata = "",
        original = "",
        romanized = "",
        translated = "",
        alignedRight = false,
        lineLevelSync = false,
        lineStartMs = 0,
        lineEndMs = 0,
        positionMs = 0,
        sampledAtElapsedMs = 0,
        speed = 1f,
        words = emptyList(),
        ruby = emptyList(),
        layoutGroups = emptyList(),
        weight = "Medium",
        textSizeMode = "normal",
        textSizeCustom = 100,
        secondaryMode = "Main only",
        animationMode = "Gradient",
        glowMode = "Off",
        motionMode = "Fluid",
        lineSyncFillMode = "Top to bottom",
        overflowMode = "Wrap",
        transitionMode = "Fade up",
        fontFamily = "noto",
        alignmentMode = "auto",
        metadataVisible = true,
        metadataAnchor = "top",
        adaptiveSectioning = true,
        palette = emptyMap()
    )
    private var resolvedPalette = resolveAodPalette(emptyMap())
    private var alignment = Alignment.START
    private var layout = LayoutState(emptyList(), OriginalLayout(emptyList(), 0f, 0f, false))
    private var exitSnapshot: CanvasSnapshot? = null
    private var transitionStartedAt = 0L
    private var handoffActive = false
    private var suppressNextLineTransition = false
    private var timingEffectEnabled = false
    private var cadenceWindowStartedAt = 0L
    private var cadenceCallbackCount = 0
    private var cadenceDrawCount = 0
    private var cadenceMaxDrawGapMs = 0L
    private var cadenceLastDrawAt = 0L
    private var verticalAlignment = AodCanvasVerticalAlignment.CENTER
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val fontContext = runCatching {
        context.createPackageContext("com.eza.hyperglow", Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()
    private val metadataPaint = paint(14f, 0xB3FFFFFF.toInt(), Typeface.NORMAL)
    private val originalPaint = paint(27f, Color.WHITE, Typeface.NORMAL)
    private val romanizedPaint = paint(17f, Color.WHITE, Typeface.NORMAL)
    private val translatedPaint = paint(17f, Color.WHITE, Typeface.ITALIC)
    private val rubyPaint = paint(11f, 0xB3FFFFFF.toInt(), Typeface.NORMAL).apply {
        textAlign = Paint.Align.CENTER
    }
    private val horizontalSweepShaders = SparseArray<LinearGradient>(4)
    private val verticalSweepShaders = SparseArray<LinearGradient>(4)
    private val sweepMatrix = Matrix()
    private var currentRenderStyle = captureRenderStyle()
    private var contentBoundsChangedListener: (() -> Unit)? = null
    private val typefaceCache = HashMap<TypefaceKey, Typeface>(3)
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    private val frame = object : Runnable {
        override fun run() {
            if (!effectiveCadenceActive()) {
                syncCadence()
                return
            }
            recordDozeCadenceCallback()
            if (exitSnapshot != null && isExitTransitionExpired(
                    transitionStartedAt,
                    SystemClock.elapsedRealtime(),
                    ENTER_TRANSITION_MS
                )
            ) {
                transitionStartedAt = 0L
                exitSnapshot = null
                contentBoundsChangedListener?.invoke()
            }
            invalidate()
            val interval = frameInterval()
            if (interval > 0L) scheduleFrame(this, interval)
            else {
                cadenceGate.update(false)
                removeCallbacks(this)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
    }

    fun setContent(incomingContent: AodCanvasContent) {
        val nextContent = incomingContent.copy(
            animationMode = normalizeAodAnimation(incomingContent.animationMode),
            motionMode = normalizeAodMotion(incomingContent.motionMode),
            overflowMode = normalizeAodOverflow(incomingContent.overflowMode)
        )
        val lineChanged = this.content.original.isNotBlank() &&
            aodCanvasLineIdentity(this.content) != aodCanvasLineIdentity(nextContent)
        val resuming = suppressNextLineTransition
        suppressNextLineTransition = false
        if (shouldStartLineTransition(
                lineChanged,
                nextContent.transitionMode,
                handoffActive,
                resuming
            )
        ) {
            exitSnapshot = CanvasSnapshot(content, layout, currentRenderStyle)
            transitionStartedAt = SystemClock.elapsedRealtime()
        } else if (resuming || nextContent.transitionMode == "None") {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        this.content = nextContent
        timingEffectEnabled = hasActiveCanvasTiming(
            nextContent.lineLevelSync,
            nextContent.lineSyncFillMode,
            nextContent.lineStartMs,
            nextContent.lineEndMs,
            nextContent.words,
            nextContent.speed
        )
        resolvedPalette = resolveAodPalette(nextContent.palette)
        alignment = when (nextContent.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> if (nextContent.alignedRight) Alignment.END else Alignment.START
        }
        val sizeScale = textSizeModeMultiplier(nextContent.textSizeMode, nextContent.textSizeCustom)
        val baseSp = baseTextSizeSp(nextContent.original) * sizeScale
        val typeface = resolveTypeface(nextContent.fontFamily, nextContent.weight)
        originalPaint.typeface = typeface
        if (nextContent.fontFamily != "auto") {
            val regularTypeface = resolveTypeface(nextContent.fontFamily, "Regular")
            metadataPaint.typeface = regularTypeface
            romanizedPaint.typeface = regularTypeface
            translatedPaint.typeface = Typeface.create(regularTypeface, Typeface.ITALIC)
            rubyPaint.typeface = regularTypeface
        } else {
            metadataPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            romanizedPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            translatedPaint.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            rubyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        originalPaint.textSize = baseSp * scaledDensity
        romanizedPaint.textSize = max(14f, kotlin.math.round(baseSp * 0.48f)) * scaledDensity
        translatedPaint.textSize = max(13f, kotlin.math.round(baseSp * 0.48f) - 1f) * scaledDensity
        rubyPaint.textSize = originalPaint.textSize * 0.46f
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        syncCadence()
        invalidate()
    }

    fun stop() {
        cadenceGate.update(false)
        removeCallbacks(frame)
        exitSnapshot = null
        transitionStartedAt = 0L
        suppressNextLineTransition = true
        contentBoundsChangedListener?.invoke()
    }

    fun setContentBoundsChangedListener(listener: (() -> Unit)?) {
        contentBoundsChangedListener = listener
        listener?.invoke()
    }

    fun setVerticalAlignment(alignment: AodCanvasVerticalAlignment) {
        if (verticalAlignment == alignment) return
        verticalAlignment = alignment
        rebuildLayout()
        invalidate()
    }

    fun visibleContentVerticalBounds(): AodCanvasVerticalBounds? =
        unionAodCanvasVerticalBounds(
            verticalBounds(layout),
            exitSnapshot?.layout?.let(::verticalBounds)
        )

    fun setHandoffActive(active: Boolean) {
        handoffActive = active
        if (active) {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        syncCadence()
    }

    fun setSceneActive(active: Boolean) {
        if (sceneActive == active) return
        sceneActive = active
        syncCadence()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        syncCadence()
    }

    override fun onDetachedFromWindow() {
        stop()
        aggregatedVisible = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncCadence()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncCadence()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        syncCadence()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        recordDozeDraw()
        syncCadence()
        drawMetadata(canvas, layout)
        val snapshot = exitSnapshot
        if (snapshot == null) {
            drawRows(canvas, layout, content, 1f, 0f)
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - transitionStartedAt).coerceAtLeast(0L)
        val exitProgress = (elapsed / EXIT_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val enterProgress = (elapsed / ENTER_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        drawRows(
            canvas,
            snapshot.layout,
            snapshot.content,
            1f - exitProgress,
            if (content.transitionMode == "Fade up") -14f * density * exitProgress else 0f,
            snapshot.renderStyle
        )
        drawRows(canvas, layout, content, enterProgress, if (content.transitionMode == "Fade up") 14f * density * (1f - enterProgress) else 0f)
        if (enterProgress >= 1f) {
            transitionStartedAt = 0L
            exitSnapshot = null
            contentBoundsChangedListener?.invoke()
        }
    }

    private fun drawRows(
        canvas: Canvas,
        drawLayout: LayoutState,
        drawContent: AodCanvasContent,
        alpha: Float,
        translateY: Float,
        renderStyle: RenderStyleSnapshot? = null
    ) {
        if (alpha <= 0f || drawLayout.rows.none { it.row.kind != RowKind.METADATA }) return
        val savedContent = content
        val savedLayout = layout
        if (renderStyle != null) applyRenderStyle(renderStyle)
        content = drawContent
        layout = drawLayout
        val layer = if (alpha < 1f || translateY != 0f) {
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * alpha).toInt())
            canvas.translate(0f, translateY)
            save
        } else canvas.save()
        val sharedLineLevelSweep = shouldUseSharedLineLevelSweep(
            drawContent.lineLevelSync,
            drawLayout.original.lines.isNotEmpty(),
            drawContent.animationMode,
            drawContent.lineStartMs,
            drawContent.lineEndMs
        )
        if (sharedLineLevelSweep) {
            drawSharedLineLevelRows(canvas, drawLayout.rows)
        } else {
            var rowIndex = 0
            while (rowIndex < drawLayout.rows.size) {
                val row = drawLayout.rows[rowIndex]
                when (row.row.kind) {
                    RowKind.METADATA -> Unit
                    RowKind.ORIGINAL -> drawOriginal(canvas, row.baseline)
                    else -> drawText(canvas, row.row, row.baseline)
                }
                rowIndex++
            }
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun drawSharedLineLevelRows(canvas: Canvas, rows: List<PositionedRow>) {
        val original = rows.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: return
        val progress = lineProgress()
        when (resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode)) {
            "None" -> {
                clearBlockSweepShaders()
                drawUntimedLines(canvas, original.baseline, bright = true, progress)
                drawSecondaryRowsStatic(canvas, rows, bright = true)
                drawOriginalRubyRows(canvas, original.baseline, bright = true)
            }
            "Left to right (main only)" -> {
                clearBlockSweepShaders()
                drawSecondaryRowsStatic(canvas, rows, bright = true)
                drawUntimedLines(canvas, original.baseline, bright = false, progress)
                drawOriginalRubyRows(canvas, original.baseline, bright = false)
                applyHorizontalSweepShaders(progress, includeSecondary = false)
                drawUntimedLines(canvas, original.baseline, bright = true, progress)
                drawOriginalRubyRows(canvas, original.baseline, bright = true)
                clearBlockSweepShaders()
            }
            "Left to right (whole block)" -> {
                clearBlockSweepShaders()
                drawUntimedLines(canvas, original.baseline, bright = false, progress)
                drawSecondaryRowsStatic(canvas, rows, bright = false)
                drawOriginalRubyRows(canvas, original.baseline, bright = false)
                applyHorizontalSweepShaders(progress, includeSecondary = true)
                drawUntimedLines(canvas, original.baseline, bright = true, progress)
                drawSecondaryRowsStatic(canvas, rows, bright = true)
                drawOriginalRubyRows(canvas, original.baseline, bright = true)
                clearBlockSweepShaders()
            }
            else -> {
                clearBlockSweepShaders()
                drawUntimedLines(canvas, original.baseline, false, progress)
                drawSecondaryRowsStatic(canvas, rows, bright = false)
                drawOriginalRubyRows(canvas, original.baseline, bright = false)
                var top = Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                var index = 0
                while (index < rows.size) {
                    val row = rows[index]
                    if (row.row.kind != RowKind.METADATA) {
                        val rowTop = row.baseline + row.row.paint.fontMetrics.ascent
                        top = minOf(top, rowTop)
                        bottom = maxOf(bottom, rowTop + row.row.height)
                    }
                    index++
                }
                val blockTop = top.coerceAtLeast(paddingTop.toFloat())
                val blockBottom = bottom.coerceAtMost((height - paddingBottom).toFloat())
                applyBlockSweepShaders(
                    origin = blockTop,
                    progress = progress,
                    extent = blockBottom - blockTop
                )
                drawUntimedLines(canvas, original.baseline, true, progress)
                drawSecondaryRowsStatic(canvas, rows, bright = true)
                drawOriginalRubyRows(canvas, original.baseline, bright = true)
                clearBlockSweepShaders()
            }
        }
    }

    private fun drawSecondaryRowsStatic(
        canvas: Canvas,
        rows: List<PositionedRow>,
        bright: Boolean
    ) {
        var rowIndex = 0
        while (rowIndex < rows.size) {
            val positioned = rows[rowIndex]
            if (positioned.row.kind == RowKind.ORIGINAL ||
                positioned.row.kind == RowKind.METADATA
            ) {
                rowIndex++
                continue
            }
            setTextAlpha(
                positioned.row.paint,
                if (bright) 1f else 0.35f,
                1f,
                resolvedPalette.secondaryText
            )
            var lineIndex = 0
            while (lineIndex < positioned.row.lines.size) {
                val line = positioned.row.lines[lineIndex]
                canvas.drawText(
                    line.text,
                    line.startX,
                    positioned.baseline + lineIndex * positioned.row.lineHeight,
                    positioned.row.paint
                )
                lineIndex++
            }
            rowIndex++
        }
    }

    private fun drawOriginalRubyRows(canvas: Canvas, baseline: Float, bright: Boolean) {
        var precedingRuby = 0f
        layout.original.lines.forEachIndexed { lineIndex, line ->
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            if (line.ruby.isNotEmpty()) drawRuby(canvas, line, lineBaseline, bright)
            precedingRuby += line.rubyHeight
        }
    }

    private fun captureRenderStyle(): RenderStyleSnapshot = RenderStyleSnapshot(
        metadataPaint = Paint(metadataPaint),
        originalPaint = Paint(originalPaint),
        romanizedPaint = Paint(romanizedPaint),
        translatedPaint = Paint(translatedPaint),
        rubyPaint = Paint(rubyPaint),
        palette = resolvedPalette,
        alignment = alignment
    )

    private fun applyRenderStyle(style: RenderStyleSnapshot) {
        metadataPaint.set(style.metadataPaint)
        originalPaint.set(style.originalPaint)
        romanizedPaint.set(style.romanizedPaint)
        translatedPaint.set(style.translatedPaint)
        rubyPaint.set(style.rubyPaint)
        resolvedPalette = style.palette
        alignment = style.alignment
    }

    private fun drawMetadata(canvas: Canvas, drawLayout: LayoutState) {
        val metadata = drawLayout.rows.firstOrNull { it.row.kind == RowKind.METADATA } ?: return
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        metadata.row.paint.color = resolvedPalette.metadataText
        metadata.row.paint.alpha = 255
        metadata.row.lines.forEachIndexed { index, line ->
            canvas.drawText(
                line.text,
                line.startX,
                metadata.baseline + index * metadata.row.lineHeight,
                metadata.row.paint
            )
        }
        canvas.restore()
    }

    private fun rebuildLayout() {
        val originalLayout = buildOriginalLayout()
        val rows = ArrayList<Row>(4)
        if (content.metadataVisible && content.metadata.isNotBlank()) {
            rows += row(RowKind.METADATA, content.metadata, metadataPaint, 0f, false)
        }
        if (content.original.isNotBlank()) {
            val metrics = originalPaint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent + 2f * density
            rows += Row(
                RowKind.ORIGINAL,
                content.original,
                originalPaint,
                originalRowHeight(
                    lineHeight,
                    originalLayout.lineCount,
                    originalLayout.rubyHeight,
                    originalLayout.lineGap
                ),
                8f * density,
                emptyList(),
                lineHeight
            )
        }
        val showReading = content.secondaryMode == "Transliteration" || content.secondaryMode == "Both"
        val showTranslation = content.secondaryMode == "Translation" || content.secondaryMode == "Both"
        if (showReading && content.romanized.isNotBlank()) {
            val lines = transliterationLines(originalLayout)
                ?: wrapSecondaryText(content.romanized, romanizedPaint, originalLayout.lineCount)
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, romanizedPaint, 2f * density, lines)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translatedPaint,
                2f * density,
                wrapSecondaryText(content.translated, translatedPaint, originalLayout.lineCount)
            )
        }
        layout = LayoutState(positionRows(rows, originalLayout), originalLayout)
        contentBoundsChangedListener?.invoke()
    }

    private fun verticalBounds(state: LayoutState): AodCanvasVerticalBounds? {
        if (state.rows.isEmpty()) return null
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        state.rows.forEach { positioned ->
            val rowTop = positioned.baseline + positioned.row.paint.fontMetrics.ascent
            top = minOf(top, rowTop)
            bottom = maxOf(bottom, rowTop + positioned.row.height)
        }
        if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return null
        return AodCanvasVerticalBounds(
            top.coerceIn(0f, height.toFloat()),
            bottom.coerceIn(0f, height.toFloat())
        )
    }

    private fun row(kind: RowKind, text: String, paint: Paint, gap: Float, allowWrap: Boolean = true): Row {
        val lines = if (allowWrap) {
            wrapSecondaryText(text, paint, MAX_SECONDARY_LINES)
        } else {
            listOf(textLine(text, paint.measureText(text), paint, alignmentFor(kind)))
        }
        return rowWithLines(kind, text, paint, gap, lines)
    }

    private fun rowWithLines(
        kind: RowKind,
        text: String,
        paint: Paint,
        gap: Float,
        lines: List<TextLine>
    ): Row {
        val metrics = paint.fontMetrics
        val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
        return Row(kind, text, paint, lineHeight * lines.size, gap, lines, lineHeight)
    }

    private fun positionRows(rows: List<Row>, originalLayout: OriginalLayout): List<PositionedRow> {
        val positioned = ArrayList<PositionedRow>(rows.size)
        val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
        if (metadata != null) {
            val anchor = when (content.metadataAnchor) {
                "bottom" -> "bottom"
                else -> "top"
            }
            val metadataBounds = metadataLayoutBounds(
                anchor,
                height.toFloat(),
                paddingTop.toFloat(),
                paddingBottom.toFloat(),
                metadata.paint.fontMetrics.ascent,
                metadata.paint.fontMetrics.descent,
                10f * density
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            val gap = 10f * density
            if (anchor == "bottom") {
                var bottom = metadataBounds.lyricEnd
                lyricRows.asReversed().forEach { row ->
                    bottom -= row.height
                    positioned += PositionedRow(row, bottom - row.paint.fontMetrics.ascent, true)
                    bottom -= row.gapBefore
                }
                positioned.sortBy { it.baseline }
            } else {
                var top = metadataBounds.lyricStart
                lyricRows.forEach { row ->
                    top += row.gapBefore
                    positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                    top += row.height
                }
            }
        } else {
            val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
            val topPadding = paddingTop.toFloat()
            val bottomPadding = height - paddingBottom
            val available = (bottomPadding - topPadding).coerceAtLeast(0f)
            var top = if (verticalAlignment == AodCanvasVerticalAlignment.TOP) {
                topPadding
            } else {
                topPadding + max(0f, (available - total) / 2f)
            }
            rows.forEach { row ->
                top += row.gapBefore
                positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                top += row.height
            }
        }
        val original = positioned.firstOrNull { it.row.kind == RowKind.ORIGINAL }
        val firstLine = originalLayout.lines.firstOrNull()
        if (original == null || firstLine == null || firstLine.rubyHeight <= 0f) return positioned
        val firstBaseBaseline = original.baseline + firstLine.rubyHeight
        val top = rubyClipTop(firstBaseBaseline, originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
        val shift = rubyTopShift(top, paddingTop.toFloat())
        return if (shift == 0f) positioned else positioned.map {
            if (it.row.kind == RowKind.METADATA) it else it.copy(baseline = it.baseline + shift)
        }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        if (!originalLayout.timed) {
            val progress = if (content.animationMode == "Minimal") 1f else lineProgress()
            if (resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode) ==
                "Top to bottom"
            ) {
                drawUntimedTopToBottom(canvas, baseline, progress)
            } else {
                var precedingRuby = 0f
                var lineIndex = 0
                while (lineIndex < lines.size) {
                    val line = lines[lineIndex]
                    val lineBaseline = originalLineBaseline(
                        baseline,
                        lineIndex,
                        originalLayout.lineHeight,
                        precedingRuby,
                        line.rubyHeight,
                        originalLayout.lineGap
                    )
                    val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
                    if (line.ruby.isNotEmpty()) {
                        drawRuby(canvas, line, lineBaseline)
                    }
                    drawLineFill(
                        canvas,
                        line,
                        lineBaseline,
                        originalLayout.continuousFill(progress, lineIndex),
                        false
                    )
                    if (clipSave != -1) canvas.restoreToCount(clipSave)
                    precedingRuby += line.rubyHeight
                    lineIndex++
                }
            }
            return
        }
        val position = projectedPosition()
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            val lineClipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            if (line.ruby.isNotEmpty()) {
                drawRuby(canvas, line, lineBaseline)
            }
            var x = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val width = placed.width
                val wordX = line.startX + x
                val progress = timedWordProgress(position, word.startMs, word.endMs)
                val active = position >= word.startMs && position < word.endMs
                val sung = position >= word.endMs
                val animated = content.animationMode != "Minimal"
                val scale = if (animated && active) scaleSpline(progress) else if (animated && !sung) 0.95f else 1f
                val y = if (animated && active) yOffsetSpline(progress) * originalPaint.textSize
                else if (animated && !sung) 0.01f * originalPaint.textSize else 0f
                val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off" && active) {
                    0.55f * glowSpline(progress)
                } else 0f
                canvas.save()
                val wordBaseline = lineBaseline
                if (animated) canvas.scale(scale, scale, wordX + width / 2f, wordBaseline)
                applyGlow(originalPaint, glow)
                originalPaint.shader = null
                setTextAlpha(
                    originalPaint,
                    if (content.animationMode == "Minimal" || sung) 1f else 0.35f,
                    1f,
                    if (sung) resolvedPalette.sungText else resolvedPalette.unsungText
                )
                canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                if (active && content.animationMode == "Gradient") {
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    applySoftSweep(
                        originalPaint,
                        resolvedPalette.sungText,
                        origin = wordX,
                        progress = progress,
                        extent = width,
                        vertical = false
                    )
                    canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                    originalPaint.shader = null
                }
                originalPaint.clearShadowLayer()
                canvas.restore()
                x += width + placed.gapAfter
                wordIndex++
            }
            if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawUntimedTopToBottom(canvas: Canvas, baseline: Float, progress: Float) {
        val lines = layout.original.lines
        val firstLine = lines.firstOrNull()
        val firstLineBaseline = firstLine?.let {
            originalLineBaseline(
                baseline,
                0,
                layout.original.lineHeight,
                0f,
                it.rubyHeight,
                layout.original.lineGap
            )
        } ?: baseline
        val blockTop = max(
            paddingTop.toFloat(),
            rubyClipTop(firstLineBaseline, originalPaint.fontMetrics.ascent, firstLine?.rubyHeight ?: 0f)
        )
        val blockHeight = originalRowHeight(
            layout.original.lineHeight,
            lines.size,
            layout.original.rubyHeight,
            layout.original.lineGap
        )
        clearBlockSweepShaders()
        drawUntimedLines(canvas, baseline, false, progress)
        drawOriginalRubyRows(canvas, baseline, bright = false)
        applyBlockSweepShaders(
            origin = blockTop,
            progress = progress,
            extent = blockHeight
        )
        drawUntimedLines(canvas, baseline, true, progress)
        drawOriginalRubyRows(canvas, baseline, bright = true)
        clearBlockSweepShaders()
    }

    private fun drawUntimedLines(canvas: Canvas, baseline: Float, bright: Boolean, progress: Float) {
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < layout.original.lines.size) {
            val line = layout.original.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off" && !bright) {
                0.55f * glowSpline(progress)
            } else 0f
            applyGlow(originalPaint, glow)
            setTextAlpha(
                originalPaint,
                if (bright) 1f else 0.35f,
                1f,
                if (bright) resolvedPalette.sungText else resolvedPalette.unsungText
            )
            drawOriginalText(canvas, line, lineBaseline)
            originalPaint.clearShadowLayer()
            if (clipSave != -1) canvas.restoreToCount(clipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawRuby(
        canvas: Canvas,
        line: OriginalLine,
        baseBaseline: Float,
        bright: Boolean = true
    ) {
        rubyPaint.color = resolvedPalette.secondaryText
        rubyPaint.alpha = (255f * steadyTextAlpha(if (bright) 1f else 0.35f)).toInt()
        val gap = line.rubyHeight + rubyPaint.fontMetrics.ascent
        val baseline = baseBaseline + originalPaint.fontMetrics.ascent -
            gap - rubyPaint.fontMetrics.descent
        var index = 0
        while (index < line.ruby.size) {
            val placement = line.ruby[index]
            canvas.drawText(
                placement.reading,
                rubyDrawCenterX(line.startX, placement.rubyCenterX),
                baseline,
                rubyPaint
            )
            index++
        }
    }

    private fun clipOriginalLine(canvas: Canvas, baseBaseline: Float, rubyHeight: Float): Int {
        if (content.overflowMode == "Wrap") return -1
        val save = canvas.save()
        canvas.clipRect(
            paddingLeft.toFloat(),
            max(paddingTop.toFloat(), rubyClipTop(baseBaseline, originalPaint.fontMetrics.ascent, rubyHeight)),
            width - paddingRight.toFloat(),
            (height - paddingBottom).toFloat()
        )
        return save
    }

    private fun frameInterval(): Long = frameIntervalForTiming(
        effectiveCadenceActive(),
        timingActive = true
    )

    private fun effectiveCadenceActive(): Boolean = isEffectiveCadenceActive(
        attached = isAttachedToWindow,
        sceneActive = sceneActive,
        ownVisible = visibility == VISIBLE,
        windowVisible = windowVisibility == VISIBLE,
        aggregatedVisible = aggregatedVisible && isShown,
        effectiveAlpha = effectiveAlpha(),
        timedOrTransitionActive = timingEffectActive() || exitSnapshot != null,
        handoffActive = handoffActive,
        verifiedDozeHost = useDozeHandlerCadence
    )

    private fun timingEffectActive(): Boolean = timingEffectEnabled

    private fun effectiveAlpha(): Float {
        var value = alpha * transitionAlpha
        var ancestor = parent as? View
        while (ancestor != null) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value <= EFFECTIVE_ALPHA_THRESHOLD) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun syncCadence() {
        when (cadenceGate.update(effectiveCadenceActive())) {
            CadenceChange.START -> {
                removeCallbacks(frame)
                scheduleFrame(frame, 0L)
            }
            CadenceChange.STOP -> removeCallbacks(frame)
            CadenceChange.NONE -> Unit
        }
    }

    private fun scheduleFrame(action: Runnable, delayMs: Long) {
        if (useDozeHandlerCadence) postDelayed(action, delayMs)
        else postOnAnimation(action)
    }

    private fun recordDozeCadenceCallback() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceWindowStartedAt == 0L) cadenceWindowStartedAt = now
        cadenceCallbackCount++
        if (now - cadenceWindowStartedAt < CADENCE_DIAGNOSTIC_WINDOW_MS) return
        HookLogger.i(
            CADENCE_DIAGNOSTIC_TAG,
            "callbacks=$cadenceCallbackCount draws=$cadenceDrawCount " +
                "maxDrawGapMs=$cadenceMaxDrawGapMs"
        )
        cadenceWindowStartedAt = now
        cadenceCallbackCount = 0
        cadenceDrawCount = 0
        cadenceMaxDrawGapMs = 0L
    }

    private fun recordDozeDraw() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceLastDrawAt > 0L) {
            cadenceMaxDrawGapMs = maxOf(cadenceMaxDrawGapMs, now - cadenceLastDrawAt)
        }
        cadenceLastDrawAt = now
        cadenceDrawCount++
    }

    private fun buildOriginalLayout(): OriginalLayout {
        val words = coalesceRubyWords(
            content.original,
            content.words.filter { it.text.isNotBlank() },
            content.ruby
        )
        val lines = if (words.isEmpty()) {
            if (content.adaptiveSectioning) layoutTextByGroups()
            else wrapText(content.original, originalPaint)
        } else {
            layoutWordLines(words, 8f * density)
        }
        val metrics = originalPaint.fontMetrics
        return OriginalLayout(
            assignRuby(lines),
            metrics.descent - metrics.ascent + 2f * density,
            ORIGINAL_LINE_GAP_DP * density,
            words.isNotEmpty()
        )
    }

    private fun layoutTextByGroups(): List<OriginalLine> {
        val ranges = coveredLayoutRanges(content.original, content.layoutGroups)
        if (ranges.isEmpty()) return wrapText(content.original, originalPaint)
        val synthetic = ranges.mapIndexed { index, range ->
            val previousEnd = ranges.getOrNull(index - 1)?.last?.plus(1) ?: range.first
            val attached = index > 0 && content.original.substring(previousEnd, range.first)
                .none { it.isWhitespace() }
            AodCanvasWord(
                content.original.substring(range.first, range.last + 1),
                "",
                0L,
                0L,
                attached,
                range.first,
                range.last + 1
            )
        }
        return layoutWordLines(synthetic, 8f * density)
    }

    private fun layoutWordLines(words: List<AodCanvasWord>, gap: Float): List<OriginalLine> {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val offsets = wordOffsets(words)
        val placed = words.mapIndexed { index, word ->
            val wordWidth = originalPaint.measureText(word.text)
            val gapAfter = if (index == words.lastIndex) {
                0f
            } else {
                authoredWordSeparator(content.original, word, words[index + 1])
                    ?.let(originalPaint::measureText)
                    ?: aodWordGapAfter(word.partOfWord, gap)
            }
            PlacedWord(word, wordWidth, gapAfter, offsets[index])
        }
        if (content.overflowMode != "Wrap") {
            return listOf(wordLine(placed))
        }
        if (!content.adaptiveSectioning) {
            return legacyAttachedWordLineRanges(
                words,
                placed.map(PlacedWord::width),
                placed.map(PlacedWord::gapAfter),
                available,
                MAX_ORIGINAL_LINES
            ).map { range ->
                val lineWords = range.map(placed::get)
                wordLine(lineWords)
            }
        }
        val groupIds = lexicalGroupIds(offsets, content.layoutGroups)
        val chunks = ArrayList<List<PlacedWord>>()
        var index = 0
        while (index < placed.size) {
            val groupId = groupIds[index]
            var end = index + 1
            if (groupId != null) while (end < placed.size && groupIds[end] == groupId) end++
            val chunk = placed.subList(index, end)
            val chunkWidth = chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
            if (chunkWidth > available && chunk.size > 1) chunk.forEach { chunks += listOf(it) }
            else chunks += chunk.toList()
            index = end
        }
        val chunkWidths = chunks.map { chunk ->
            chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        }
        val lines = balancedChunkRanges(chunkWidths, available, MAX_ORIGINAL_LINES).map { range ->
            val lineWords = range.flatMap { chunks[it] }
            wordLine(lineWords)
        }
        return lines.ifEmpty { listOf(originalLine("", 0f, null, null)) }
    }

    private fun wordLine(words: List<PlacedWord>): OriginalLine {
        val mapped = words.mapNotNull { word -> word.offset?.let { it.first to it.last + 1 } }
        val offsets = mapped.takeIf { it.size == words.size }
        val start = offsets?.minOf { it.first }
        val end = offsets?.maxOf { it.second }
        val text = if (start != null && end != null && start >= 0 && end <= content.original.length) {
            content.original.substring(start, end)
        } else {
            buildString {
                words.forEachIndexed { index, placed ->
                    append(placed.word.text)
                    if (index < words.lastIndex && placed.gapAfter > 0f) append(' ')
                }
            }
        }
        val width = words.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat() -
            (words.lastOrNull()?.gapAfter ?: 0f)
        return originalLine(
            text,
            width,
            start,
            end
        )
            .copy(words = words)
    }

    private fun wrapText(text: String, paint: Paint): List<OriginalLine> {
        if (text.isBlank()) return emptyList()
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        if (content.overflowMode != "Wrap") {
            return listOf(originalLine(text, paint.measureText(text), 0, text.length))
        }
        val lines = ArrayList<OriginalLine>(MAX_ORIGINAL_LINES)
        var remaining = text
        var charStart = 0
        while (remaining.isNotEmpty() && lines.size < MAX_ORIGINAL_LINES) {
            val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
            val line = remaining.take(count)
            lines += originalLine(line, paint.measureText(line), charStart, charStart + line.length)
            remaining = remaining.drop(count)
            charStart += count
        }
        return lines
    }

    private fun transliterationLines(originalLayout: OriginalLayout): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val sourceWords = originalLayout.lines.flatMap { it.words }.map { it.word }
        if (sourceWords.isEmpty()) return null
        val spaceWidth = romanizedPaint.measureText(" ")
        val timedIndexes = timedRomanizedWordIndexes(sourceWords)
        val segments = timedIndexes.mapIndexed { renderedIndex, sourceIndex ->
            val word = sourceWords[sourceIndex]
            val text = word.romanized.trim()
            val nextSourceIndex = timedIndexes.getOrNull(renderedIndex + 1)
            SecondaryTimedSegment(
                text = text,
                width = romanizedPaint.measureText(text),
                gapAfter = if (nextSourceIndex != null && sourceWords[nextSourceIndex].partOfWord) {
                    0f
                } else {
                    spaceWidth
                },
                startMs = word.startMs,
                endMs = word.endMs
            )
        }
        if (segments.isEmpty()) return null
        return secondaryTimedVisualRanges(
            segments,
            available,
            MAX_SECONDARY_LINES,
            wrap = content.adaptiveSectioning && content.overflowMode == "Wrap"
        ).map { range ->
            val lineSegments = range.map(segments::get).mapIndexed { index, segment ->
                if (index == range.count() - 1) segment.copy(gapAfter = 0f) else segment
            }
            val text = buildString {
                lineSegments.forEach { segment ->
                    append(segment.text)
                    if (segment.gapAfter > 0f) append(' ')
                }
            }
            val lineWidth = lineSegments.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
            textLine(text, lineWidth, romanizedPaint).copy(timedSegments = lineSegments)
        }
    }

    private fun wrapSecondaryText(text: String, paint: Paint, preferredLines: Int): List<TextLine> {
        if (!content.adaptiveSectioning || content.overflowMode != "Wrap") {
            return listOf(textLine(text, paint.measureText(text), paint))
        }
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val tokens = secondaryTokens(text).flatMap { token ->
            if (paint.measureText(token) <= available) {
                listOf(token)
            } else {
                val pieces = ArrayList<String>()
                var remaining = token
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
                    pieces += remaining.take(count)
                    remaining = remaining.drop(count)
                }
                pieces
            }
        }
        if (tokens.isEmpty()) return emptyList()
        val maxLines = if (paint.measureText(text) > available) {
            maxOf(preferredLines, MAX_SECONDARY_LINES)
        } else {
            preferredLines
        }.coerceIn(1, MAX_SECONDARY_LINES)
        return balancedTokenLineTexts(
            tokens,
            tokens.map(paint::measureText),
            paint.measureText(" "),
            available,
            maxLines
        ).map { line -> textLine(line, paint.measureText(line), paint) }
    }

    private fun textLine(
        text: String,
        width: Float,
        paint: Paint,
        lineAlignment: Alignment = alignment
    ): TextLine {
        val visual = visualExtents(text, paint, width)
        return TextLine(text, width, alignedStart(width, lineAlignment, visual.first, visual.second))
    }

    private fun originalLine(text: String, width: Float, charStart: Int?, charEnd: Int?): OriginalLine {
        val visual = visualExtents(text, originalPaint, width)
        return OriginalLine(
            text,
            emptyList(),
            width,
            alignedStart(width, alignment, visual.first, visual.second),
            charStart,
            charEnd
        )
    }

    private fun wordOffsets(words: List<AodCanvasWord>): List<IntRange?> =
        words.map { transportedWordOffset(content.original, it) }

    private fun assignRuby(lines: List<OriginalLine>): List<OriginalLine> = lines.map { line ->
        val lineStart = line.charStart
        val lineEnd = line.charEnd
        if (lineStart == null || lineEnd == null) return@map line

        val placements = content.ruby.asSequence()
            .filter { segment ->
                segment.start >= 0 && segment.end > segment.start &&
                    segment.end <= content.original.length &&
                    segment.start < lineEnd && segment.end > lineStart
            }
            .sortedBy { it.start }
            .mapNotNull { segment ->
                val baseStart = maxOf(segment.start, lineStart)
                val baseEnd = minOf(segment.end, lineEnd)
                val baseRun = measureBaseRun(line, baseStart, baseEnd) ?: return@mapNotNull null
                val geometry = rubySpanGeometry(
                    baseRun.x,
                    baseRun.width,
                    rubyPaint.measureText(segment.reading)
                )
                RubyPlacement(
                    baseStart = baseStart,
                    baseEnd = baseEnd,
                    baseX = geometry.baseX,
                    baseWidth = geometry.baseWidth,
                    spanX = geometry.spanX,
                    spanWidth = geometry.spanWidth,
                    extraWidth = 0f,
                    baseOffset = 0f,
                    rubyCenterX = geometry.rubyCenterX,
                    reading = segment.reading
                )
            }
            .toList()
        val rubyHeight = if (placements.isEmpty()) 0f else {
            rubyReservation(originalPaint.textSize, rubyPaint.fontMetrics.ascent)
        }
        val baseVisual = visualExtents(line.text, originalPaint, line.width)
        val visualLeft = minOf(
            baseVisual.first,
            placements.minOfOrNull { it.spanX } ?: baseVisual.first
        )
        val visualRight = maxOf(
            baseVisual.second,
            placements.maxOfOrNull { it.spanX + it.spanWidth } ?: baseVisual.second
        )
        line.copy(
            startX = alignedStart(line.width, alignment, visualLeft, visualRight),
            ruby = placements,
            rubyHeight = rubyHeight,
            textRuns = originalTextRuns(
                line.text.length,
                placements.map { placement ->
                    OriginalTextRun(
                        (placement.baseStart - lineStart).coerceIn(0, line.text.length),
                        (placement.baseEnd - lineStart).coerceIn(0, line.text.length),
                        placement.baseX
                    )
                }
            ) { end -> originalPaint.measureText(line.text, 0, end) }
        )
    }

    private fun measureBaseRun(line: OriginalLine, start: Int, end: Int): BaseRun? {
        if (start >= end) return null
        val lineStart = line.charStart ?: return null
        if (line.words.isEmpty()) {
            val localStart = (start - lineStart).coerceIn(0, line.text.length)
            val localEnd = (end - lineStart).coerceIn(localStart, line.text.length)
            val prefixWidth = originalPaint.measureText(line.text, 0, localStart)
            return BaseRun(
                prefixWidth,
                originalPaint.measureText(line.text, localStart, localEnd)
            )
        }

        var x = 0f
        var firstX: Float? = null
        var lastX = 0f
        line.words.forEach { placed ->
            val offset = placed.offset
            if (offset != null) {
                val wordStart = offset.first
                val wordEnd = offset.last + 1
                val overlapStart = maxOf(start, wordStart)
                val overlapEnd = minOf(end, wordEnd)
                if (overlapStart < overlapEnd) {
                    val localStart = overlapStart - wordStart
                    val localEnd = overlapEnd - wordStart
                    val runStart = x + originalPaint.measureText(placed.word.text, 0, localStart)
                    val runEnd = x + originalPaint.measureText(placed.word.text, 0, localEnd)
                    if (firstX == null) firstX = runStart
                    lastX = runEnd
                }
            }
            x += placed.width + placed.gapAfter
        }
        val baseX = firstX ?: return null
        return BaseRun(baseX, (lastX - baseX).coerceAtLeast(0f))
    }

    private fun drawLineFill(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        progress: Float,
        clipToPaddedWidth: Boolean
    ) {
        val x = line.startX
        val clipSave = if (clipToPaddedWidth) canvas.save() else -1
        if (clipToPaddedWidth) canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off") {
            0.55f * glowSpline(progress)
        } else 0f
        applyGlow(originalPaint, glow)
        originalPaint.shader = null
        setTextAlpha(originalPaint, 0.35f, 1f, resolvedPalette.unsungText)
        drawOriginalText(canvas, line, baseline)
        setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
        applySoftSweep(
            originalPaint,
            resolvedPalette.sungText,
            origin = x,
            progress = progress,
            extent = line.width,
            vertical = false
        )
        drawOriginalText(canvas, line, baseline)
        originalPaint.shader = null
        originalPaint.clearShadowLayer()
        if (clipToPaddedWidth) canvas.restoreToCount(clipSave)
    }

    private fun drawOriginalText(canvas: Canvas, line: OriginalLine, baseline: Float) {
        if (line.ruby.isEmpty()) {
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        if (line.textRuns.isEmpty()) {
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        var index = 0
        while (index < line.textRuns.size) {
            val run = line.textRuns[index]
            canvas.drawText(
                line.text,
                run.start,
                run.end,
                line.startX + run.x,
                baseline,
                originalPaint
            )
            index++
        }
    }

    private fun drawText(canvas: Canvas, row: Row, baseline: Float) {
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        var lineIndex = 0
        while (lineIndex < row.lines.size) {
            val line = row.lines[lineIndex]
            val lineBaseline = baseline + lineIndex * row.lineHeight
            if (row.kind == RowKind.METADATA) {
                row.paint.color = resolvedPalette.metadataText
                row.paint.alpha = 255
                canvas.drawText(line.text, line.startX, lineBaseline, row.paint)
            } else if (row.kind == RowKind.ROMANIZED) {
                val fullySung = content.animationMode == "Minimal"
                if (!fullySung && line.timedSegments.isNotEmpty()) {
                    drawTimedSecondaryLine(canvas, row.paint, line, lineBaseline)
                } else {
                    drawSecondaryLine(
                        canvas,
                        row.paint,
                        line.text,
                        line.startX,
                        lineBaseline,
                        line.width,
                        lineProgress(),
                        fullySung
                    )
                }
            } else {
                drawSecondaryLine(canvas, row.paint, line.text, line.startX, lineBaseline, line.width, 1f, true)
            }
            lineIndex++
        }
        canvas.restore()
    }

    private fun alignmentFor(kind: RowKind): Alignment = if (kind == RowKind.METADATA) {
        when (content.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> Alignment.START
        }
    } else {
        alignment
    }

    private fun alignedStart(
        textWidth: Float,
        lineAlignment: Alignment = alignment,
        visualLeft: Float = 0f,
        visualRight: Float = textWidth
    ): Float = edgeSafeAlignedStart(
        canvasWidth = width.toFloat(),
        paddingLeft = paddingLeft.toFloat(),
        paddingRight = paddingRight.toFloat(),
        visualLeft = visualLeft,
        visualRight = visualRight,
        alignment = when (lineAlignment) {
            Alignment.START -> "start"
            Alignment.CENTER -> "center"
            Alignment.END -> "end"
        },
        safetyInset = if (lineAlignment == Alignment.END) END_EDGE_SAFETY_DP * density else 0f
    )

    private fun visualExtents(text: String, paint: Paint, advanceWidth: Float): Pair<Float, Float> {
        if (text.isEmpty()) return 0f to advanceWidth
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return minOf(0f, bounds.left.toFloat()) to maxOf(advanceWidth, bounds.right.toFloat())
    }

    private fun projectedPosition(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - content.sampledAtElapsedMs).coerceAtLeast(0L)
        return content.positionMs + (elapsed * content.speed).toLong()
    }

    private fun lineProgress(): Float = progress(projectedPosition(), content.lineStartMs, content.lineEndMs)

    private fun progress(position: Long, start: Long, end: Long): Float =
        if (end <= start) if (position >= end) 1f else 0f
        else ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun scaleSpline(t: Float): Float = if (t <= 0.7f) lerp(0.95f, 1.0505f, t / 0.7f)
    else lerp(1.0505f, 1f, (t - 0.7f) / 0.3f)

    private fun yOffsetSpline(t: Float): Float = if (t <= 0.9f) lerp(0.01f, -(1f / 60f), t / 0.9f)
    else lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f)

    private fun glowSpline(t: Float): Float = when {
        t <= 0.15f -> lerp(0f, 1f, t / 0.15f)
        t <= 0.6f -> 1f
        else -> lerp(1f, 0f, (t - 0.6f) / 0.4f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun setTextAlpha(
        paint: Paint,
        factor: Float,
        brightness: Float,
        color: Int = resolvedPalette.primaryText
    ) {
        paint.color = color
        paint.alpha = (255f * (steadyTextAlpha(factor) * brightness).coerceIn(0f, 1f)).toInt()
    }

    private fun applySoftSweep(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float,
        vertical: Boolean
    ) {
        val shaders = if (vertical) verticalSweepShaders else horizontalSweepShaders
        var shader = shaders[color]
        if (shader == null) {
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            val middle = Color.argb(184, Color.red(color), Color.green(color), Color.blue(color))
            shader = if (vertical) {
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    1f,
                    intArrayOf(color, middle, transparent),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f,
                    0f,
                    1f,
                    0f,
                    intArrayOf(color, middle, transparent),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            shaders.put(color, shader)
        }
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        sweepMatrix.setScale(if (vertical) 1f else band, if (vertical) band else 1f)
        sweepMatrix.postTranslate(if (vertical) 0f else start, if (vertical) start else 0f)
        shader.setLocalMatrix(sweepMatrix)
        paint.shader = shader
    }

    private fun applyBlockSweepShaders(origin: Float, progress: Float, extent: Float) {
        applySoftSweep(originalPaint, resolvedPalette.sungText, origin, progress, extent, vertical = true)
        applySoftSweep(romanizedPaint, resolvedPalette.secondaryText, origin, progress, extent, vertical = true)
        applySoftSweep(translatedPaint, resolvedPalette.secondaryText, origin, progress, extent, vertical = true)
        applySoftSweep(rubyPaint, resolvedPalette.secondaryText, origin, progress, extent, vertical = true)
    }

    private fun applyHorizontalSweepShaders(progress: Float, includeSecondary: Boolean) {
        val origin = paddingLeft.toFloat()
        val extent = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        applySoftSweep(
            originalPaint,
            resolvedPalette.sungText,
            origin,
            progress,
            extent,
            vertical = false
        )
        applySoftSweep(
            rubyPaint,
            resolvedPalette.secondaryText,
            origin,
            progress,
            extent,
            vertical = false
        )
        if (!includeSecondary) return
        applySoftSweep(
            romanizedPaint,
            resolvedPalette.secondaryText,
            origin,
            progress,
            extent,
            vertical = false
        )
        applySoftSweep(
            translatedPaint,
            resolvedPalette.secondaryText,
            origin,
            progress,
            extent,
            vertical = false
        )
    }

    private fun clearBlockSweepShaders() {
        originalPaint.shader = null
        romanizedPaint.shader = null
        translatedPaint.shader = null
        rubyPaint.shader = null
    }

    private fun applyGlow(paint: Paint, glow: Float) {
        if (glow > 0.02f) {
            val alpha = (255f * glow.coerceIn(0f, 1f)).toInt()
            paint.setShadowLayer(
                7f * glow,
                0f,
                0f,
                Color.argb(
                    alpha,
                    Color.red(resolvedPalette.glow),
                    Color.green(resolvedPalette.glow),
                    Color.blue(resolvedPalette.glow)
                )
            )
        }
    }

    private fun drawSecondaryLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float,
        width: Float,
        fillProgress: Float,
        fullySung: Boolean
    ) {
        setTextAlpha(
            paint,
            if (fullySung) 1f else 0.35f,
            1f,
            resolvedPalette.secondaryText
        )
        paint.shader = null
        canvas.drawText(text, x, baseline, paint)
        if (!fullySung) {
            setTextAlpha(paint, 1f, 1f, resolvedPalette.secondaryText)
            applySoftSweep(
                paint,
                resolvedPalette.secondaryText,
                origin = x,
                progress = fillProgress,
                extent = width,
                vertical = false
            )
            canvas.drawText(text, x, baseline, paint)
            paint.shader = null
        }
    }

    private fun drawTimedSecondaryLine(
        canvas: Canvas,
        paint: Paint,
        line: TextLine,
        baseline: Float
    ) {
        val position = projectedPosition()
        var x = line.startX
        line.timedSegments.forEach { segment ->
            val fill = secondaryTimedProgress(position, segment.startMs, segment.endMs)
            paint.shader = null
            setTextAlpha(paint, 0.35f, 1f, resolvedPalette.secondaryText)
            canvas.drawText(segment.text, x, baseline, paint)
            if (fill > 0f) {
                setTextAlpha(paint, 1f, 1f, resolvedPalette.secondaryText)
                applySoftSweep(
                    paint,
                    resolvedPalette.secondaryText,
                    origin = x,
                    progress = fill,
                    extent = segment.width,
                    vertical = false
                )
                canvas.drawText(segment.text, x, baseline, paint)
                paint.shader = null
            }
            x += segment.width + segment.gapAfter
        }
    }

    private fun paint(sizeSp: Float, color: Int, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * scaledDensity
        setColor(color)
        typeface = Typeface.create("sans-serif", weight)
        isSubpixelText = true
    }

    private fun resolveTypeface(family: String, weight: String): Typeface {
        val key = TypefaceKey(family, weight)
        typefaceCache[key]?.let { return it }
        val asset = if (family == "noto") {
            "fonts/NotoSans-" + when (weight) {
                "Bold" -> "Bold"
                "Medium" -> "Medium"
                else -> "Regular"
            } + ".ttf"
        } else if (family == "apple") {
            if (weight == "Regular") "fonts/lyrics_medium.ttf" else "fonts/sf-pro-display-bold.ttf"
        } else if (weight == "Bold") {
            "fonts/sf-pro-display-bold.ttf"
        } else {
            "fonts/spotifymix-medium.ttf"
        }
        val typeface = runCatching {
            Typeface.createFromAsset(fontContext?.assets ?: context.assets, asset)
        }.getOrElse {
            val fallback = if (family == "apple") "sans-serif" else "sans-serif-medium"
            Typeface.create(fallback, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
        }
        typefaceCache[key] = typeface
        return typeface
    }

    private enum class RowKind { METADATA, ORIGINAL, ROMANIZED, TRANSLATED }
    private data class Row(
        val kind: RowKind,
        val text: String,
        val paint: Paint,
        val height: Float,
        val gapBefore: Float,
        val lines: List<TextLine>,
        val lineHeight: Float
    )
    private data class PlacedWord(
        val word: AodCanvasWord,
        val width: Float,
        val gapAfter: Float,
        val offset: IntRange?
    )
    private data class OriginalLine(
        val text: String,
        val words: List<PlacedWord>,
        val width: Float,
        val startX: Float,
        val charStart: Int?,
        val charEnd: Int?,
        val ruby: List<RubyPlacement> = emptyList(),
        val rubyHeight: Float = 0f,
        val textRuns: List<OriginalTextRun> = emptyList()
    )
    private data class TextLine(
        val text: String,
        val width: Float,
        val startX: Float,
        val timedSegments: List<SecondaryTimedSegment> = emptyList()
    )
    private data class BaseRun(val x: Float, val width: Float)
    private data class RubyPlacement(
        val baseStart: Int,
        val baseEnd: Int,
        val baseX: Float,
        val baseWidth: Float,
        val spanX: Float,
        val spanWidth: Float,
        val extraWidth: Float,
        val baseOffset: Float,
        val rubyCenterX: Float,
        val reading: String
    )
    private data class CanvasSnapshot(
        val content: AodCanvasContent,
        val layout: LayoutState,
        val renderStyle: RenderStyleSnapshot
    )
    private data class RenderStyleSnapshot(
        val metadataPaint: Paint,
        val originalPaint: Paint,
        val romanizedPaint: Paint,
        val translatedPaint: Paint,
        val rubyPaint: Paint,
        val palette: AodResolvedPalette,
        val alignment: Alignment
    )
    private data class PositionedRow(val row: Row, val baseline: Float, val animate: Boolean)
    private data class OriginalLayout(
        val lines: List<OriginalLine>,
        val lineHeight: Float,
        val lineGap: Float,
        val timed: Boolean
    ) {
        val lineCount: Int
            get() = lines.size
        val rubyHeight: Float
            get() = lines.sumOf { it.rubyHeight.toDouble() }.toFloat()
        private val totalLineWidth = lines.sumOf { it.width.coerceAtLeast(0f).toDouble() }.toFloat()
        private val precedingWidths = FloatArray(lines.size).also { values ->
            var preceding = 0f
            lines.forEachIndexed { index, line ->
                values[index] = preceding
                preceding += line.width.coerceAtLeast(0f)
            }
        }

        fun continuousFill(progress: Float, lineIndex: Int): Float {
            val width = lines[lineIndex].width.coerceAtLeast(0f)
            if (width == 0f || totalLineWidth <= 0f) return 0f
            return ((progress.coerceIn(0f, 1f) * totalLineWidth - precedingWidths[lineIndex]) / width)
                .coerceIn(0f, 1f)
        }
    }
    private data class LayoutState(
        val rows: List<PositionedRow>,
        val original: OriginalLayout
    )
    private data class TypefaceKey(val family: String, val weight: String)

    companion object {
        private const val MAX_ORIGINAL_LINES = 3
        private const val MAX_SECONDARY_LINES = 2
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
        private const val ORIGINAL_LINE_GAP_DP = 4f
        private const val END_EDGE_SAFETY_DP = 4f
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
    }
}
