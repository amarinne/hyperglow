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
    val boundaryAfter: Boolean,
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

internal fun aodWordGapAfter(boundaryAfter: Boolean, gap: Float): Float =
    if (boundaryAfter) gap else 0f

internal fun attachedWordRanges(words: List<AodCanvasWord>): List<IntRange> {
    if (words.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var start = 0
    words.forEachIndexed { index, word ->
        if (word.boundaryAfter || index == words.lastIndex) {
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
            joinedRomanizedWords(words.subList(range.first, range.last + 1).map { it.romanized to it.boundaryAfter }),
            first.startMs,
            maxOf(first.startMs + 1L, last.endMs),
            last.boundaryAfter,
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
    val metadataSizePercent: Int = 100,
    val secondLine: AodCanvasSecondLine? = null,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = 3
)

/**
 * One overlapping sung line rendered next to the primary with the same size:
 * own text, timed words, readings, and active window. Null renders solo.
 */
internal data class AodCanvasSecondLine(
    val text: String = "",
    val romanized: String = "",
    val translated: String = "",
    val alignedRight: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val words: List<AodCanvasWord> = emptyList(),
    val ruby: List<AodCanvasRuby> = emptyList(),
    val layoutGroups: List<AodCanvasLayoutGroup> = emptyList()
)

/**
 * Transition identity: new generation, new primary start, or second-line
 * slot change (join, leave, replacement by start). Lane revisions that only
 * refine text, end timings, or words must not restart the dissolve, or
 * settling lanes strobe the canvas while the same line sings.
 */
internal data class AodLineTransitionKey(
    val trackGeneration: Long,
    val lineStartMs: Long,
    val secondStartMs: Long?
)

internal fun aodLineTransitionKey(content: AodCanvasContent): AodLineTransitionKey =
    AodLineTransitionKey(
        content.trackGeneration,
        content.lineStartMs,
        content.secondLine?.lineStartMs
    )

/**
 * Whether two snapshots lay out identically: every field the builders read,
 * compared structurally. Playback progress (position, sample time, speed)
 * is excluded — heartbeats republish the same line with fresh positions, and
 * rebuilding the whole layout for that is the visible canvas churn.
 */
internal fun layoutEquivalent(a: AodCanvasContent, b: AodCanvasContent): Boolean {
    if (a.trackGeneration != b.trackGeneration ||
        a.original != b.original ||
        a.romanized != b.romanized ||
        a.translated != b.translated ||
        a.alignedRight != b.alignedRight ||
        a.lineStartMs != b.lineStartMs ||
        a.lineEndMs != b.lineEndMs ||
        a.words.size != b.words.size ||
        a.ruby.size != b.ruby.size ||
        a.layoutGroups.size != b.layoutGroups.size ||
        a.weight != b.weight ||
        a.textSizeMode != b.textSizeMode ||
        a.textSizeCustom != b.textSizeCustom ||
        a.secondaryMode != b.secondaryMode ||
        a.animationMode != b.animationMode ||
        a.glowMode != b.glowMode ||
        a.lineSyncFillMode != b.lineSyncFillMode ||
        a.overflowMode != b.overflowMode ||
        a.transitionMode != b.transitionMode ||
        a.fontFamily != b.fontFamily ||
        a.alignmentMode != b.alignmentMode ||
        a.metadata != b.metadata ||
        a.metadataVisible != b.metadataVisible ||
        a.metadataAnchor != b.metadataAnchor ||
        a.metadataSizePercent != b.metadataSizePercent ||
        a.adaptiveSectioning != b.adaptiveSectioning ||
        a.lyricLineLimit != b.lyricLineLimit ||
        a.palette != b.palette
    ) return false
    for (index in a.words.indices) {
        val wa = a.words[index]
        val wb = b.words[index]
        if (wa.text != wb.text || wa.romanized != wb.romanized ||
            wa.startMs != wb.startMs || wa.endMs != wb.endMs ||
            wa.boundaryAfter != wb.boundaryAfter ||
            wa.sourceStart != wb.sourceStart || wa.sourceEnd != wb.sourceEnd
        ) return false
    }
    for (index in a.ruby.indices) {
        if (a.ruby[index] != b.ruby[index]) return false
    }
    for (index in a.layoutGroups.indices) {
        if (a.layoutGroups[index] != b.layoutGroups[index]) return false
    }
    val sa = a.secondLine
    val sb = b.secondLine
    if (sa == null || sb == null) return sa == null && sb == null
    if (sa.text != sb.text || sa.romanized != sb.romanized ||
        sa.translated != sb.translated || sa.alignedRight != sb.alignedRight ||
        sa.lineStartMs != sb.lineStartMs || sa.lineEndMs != sb.lineEndMs ||
        sa.words.size != sb.words.size || sa.ruby.size != sb.ruby.size ||
        sa.layoutGroups.size != sb.layoutGroups.size
    ) return false
    for (index in sa.words.indices) {
        val wa = sa.words[index]
        val wb = sb.words[index]
        if (wa.text != wb.text || wa.romanized != wb.romanized ||
            wa.startMs != wb.startMs || wa.endMs != wb.endMs ||
            wa.boundaryAfter != wb.boundaryAfter ||
            wa.sourceStart != wb.sourceStart || wa.sourceEnd != wb.sourceEnd
        ) return false
    }
    for (index in sa.ruby.indices) {
        if (sa.ruby[index] != sb.ruby[index]) return false
    }
    for (index in sa.layoutGroups.indices) {
        if (sa.layoutGroups[index] != sb.layoutGroups[index]) return false
    }
    return true
}

/**
 * Exit-pass content: identical timing, words, and fill state, but glow
 * muted so an overlapping dissolve cannot double shadow/glow brightness
 * into a flash. Animation mode stays untouched — Minimal would change
 * unsung brightness and karaoke progress presentation, and the exit copy
 * must cross over with the same word states the survivor shows.
 */
internal fun AodCanvasContent.withMutedEffects(): AodCanvasContent =
    copy(glowMode = "Off")

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
        sungText = resolvePaletteColor(
            tokens["sungText"],
            resolvePaletteColor(tokens["primaryText"], Color.WHITE)
        ),
        unsungText = resolvePaletteColor(
            tokens["unsungText"],
            resolvePaletteColor(tokens["primaryText"], Color.WHITE)
        ),
        glow = resolvePaletteColor(tokens["glow"], Color.WHITE),
        accent = resolvePaletteColor(tokens["accent"], Color.WHITE)
    )

private fun resolvePaletteColor(token: String?, fallback: Int): Int = when (token) {
    "white" -> Color.WHITE
    "lavender" -> PALETTE_LAVENDER
    "mint" -> PALETTE_MINT
    "dimmed" -> opaqueRgb(
        (((fallback ushr 16) and 0xFF) * 0.72f).roundToInt(),
        (((fallback ushr 8) and 0xFF) * 0.72f).roundToInt(),
        ((fallback and 0xFF) * 0.72f).roundToInt()
    )
    else -> token?.let(::parsePaletteHexColor) ?: fallback
}

private const val PALETTE_LAVENDER = 0xFFB9A8FF.toInt()
private const val PALETTE_MINT = 0xFF62D891.toInt()

private fun parsePaletteHexColor(value: String): Int? {
    if (!value.matches(Regex("#[0-9a-fA-F]{6}"))) return null
    return value.substring(1).toLongOrNull(16)?.toInt()?.let { 0xFF000000.toInt() or it }
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

/** Attaches common Latin punctuation to its neighboring lexical chunk. */
internal fun attachAodPunctuationGroups(words: List<AodCanvasWord>, ids: List<Int?>): List<Int?> {
    if (words.size != ids.size) return ids
    val result = ids.mapIndexed { index, id -> id ?: -(index + 1) }.toMutableList()
    val openQuotes = mutableSetOf<Char>()
    words.forEachIndexed { index, word ->
        val text = word.text.trim()
        val neighbor = when {
            text.isEmpty() -> null
            text == "\"" || text == "'" -> {
                if (openQuotes.add(text.single())) index + 1
                else { openQuotes.remove(text.single()); index - 1 }
            }
            text.all { aodPunctuationAttachToPrevious(it.code) } -> index - 1
            text.all { aodPunctuationAttachToNext(it.code) } -> index + 1
            else -> null
        }
        if (neighbor != null && neighbor in result.indices) {
            val from = result[index]
            val to = result[neighbor]
            for (i in result.indices) if (result[i] == from) result[i] = to
        }
    }
    return result
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

/** Writing-system line-break classes used by producers and tests. */
internal fun aodPunctuationAttachToPrevious(codePoint: Int): Boolean =
    codePoint.toChar() in ",.;:!?…，。！？：；、)]}」』】》〉》”’"

internal fun aodPunctuationAttachToNext(codePoint: Int): Boolean =
    codePoint.toChar() in "([{「『【《〈“‘"

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

internal enum class AodTextDirection { LTR, RTL }

internal fun firstStrongAodTextDirection(text: String): AodTextDirection? {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return AodTextDirection.RTL
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return AodTextDirection.LTR
        }
        index += Character.charCount(codePoint)
    }
    return null
}

internal fun resolvedAodTextDirection(
    text: String,
    words: List<AodCanvasWord> = emptyList()
): AodTextDirection = firstStrongAodTextDirection(text)
    ?: words.firstOrNull { firstStrongAodTextDirection(it.text) == AodTextDirection.RTL }
        ?.let { AodTextDirection.RTL }
    ?: AodTextDirection.LTR

internal fun resolvedAodPhysicalAlignment(
    configured: String,
    oppositeAligned: Boolean,
    direction: AodTextDirection
): String {
    val start = if (direction == AodTextDirection.RTL) "end" else "start"
    val end = if (direction == AodTextDirection.RTL) "start" else "end"
    return when (configured) {
        "start" -> start
        "center" -> "center"
        "end" -> end
        else -> if (oppositeAligned) end else start
    }
}

internal fun timedWordDrawX(
    lineStartX: Float,
    lineWidth: Float,
    precedingWidth: Float,
    wordWidth: Float,
    direction: AodTextDirection
): Float = if (direction == AodTextDirection.RTL) {
    lineStartX + lineWidth - precedingWidth - wordWidth
} else {
    lineStartX + precedingWidth
}

internal data class GradientSweepZone(val start: Float, val end: Float)

internal fun gradientSweepZone(
    progress: Float,
    extent: Float,
    bandFraction: Float = 0.4f,
    direction: AodTextDirection = AodTextDirection.LTR
): GradientSweepZone {
    val safeExtent = extent.coerceAtLeast(0f)
    val band = (safeExtent * bandFraction.coerceIn(0.1f, 1f)).coerceAtLeast(1f)
    val value = progress.coerceIn(0f, 1f)
    val start = if (direction == AodTextDirection.RTL) {
        safeExtent - (safeExtent + band) * value
    } else {
        -band + (safeExtent + band) * value
    }
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
    words.forEachIndexed { index, (text, boundaryAfter) ->
        if (text.isBlank()) return@forEachIndexed
        append(text)
        if (boundaryAfter && words.drop(index + 1).any { it.first.isNotBlank() }) append(' ')
    }
}

internal fun baseTextSizeSp(text: String): Float = when {
    text.codePointCount(0, text.length) >= 30 -> 23f
    text.codePointCount(0, text.length) >= 22 -> 24f
    text.codePointCount(0, text.length) >= 14 -> 26f
    else -> 28f
} * LIVE_CARD_SIZE_MULTIPLIER

/**
 * Wrap-mode base size: fixed, never bucketed by line length. Length buckets
 * exist so single-line Clip layouts shrink long lines toward fitting, but in
 * Wrap mode every primary change to a different-length line would resize all
 * shared paints and reflow every section. A fixed base keeps all paint sizes
 * (and therefore all row heights, spans, and fit scales) stable across line
 * changes; wrapping absorbs length instead.
 */
internal const val WRAP_MODE_TEXT_SP = 26f

internal fun baseTextSizeForMode(text: String, overflowMode: String): Float =
    if (overflowMode != "Wrap") baseTextSizeSp(text)
    else WRAP_MODE_TEXT_SP * LIVE_CARD_SIZE_MULTIPLIER

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

internal fun rubyTopShift(rubyClipTop: Float, logicalPadTop: Float): Float =
    max(0f, logicalPadTop - rubyClipTop)

internal fun metadataLayoutBounds(
    anchor: String,
    height: Float,
    logicalPadTop: Float,
    logicalPadBottom: Float,
    metadataAscent: Float,
    metadataDescent: Float,
    gap: Float,
    extraLineHeight: Float = 0f
): MetadataLayoutBounds {
    val metadataBaseline = if (anchor == "bottom") {
        height - logicalPadBottom - metadataDescent - extraLineHeight
    } else {
        logicalPadTop - metadataAscent
    }
    return if (anchor == "bottom") {
        MetadataLayoutBounds(metadataBaseline, logicalPadTop, metadataBaseline + metadataAscent - gap)
    } else {
        MetadataLayoutBounds(metadataBaseline, metadataBaseline + metadataDescent + extraLineHeight + gap, height - logicalPadBottom)
    }
}

internal fun metadataLineTexts(text: String): List<String> =
    text.split('·', '\n').map { it.trim() }.filter { it.isNotEmpty() }

internal fun metadataTextSizeMultiplier(percent: Int): Float =
    percent.coerceIn(50, 200) / 100f

internal fun metadataWidgetHeightDp(percent: Int): Float =
    22f + 14f * metadataTextSizeMultiplier(percent)

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

internal fun isSongChangeMetadataPlaceholder(
    original: String,
    metadata: String,
    lineStartMs: Long,
    lineEndMs: Long,
    hasTimedWords: Boolean
): Boolean = metadata.isNotBlank() && original == metadata &&
    lineEndMs <= lineStartMs && !hasTimedWords

internal fun shouldMorphSongChangeMetadata(
    previousOriginal: String,
    previousMetadata: String,
    previousLineStartMs: Long,
    previousLineEndMs: Long,
    previousHasTimedWords: Boolean,
    nextMetadata: String,
    nextMetadataVisible: Boolean
): Boolean = nextMetadataVisible && previousMetadata == nextMetadata &&
    isSongChangeMetadataPlaceholder(
        previousOriginal,
        previousMetadata,
        previousLineStartMs,
        previousLineEndMs,
        previousHasTimedWords
    )

internal fun interpolateAodColor(start: Int, end: Int, progress: Float): Int {
    val value = progress.coerceIn(0f, 1f)
    fun channel(from: Int, to: Int): Int = (from + (to - from) * value).roundToInt()
    return Color.argb(
        channel(Color.alpha(start), Color.alpha(end)),
        channel(Color.red(start), Color.red(end)),
        channel(Color.green(start), Color.green(end)),
        channel(Color.blue(start), Color.blue(end))
    )
}

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
        "Left to right (whole block)" -> "Left to right (whole block)"
        "Left to right",
        "Left to right (main only)",
        "Left to right (sentence)" -> "Left to right (main only)"
        else -> "Top to bottom"
    }

internal fun resolvedLyricLayoutLineLimit(
    configuredLimit: Int,
    originalLength: Int,
    wordCount: Int
): Int = if (configuredLimit in 1..5) {
    configuredLimit
} else {
    maxOf(originalLength, wordCount, 1)
}

internal enum class AodCanvasVerticalAlignment { TOP, CENTER }

private const val LIVE_CARD_SIZE_MULTIPLIER = 0.68f
private const val AOD_DIMMING_BOOST = 1.6f // Sanctioned AOD dimming delta; preserves hardware contrast.

private fun steadyTextAlpha(factor: Float): Float = if (factor < 0.5f) {
    max(0.35f * AOD_DIMMING_BOOST, 0.55f)
} else {
    minOf(1f, 0.85f * AOD_DIMMING_BOOST)
}

internal fun staticSecondaryTextFactor(bright: Boolean): Float = if (bright) 1f else 0.35f

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
        metadataSizePercent = 100,
        adaptiveSectioning = true,
        palette = emptyMap(),
        secondaryTextBright = true,
        lyricLineLimit = 3
    )
    private var resolvedPalette = resolveAodPalette(emptyMap())
    private var alignment = Alignment.START
    private var textDirection = AodTextDirection.LTR
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
    private var verticalBias: Float? = null
    private var orientationStep = 0
    private var landscapeTextScale = 1f
    /** TEMPORARY: last logged wrapped-row index per section (crossing diag). */
    private val crossingTracker = HashMap<Long, Int>()
    /**
     * Committed presentation decisions for the unwrap-on-floor policy:
     * section id -> (section count at decision time, unwrapped). A committed
     * section keeps its recorded presentation through same-geometry rebuilds
     * (late timing refinements may not flip it); a section-count change
     * (duet join or leave) re-evaluates once.
     */
    private val sectionPresentation = HashMap<DuetSectionId, Pair<Int, Boolean>>()
    /**
     * Committed draw scales for the conveyor: section id -> scale. An
     * anchored section keeps its committed scale through partner swaps — the
     * continuing line never resizes; newcomers size themselves into the
     * leftover area, and only a genuine combined overflow rescales everyone.
     */
    private val sectionScaleCommit = HashMap<DuetSectionId, Float>()
    /** Last laid-out logical frame, to distinguish real frame changes from size churn. */
    private var lastLogicalFrameWidth = 0
    private var lastLogicalFrameHeight = 0
    /** Visual section ids of the last layout, for positional slot assignment. */
    private var lastOrderedIds: List<DuetSectionId> = emptyList()
    /**
     * Last rendered section tops by line id. A continuing line keeps its
     * exact top across rebuilds, so the survivor of a join/leave/change
     * never moves; newcomers stack adjacently. Cleared only when the
     * coordinate space itself changes (track generation, orientation step,
     * alignment change) — metadata on/off publication re-solves against the
     * new area while keeping section anchors.
     */
    private var sectionTops: Map<DuetSectionId, Float> = emptyMap()
    /** Center of the last lyric block, for anchoring full swaps. */
    private var lastBlockCenter: Float? = null
    private var anchorGeneration: Long? = null
    /** Track generations that have shown a duet; stable chain-membership policy. */
    private val episodeDuetGenerations = HashSet<Long>()

    private fun clearSectionAnchors() {
        sectionTops = emptyMap()
        // Section-local decisions key off the same geometry as the anchors:
        // a frame change re-fits presentations and scales at the new budget
        // instead of replaying commits from a stale one.
        sectionPresentation.clear()
        sectionScaleCommit.clear()
        lastBlockCenter = null
        wrapCache.clear()
    }

    /**
     * Native-style logical frame: side steps lay out in long-axis x short-axis
     * coordinates (as if the window itself rotated), then one rigid transform
     * maps that frame onto the portrait view. Portrait steps use view size.
     */
    private fun isSideStep(): Boolean = orientationStep == 90 || orientationStep == 270

    internal fun layoutFrameWidth(): Int =
        if (isSideStep()) maxOf(width, height) else width

    internal fun layoutFrameHeight(): Int =
        if (isSideStep()) minOf(width, height) else height

    /**
     * Logical-frame content padding in pixels. Percent config resolves to px
     * against the logical axes (X% of frame width, Y% of frame height), which
     * a symmetric View padding cannot express once the frame is rotated.
     */
    private var logicalPadLeft = 0f
    private var logicalPadTop = 0f
    private var logicalPadRight = 0f
    private var logicalPadBottom = 0f

    fun setLogicalPadding(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int) {
        val next = floatArrayOf(
            leftPx.coerceAtLeast(0).toFloat(),
            topPx.coerceAtLeast(0).toFloat(),
            rightPx.coerceAtLeast(0).toFloat(),
            bottomPx.coerceAtLeast(0).toFloat()
        )
        if (logicalPadLeft == next[0] && logicalPadTop == next[1] &&
            logicalPadRight == next[2] && logicalPadBottom == next[3]
        ) return
        logicalPadLeft = next[0]
        logicalPadTop = next[1]
        logicalPadRight = next[2]
        logicalPadBottom = next[3]
        clearSectionAnchors()
        rebuildLayout()
    }
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
    private val horizontalRtlSweepShaders = SparseArray<LinearGradient>(4)
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
        // Heartbeats republish the same singing line with a fresh position.
        // A full rebuild for that re-measures every word on the main thread
        // and reads as a canvas flash; identical layout inputs only advance
        // timing and cadence state. A paused line can resume through this
        // path (speed flips 0 <-> 1), so the karaoke gate and frame loop
        // must re-evaluate; the redraw itself only matters when the draw
        // depends on timing, transition, or mutable draw-only styling.
        if (layoutEquivalent(this.content, nextContent)) {
            this.content = nextContent
            timingEffectEnabled = hasActiveCanvasTiming(
                nextContent.lineLevelSync,
                nextContent.lineSyncFillMode,
                nextContent.lineStartMs,
                nextContent.lineEndMs,
                nextContent.words + nextContent.secondLine?.words.orEmpty(),
                nextContent.speed
            )
            syncCadence()
            invalidate()
            return
        }
        val lineChanged = this.content.original.isNotBlank() &&
            aodLineTransitionKey(this.content) != aodLineTransitionKey(nextContent)
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
            nextContent.words + nextContent.secondLine?.words.orEmpty(),
            nextContent.speed
        )
        resolvedPalette = resolveAodPalette(nextContent.palette)
        textDirection = resolvedAodTextDirection(nextContent.original, nextContent.words)
        alignment = when (resolvedAodPhysicalAlignment(
            nextContent.alignmentMode,
            nextContent.alignedRight,
            textDirection
        )) {
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> Alignment.START
        }
        val sizeScale = textSizeModeMultiplier(nextContent.textSizeMode, nextContent.textSizeCustom)
        val baseSp = baseTextSizeForMode(nextContent.original, nextContent.overflowMode) * sizeScale
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
        sizePaints()
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        syncCadence()
        invalidate()
    }

    /**
     * Landscape-only text multiplier, applied on top of the profile size while
     * a side orientation step is active. Portrait rendering is untouched.
     */
    fun setLandscapeTextScale(scale: Float) {
        val normalized = if (scale.isFinite()) scale.coerceIn(0.5f, 2f) else 1f
        if (landscapeTextScale == normalized) return
        landscapeTextScale = normalized
        sizePaints()
        // Text-scale changes resize sections but not the logical frame, so
        // anchored tops stay valid: the survivor holds its position across
        // orientation-scale publication, and the clamp re-pins only when the
        // new heights genuinely cannot fit.
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        invalidate()
    }

    private fun effectiveTextScale(): Float =
        if (orientationStep == 90 || orientationStep == 270) landscapeTextScale else 1f

    private fun sizePaints() {
        val sizeScale = textSizeModeMultiplier(content.textSizeMode, content.textSizeCustom) *
            effectiveTextScale()
        val baseSp = baseTextSizeForMode(content.original, content.overflowMode) * sizeScale
        originalPaint.textSize = baseSp * scaledDensity
        metadataPaint.textSize = 14f * metadataTextSizeMultiplier(
            content.metadataSizePercent
        ) * effectiveTextScale() * scaledDensity
        romanizedPaint.textSize = max(14f, kotlin.math.round(baseSp * 0.48f)) * scaledDensity
        translatedPaint.textSize = max(13f, kotlin.math.round(baseSp * 0.48f) - 1f) * scaledDensity
        rubyPaint.textSize = originalPaint.textSize * 0.46f
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
        clearSectionAnchors()
        rebuildLayout()
        invalidate()
    }

    fun visibleContentVerticalBounds(): AodCanvasVerticalBounds? =
        unionAodCanvasVerticalBounds(
            scaledLayoutBounds(layout),
            exitSnapshot?.layout?.let(::scaledLayoutBounds)
        )

    /** Layout bounds mapped through per-section fit scales, metadata as-is. */
    private fun scaledLayoutBounds(state: LayoutState): AodCanvasVerticalBounds? {
        val tops = measuredSectionTops(state.rows)
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (placed in state.rows) {
            var rowTop = placed.baseline + placed.row.paint.fontMetrics.ascent
            var rowBottom = rowTop + placed.row.height
            if (placed.row.kind != RowKind.METADATA) {
                val scale = state.sectionScales[placed.row.blockIndex] ?: 1f
                if (scale != 1f) {
                    val origin = tops[placed.row.blockIndex] ?: rowTop
                    rowTop = origin + scale * (rowTop - origin)
                    rowBottom = origin + scale * (rowBottom - origin)
                }
            }
            if (rowTop < top) top = rowTop
            if (rowBottom > bottom) bottom = rowBottom
        }
        if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return null
        return AodCanvasVerticalBounds(
            top.coerceIn(0f, layoutFrameHeight().toFloat()),
            bottom.coerceIn(0f, layoutFrameHeight().toFloat())
        )
    }

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
        // Xiaomi layout passes publish transient physical size churn that
        // leaves the logical frame unchanged (side steps render long x short
        // inside the same portrait view). Only a logical-frame change makes
        // anchored coordinates meaningless; everything else keeps the
        // episode and rebuilds silently.
        val logicalChanged = layoutFrameWidth() != lastLogicalFrameWidth ||
            layoutFrameHeight() != lastLogicalFrameHeight
        lastLogicalFrameWidth = layoutFrameWidth()
        lastLogicalFrameHeight = layoutFrameHeight()
        if (logicalChanged) clearSectionAnchors()
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
        // Native-style rotation: layout runs in the logical frame
        // (long x short for side steps), then one rigid transform maps it
        // onto the fullscreen portrait view. No oversized child, no manual
        // translation, no viewport strip.
        val step = orientationStep
        val logical = if (step == 90 || step == 270) {
            canvas.save().also {
                val logicalWidth = layoutFrameWidth().toFloat()
                val logicalHeight = layoutFrameHeight().toFloat()
                canvas.translate(width / 2f, height / 2f)
                canvas.rotate(if (step == 90) 90f else 270f)
                canvas.translate(-logicalWidth / 2f, -logicalHeight / 2f)
                canvas.clipRect(0f, 0f, logicalWidth, logicalHeight)
            }
        } else if (step == 180) {
            canvas.save().also { canvas.rotate(180f, width / 2f, height / 2f) }
        } else -1
        val snapshot = exitSnapshot
        if (snapshot == null) {
            drawMetadata(canvas, layout)
            drawRows(canvas, layout, content, 1f, 0f)
            if (logical != -1) canvas.restoreToCount(logical)
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - transitionStartedAt).coerceAtLeast(0L)
        val exitProgress = (elapsed / EXIT_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val enterProgress = (elapsed / ENTER_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val metadataMorph = shouldMorphSongChangeMetadata(
            previousOriginal = snapshot.content.original,
            previousMetadata = snapshot.content.metadata,
            previousLineStartMs = snapshot.content.lineStartMs,
            previousLineEndMs = snapshot.content.lineEndMs,
            previousHasTimedWords = snapshot.content.words.any { it.endMs > it.startMs },
            nextMetadata = content.metadata,
            nextMetadataVisible = content.metadataVisible
        ) && canDrawMetadataMorph(snapshot)
        if (metadataMorph) {
            drawMetadataMorph(canvas, snapshot, enterProgress)
        } else if (snapshot.content.metadata != content.metadata ||
            snapshot.content.metadataVisible != content.metadataVisible ||
            snapshot.content.metadataAnchor != content.metadataAnchor
        ) {
            drawMetadata(canvas, snapshot.layout, 1f - exitProgress, snapshot.renderStyle)
            drawMetadata(canvas, layout, enterProgress)
        } else {
            drawMetadata(canvas, layout)
        }
        // Section-ownership transition: continuing sections draw exactly
        // once at full opacity from the incoming layout (the survivor never
        // crossfades against itself — source-over compositing of two 50%
        // passes yields 75% combined alpha, the join brightness dip);
        // departing sections fade out with the exit pass; arriving sections
        // fade in with the enter pass. Solo-to-solo keeps the full
        // crossfade. Layouts predating section identity fall back to the
        // legacy full-canvas passes.
        val sectionIdsKnown = snapshot.layout.sectionIds.isNotEmpty() &&
            layout.sectionIds.isNotEmpty()
        if (sectionIdsKnown) {
            val passes = resolveDuetTransitionPasses(
                snapshot.layout.sectionIds,
                layout.sectionIds,
                snapshot.layout.blocks.map { it.originalLayout.lineCount },
                layout.blocks.map { it.originalLayout.lineCount }
            )
            val duetTransition = snapshot.layout.blocks.size > 1 || layout.blocks.size > 1
            val slide = content.transitionMode == "Fade up" && !duetTransition
            drawRows(
                canvas,
                snapshot.layout,
                snapshot.content.withMutedEffects(),
                1f - exitProgress,
                if (slide) -14f * density * exitProgress else 0f,
                snapshot.renderStyle,
                skipOriginal = metadataMorph,
                blockFilter = { block -> block in passes.departingExitBlocks }
            )
            drawRows(
                canvas,
                layout,
                content,
                1f,
                0f,
                blockFilter = { block -> block in passes.continuingEnterBlocks }
            )
            drawRows(
                canvas,
                layout,
                content,
                enterProgress,
                if (slide) 14f * density * (1f - enterProgress) else 0f,
                blockFilter = { block -> block in passes.arrivingEnterBlocks }
            )
        } else {
            val duetTransition = snapshot.layout.blocks.size > 1 || layout.blocks.size > 1
            val slide = content.transitionMode == "Fade up" && !duetTransition
            drawRows(
                canvas,
                snapshot.layout,
                snapshot.content.withMutedEffects(),
                1f - exitProgress,
                if (slide) -14f * density * exitProgress else 0f,
                snapshot.renderStyle,
                skipOriginal = metadataMorph
            )
            drawRows(
                canvas,
                layout,
                content,
                enterProgress,
                if (slide) 14f * density * (1f - enterProgress) else 0f
            )
        }
        if (logical != -1) canvas.restoreToCount(logical)
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
        renderStyle: RenderStyleSnapshot? = null,
        skipOriginal: Boolean = false,
        blockFilter: ((Int) -> Boolean)? = null
    ) {
        if (alpha <= 0f || drawLayout.rows.none {
                it.row.kind != RowKind.METADATA &&
                    (!skipOriginal || it.row.kind != RowKind.ORIGINAL) &&
                    blockFilter?.invoke(it.row.blockIndex) != false
            }
        ) return
        val savedContent = content
        val savedLayout = layout
        if (renderStyle != null) applyRenderStyle(renderStyle)
        content = drawContent
        layout = drawLayout
        val layer = if (alpha < 1f) {
            canvas.saveLayerAlpha(0f, 0f, layoutFrameWidth().toFloat(), layoutFrameHeight().toFloat(), (255f * alpha).toInt())
        } else {
            canvas.save()
        }
        if (translateY != 0f) canvas.translate(0f, translateY)
        // All lyric draw paths (original, ruby, secondary, timed sweeps, and
        // scaled duet sections) share this logical clip. This enforces both
        // horizontal padding edges even when an indivisible word or animated
        // visual overhang exceeds its measured advance width.
        canvas.clipRect(
            logicalPadLeft,
            logicalPadTop,
            layoutFrameWidth() - logicalPadRight,
            layoutFrameHeight() - logicalPadBottom
        )
        // Each section draws at its own fit scale around its precomputed
        // alignment-aware pivot, so a tall newcomer shrinks itself without
        // moving the survivor's alignment edge. The common all-full-size
        // case keeps the exact legacy single pass.
        val blocks = blockDrawDataFor(drawLayout, drawContent)
        val deferred = deferredDuetBlockIndices(
            blocks.map { it.lineStartMs },
            projectedPosition()
        )
        val passesFilter = blockFilter
        val lyricGroups = drawLayout.rows.filter { it.row.kind != RowKind.METADATA }
            .groupBy { it.row.blockIndex }.toSortedMap()
        val needsScales = drawLayout.sectionScales.values.any { it != 1f }
        if (!needsScales && passesFilter == null) {
            drawLyricRowGroup(canvas, drawLayout.rows, blocks, deferred, drawContent, skipOriginal)
        } else {
            val sectionTops = measuredSectionTops(drawLayout.rows)
            for ((block, group) in lyricGroups) {
                if (passesFilter?.invoke(block) == false) continue
                val scale = drawLayout.sectionScales[block] ?: 1f
                if (scale == 1f) {
                    drawLyricRowGroup(canvas, group, blocks, deferred, drawContent, skipOriginal)
                    continue
                }
                val save = canvas.save()
                val originX = drawLayout.sectionPivotsX[block]
                    ?: layoutFrameWidth() / 2f
                val originY = sectionTops[block] ?: group.firstOrNull()?.let {
                    it.baseline + it.row.paint.fontMetrics.ascent - it.row.gapBefore
                } ?: 0f
                canvas.translate(originX, originY)
                canvas.scale(scale, scale)
                canvas.translate(-originX, -originY)
                drawLyricRowGroup(canvas, group, blocks, deferred, drawContent, skipOriginal)
                canvas.restoreToCount(save)
            }
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    /**
     * Draws one lyric section's rows: shared line-level sweep where it
     * applies, word-timed originals, and static secondary rows. The caller
     * owns the canvas transform; all coordinates stay in layout space.
     */
    private fun drawLyricRowGroup(
        canvas: Canvas,
        group: List<PositionedRow>,
        blocks: List<BlockDrawData>,
        deferred: Set<Int>,
        drawContent: AodCanvasContent,
        skipOriginal: Boolean
    ) {
        val sweepRows = group.filter { positioned ->
            positioned.row.kind != RowKind.METADATA &&
                positioned.row.blockIndex !in deferred &&
                blocks.getOrNull(positioned.row.blockIndex)?.let { block ->
                    shouldUseSharedLineLevelSweep(
                        drawContent.lineLevelSync,
                        block.originalLayout.lines.isNotEmpty(),
                        drawContent.animationMode,
                        block.lineStartMs,
                        block.lineEndMs
                    )
                } == true
        }
        if (sweepRows.any { it.row.kind == RowKind.ORIGINAL }) {
            drawSharedLineLevelRows(canvas, sweepRows, blocks)
        }
        val sweepSet = sweepRows.toSet()
        for (row in group) {
            if (row.row.kind == RowKind.METADATA || row in sweepSet ||
                row.row.blockIndex in deferred
            ) {
                continue
            }
            when (row.row.kind) {
                RowKind.METADATA -> Unit
                RowKind.ORIGINAL -> if (!skipOriginal) {
                    blocks.getOrNull(row.row.blockIndex)?.let { block ->
                        drawOriginal(canvas, row.baseline, block)
                    }
                }
                else -> drawText(canvas, row.row, row.baseline)
            }
        }
    }

    /**
     * Line-level sweep per lyric section: each concurrent block runs the same
     * sweep mode with its own progress window, so overlapping lines fill
     * independently inside their own canvas sections.
     */
    private fun drawSharedLineLevelRows(
        canvas: Canvas,
        rows: List<PositionedRow>,
        blocks: List<BlockDrawData>
    ) {
        clearBlockSweepShaders()
        val mode = resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode)
        val groups = rows.filter { it.row.kind != RowKind.METADATA }
            .groupBy { it.row.blockIndex }
        for ((blockIndex, group) in groups) {
            val original = group.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: continue
            val block = blocks.getOrNull(blockIndex) ?: continue
            val progress = blockProgress(block)
            if (mode == "Left to right (whole block)") {
                drawWholeBlockSweepRows(canvas, group, original.baseline, progress, block)
                continue
            }
            drawSecondaryRowsStatic(canvas, group, bright = content.secondaryTextBright)
            drawOriginalRubyRows(canvas, original.baseline, block, bright = true)
            when (mode) {
                "None" -> {
                    drawUntimedLines(canvas, original.baseline, bright = true, progress, block)
                }
                "Left to right (main only)" -> {
                    drawContinuousLineFill(canvas, original.baseline, progress, block)
                    clearBlockSweepShaders()
                }
                else -> {
                    drawUntimedLines(canvas, original.baseline, false, progress, block)
                    val blockTop = (original.baseline + original.row.paint.fontMetrics.ascent)
                        .coerceAtLeast(logicalPadTop)
                    val blockBottom = (blockTop + original.row.height)
                        .coerceAtMost((layoutFrameHeight() - logicalPadBottom))
                    applyBlockSweepShaders(
                        origin = blockTop,
                        progress = progress,
                        extent = blockBottom - blockTop
                    )
                    drawUntimedLines(canvas, original.baseline, true, progress, block)
                    clearBlockSweepShaders()
                }
            }
        }
    }

    private fun drawWholeBlockSweepRows(
        canvas: Canvas,
        rows: List<PositionedRow>,
        baseline: Float,
        progress: Float,
        block: BlockDrawData
    ) {
        drawSecondaryRowsStatic(canvas, rows, bright = false)
        drawOriginalRubyRows(canvas, baseline, block, bright = false)
        drawUntimedLines(canvas, baseline, bright = false, progress, block)
        applyWholeBlockHorizontalSweepShaders(progress)
        drawSecondaryRowsStatic(
            canvas,
            rows,
            bright = content.secondaryTextBright,
            keepShader = true
        )
        drawOriginalRubyRows(canvas, baseline, block, bright = true)
        drawUntimedLines(canvas, baseline, bright = true, progress, block)
        clearBlockSweepShaders()
    }

    private fun drawSecondaryRowsStatic(
        canvas: Canvas,
        rows: List<PositionedRow>,
        bright: Boolean,
        keepShader: Boolean = false
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
                staticSecondaryTextFactor(bright),
                1f,
                resolvedPalette.secondaryText
            )
            if (!keepShader) positioned.row.paint.shader = null
            positioned.row.paint.clearShadowLayer()
            var lineIndex = 0
            while (lineIndex < positioned.row.lines.size) {
                val line = positioned.row.lines[lineIndex]
                drawDirectionalText(
                    canvas,
                    line.text,
                    line.startX,
                    positioned.baseline + lineIndex * positioned.row.lineHeight,
                    positioned.row.paint,
                    resolvedAodTextDirection(line.text)
                )
                lineIndex++
            }
            rowIndex++
        }
    }

    private fun drawOriginalRubyRows(
        canvas: Canvas,
        baseline: Float,
        block: BlockDrawData,
        bright: Boolean
    ) {
        val originalLayout = block.originalLayout
        var precedingRuby = 0f
        originalLayout.lines.forEachIndexed { lineIndex, line ->
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
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
        alignment = alignment,
        textDirection = textDirection
    )

    private fun applyRenderStyle(style: RenderStyleSnapshot) {
        metadataPaint.set(style.metadataPaint)
        originalPaint.set(style.originalPaint)
        romanizedPaint.set(style.romanizedPaint)
        translatedPaint.set(style.translatedPaint)
        rubyPaint.set(style.rubyPaint)
        resolvedPalette = style.palette
        alignment = style.alignment
        textDirection = style.textDirection
    }

    private fun drawMetadata(
        canvas: Canvas,
        drawLayout: LayoutState,
        alpha: Float = 1f,
        renderStyle: RenderStyleSnapshot? = null
    ) {
        if (alpha <= 0f) return
        val metadata = drawLayout.rows.firstOrNull { it.row.kind == RowKind.METADATA } ?: return
        if (renderStyle != null) applyRenderStyle(renderStyle)
        canvas.save()
        canvas.clipRect(logicalPadLeft, logicalPadTop, layoutFrameWidth() - logicalPadRight, layoutFrameHeight() - logicalPadBottom)
        metadata.row.paint.color = resolvedPalette.metadataText
        metadata.row.paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        metadata.row.lines.forEachIndexed { index, line ->
            canvas.drawText(
                line.text,
                line.startX,
                metadata.baseline + index * metadata.row.lineHeight,
                metadata.row.paint
            )
        }
        canvas.restore()
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun canDrawMetadataMorph(snapshot: CanvasSnapshot): Boolean =
        snapshot.layout.original.lines.size == 1 &&
            snapshot.layout.rows.count { it.row.kind == RowKind.ORIGINAL } == 1 &&
            layout.rows.firstOrNull { it.row.kind == RowKind.METADATA }
                ?.row?.lines?.size == 1

    private fun drawMetadataMorph(
        canvas: Canvas,
        snapshot: CanvasSnapshot,
        progress: Float
    ) {
        val sourceRow = snapshot.layout.rows.firstOrNull {
            it.row.kind == RowKind.ORIGINAL
        } ?: return
        val sourceLine = snapshot.layout.original.lines.singleOrNull() ?: return
        val destinationRow = layout.rows.firstOrNull {
            it.row.kind == RowKind.METADATA
        } ?: return
        val destinationLine = destinationRow.row.lines.singleOrNull() ?: return
        val value = progress.coerceIn(0f, 1f)
        val paint = Paint(
            if (value < 0.5f) snapshot.renderStyle.originalPaint
            else currentRenderStyle.metadataPaint
        ).apply {
            textSize = snapshot.renderStyle.originalPaint.textSize +
                (currentRenderStyle.metadataPaint.textSize -
                    snapshot.renderStyle.originalPaint.textSize) * value
            color = interpolateAodColor(
                snapshot.renderStyle.palette.primaryText,
                currentRenderStyle.palette.metadataText,
                value
            )
            alpha = 255
            shader = null
            clearShadowLayer()
        }
        val x = sourceLine.startX + (destinationLine.startX - sourceLine.startX) * value
        val y = sourceRow.baseline + (destinationRow.baseline - sourceRow.baseline) * value
        canvas.save()
        canvas.clipRect(logicalPadLeft, logicalPadTop, layoutFrameWidth() - logicalPadRight, layoutFrameHeight() - logicalPadBottom)
        canvas.drawText(content.metadata, x, y, paint)
        canvas.restore()
    }

    private fun rebuildLayout() {
        // Episode state snapshot: blank, placeholder, and degenerate passes
        // restore everything below instead of committing.
        val savedTops = sectionTops
        val savedCenter = lastBlockCenter
        val savedOrder = lastOrderedIds
        val savedAnchorGen = anchorGeneration
        // Blank gap builds, song-change intro placeholders, and pre-layout
        // zero-size frames clear the screen but must not commit episode
        // state: wiping anchors/order (or flip-flopping the generation) makes
        // the next real build re-place everything.
        val built = buildLyricLayout()
        val frameUsable = layoutFrameWidth() > 0 && layoutFrameHeight() > 0
        val hasLyricRows = built.first.rows.any { it.row.kind != RowKind.METADATA }
        val timingValid = content.lineEndMs > content.lineStartMs
        if (shouldCommitLayoutState(hasLyricRows, frameUsable, timingValid)) {
            // Anchors commit once per rebuild from the single full-size pass.
            sectionTops = built.second
            lastBlockCenter = built.third
        } else {
            sectionTops = savedTops
            lastBlockCenter = savedCenter
            lastOrderedIds = savedOrder
            anchorGeneration = savedAnchorGen
        }
        layout = built.first
        contentBoundsChangedListener?.invoke()
    }

    /** Lyric area vertical bounds (top to bottom) for the current content. */
    private fun lyricAreaBounds(hasMetadata: Boolean): Pair<Float, Float> {
        val frameHeight = layoutFrameHeight().toFloat()
        if (!hasMetadata) return logicalPadTop to (frameHeight - logicalPadBottom)
        val anchor = if (content.metadataAnchor == "bottom") "bottom" else "top"
        val bounds = metadataLayoutBounds(
            anchor,
            frameHeight,
            logicalPadTop,
            logicalPadBottom,
            metadataPaint.fontMetrics.ascent,
            metadataPaint.fontMetrics.descent,
            10f * density,
            (metadataLineTexts(content.metadata).size - 1).coerceAtLeast(0) *
                safeSecondaryLineHeight(metadataPaint.fontMetrics.ascent,
                    metadataPaint.fontMetrics.descent, metadataPaint.fontMetrics.bottom)
        )
        return bounds.lyricStart to bounds.lyricEnd
    }

    private fun buildLyricLayout(): Triple<LayoutState, Map<DuetSectionId, Float>, Float?> {
        val metadataRows = ArrayList<Row>(1)
        val metadataPlaceholder = isSongChangeMetadataPlaceholder(
            content.original,
            content.metadata,
            content.lineStartMs,
            content.lineEndMs,
            content.words.any { it.endMs > it.startMs }
        )
        if (content.metadataVisible && content.metadata.isNotBlank() && !metadataPlaceholder) {
            metadataRows += row(RowKind.METADATA, content.metadata, metadataPaint, 0f, true)
                .copy(blockIndex = -1)
        }
        val primary = MainLineData(content)
        val second = content.secondLine
            ?.takeIf { it.text.isNotBlank() }
            ?.let { MainLineData(it) }
        // Duet slot conveyor, earliest first: the line already on screen
        // keeps its exact rows while a newcomer stacks adjacently, an
        // expired line's slot stays logically reserved, and the next arrival
        // takes it. A lone fresh line centers exactly as before.
        // Keep the previous visual membership before replacing the ordered
        // ids below. When a duet leaves, the remaining line must be treated
        // as a fresh solo so the configured vertical bias (50% by default)
        // is applied again instead of retaining the duet's lower slot.
        val wasDuet = lastOrderedIds.size > 1
        val primaryId =
            DuetSectionId(content.trackGeneration, content.lineStartMs, content.lineEndMs)
        val ordered: List<Pair<DuetSectionId, MainLineData>>
        if (second == null) {
            ordered = listOf(primaryId to primary)
        } else {
            val secondId =
                DuetSectionId(content.trackGeneration, second.lineStartMs, second.lineEndMs)
            val byId = mapOf(primaryId to primary, secondId to second)
            ordered = if (primaryId == secondId) {
                listOf(primaryId to primary, secondId to second)
            } else {
                // Positional slots: continuing lines keep their slot, the
                // newcomer inherits the vacated one. Line 3 takes line 1's
                // place above line 2 instead of appending below it.
                assignDuetSlots(listOf(primaryId, secondId), lastOrderedIds)
                    .map { it to byId.getValue(it) }
            }
        }
        lastOrderedIds = ordered.map { it.first }
        val orderedIds = ordered.map { it.first }
        val duetEnded = shouldRecenterAfterDuet(wasDuet, orderedIds.size)
        if (duetEnded) {
            clearSectionAnchors()
            lastOrderedIds = orderedIds
        }
        forcedHits.clear()
        sectionPresentation.keys.retainAll(orderedIds.toSet())
        // Anchor continuity: only a new track invalidates line coordinates.
        // Metadata on/off publication changes the lyric area, not section
        // identity, so anchors survive and placement re-solves against the
        // new area; a full swap (nothing continues) re-centers through the
        // slot helper on the last block center.
        if (content.trackGeneration != anchorGeneration) {
            sectionTops = emptyMap()
            sectionPresentation.clear()
            sectionScaleCommit.clear()
            lastBlockCenter = null
            anchorGeneration = content.trackGeneration
        }
        // A lone line with no anchor is a fresh solo and centers; everything
        // else (chain solo included) holds its slot through the helper.
        // Landscape anchored sections cap secondary rows at one line each so
        // the pair fits without shrinking the survivor; fresh solos and
        // portrait keep legacy two-line secondaries. Participation comes
        // from the episode flag, not anchor presence: anchors appear after
        // the first build, so deriving policy from them flips a solo's
        // sizing policy on its second rebuild.
        val episodeHadDuet = episodeDuetGenerations.contains(content.trackGeneration)
        if (second != null) episodeDuetGenerations.add(content.trackGeneration)
        if (episodeDuetGenerations.size > 8) episodeDuetGenerations.clear()
        val freshSolo = orderedIds.size == 1 && (!episodeHadDuet || duetEnded)
        val secondaryCap = duetSecondaryLineCap(!freshSolo, isSideStep())
        var primaryVisualIndex = 0
        val built = ordered.mapIndexed { index, (_, data) ->
            if (data.lineStartMs == content.lineStartMs &&
                data.lineEndMs == content.lineEndMs &&
                data.text == content.original
            ) {
                primaryVisualIndex = index
            }
            buildLyricBlock(data, index, secondaryCap)
        }.toMutableList()
        val hasMetadata = metadataRows.isNotEmpty()
        val area = lyricAreaBounds(hasMetadata)
        val areaHeight = (area.second - area.first).coerceAtLeast(0f)
        // Shared fit: every section draws at one common scale, sized by the
        // combined stack against the whole lyric area. A tall section never
        // pays for the pair alone, sections keep matching glyph sizes, and
        // nobody shrinks unless the combined content genuinely exceeds the
        // canvas. A lone section is the degenerate case of the same formula.
        fun sectionStackHeight(triple: Triple<OriginalLayout, List<Row>, BlockDrawData>): Float {
            var total = 0f
            for (row in triple.second) total += row.height + row.gapBefore
            return total
        }
        val sectionTotals = built.map(::sectionStackHeight)
        val wrappedShared = resolveSharedDuetScale(sectionTotals.sum(), areaHeight)
        // Unwrap-on-floor policy (landscape only): a section that actually
        // shrinks (the shared fit drops below full size) presents one line
        // per row instead of several wrapped rows — but only when the
        // single-line form stays within tolerance of the wrapped scale, so
        // wide lines that would unwrap into a far tinier line keep their
        // wrap. Portrait keeps the wrapped stack: the narrow frame
        // width-binds every single line, so unwrapping there only shrinks
        // the section below its wrapped form. A committed decision
        // reproduces its recorded presentation exactly on every rebuild —
        // including rebuilding the single-line form — so the section cannot
        // flip back to wrapped after its anchor commits.
        val unwrappedSections = HashSet<Int>()
        val drawAvailableWidth = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
        val unwrapAllowed = isSideStep()
        built.forEachIndexed { index, triple ->
            val id = orderedIds[index]
            val recorded = sectionPresentation[id]
            if (unwrapAllowed && recorded != null && recorded.first == orderedIds.size &&
                sectionTops.containsKey(id)
            ) {
                if (recorded.second) {
                    built[index] = buildLyricBlock(ordered[index].second, index, 1, forceSingleLine = true)
                    unwrappedSections.add(index)
                }
                return@forEachIndexed
            }
            if (!unwrapAllowed || triple.first.lineCount <= 1 || wrappedShared >= 1f) {
                return@forEachIndexed
            }
            val candidate = buildLyricBlock(ordered[index].second, index, 1, forceSingleLine = true)
            val candidateCombined = sectionTotals.sum() - sectionTotals[index] +
                sectionStackHeight(candidate)
            var widest = 0f
            candidate.first.lines.forEach { line ->
                val (visualLeft, visualRight) = visualExtents(line.text, originalPaint, line.width)
                widest = maxOf(widest, visualRight - visualLeft)
            }
            for (row in candidate.second) {
                for (line in row.lines) {
                    val (visualLeft, visualRight) = visualExtents(line.text, row.paint, line.width)
                    widest = maxOf(widest, visualRight - visualLeft)
                }
            }
            val candidateScale = minOf(
                resolveSharedDuetScale(candidateCombined, areaHeight),
                resolveVisualWidthFitScale(widest, drawAvailableWidth)
            )
            val wantUnwrapped = shouldUnwrapShrunkSection(wrappedShared, candidateScale)
            sectionPresentation[id] = orderedIds.size to wantUnwrapped
            if (wantUnwrapped) {
                built[index] = candidate
                unwrappedSections.add(index)
            }
        }
        val rows = ArrayList<Row>(8)
        rows += metadataRows
        built.forEach { rows += it.second }
        val blocks = built.map { it.third }
        val layouts = built.map { it.first }
        val finalTotals = built.map(::sectionStackHeight)
        val sectionScales = HashMap<Int, Float>()
        // Conveyor scale: anchored sections keep their committed scale
        // through partner swaps (the continuing line never resizes).
        // Newcomers size themselves into the leftover area at an exact fit;
        // a fresh formation shares one exact scale so the pair keeps
        // matching glyph sizes; only when a newcomer cannot fit the leftover
        // at all does the whole set rescale to the uniform shared fit.
        fun widthCappedScale(index: Int, base: Float): Float {
            var widest = 0f
            built[index].first.lines.forEach { line ->
                val (visualLeft, visualRight) = visualExtents(line.text, originalPaint, line.width)
                widest = maxOf(widest, visualRight - visualLeft)
            }
            for (row in built[index].second) {
                for (line in row.lines) {
                    val (visualLeft, visualRight) = visualExtents(line.text, row.paint, line.width)
                    widest = maxOf(widest, visualRight - visualLeft)
                }
            }
            return minOf(base, resolveVisualWidthFitScale(widest, drawAvailableWidth))
        }
        fun commitScale(index: Int, base: Float) {
            val scale = widthCappedScale(index, base)
            sectionScales[index] = scale
            sectionScaleCommit[orderedIds[index]] = scale
        }
        val continuing = built.indices.filter { index ->
            sectionTops.containsKey(orderedIds[index]) &&
                sectionScaleCommit.containsKey(orderedIds[index])
        }
        continuing.forEach { index ->
            sectionScales[index] = widthCappedScale(
                index,
                sectionScaleCommit.getValue(orderedIds[index])
            )
        }
        val newcomers = built.indices.filter { index -> index !in continuing }
        val committedDrawn = continuing.sumOf { index ->
            (finalTotals[index] * sectionScaleCommit.getValue(orderedIds[index])).toDouble()
        }
        val leftover = (areaHeight - committedDrawn).toFloat().coerceAtLeast(0f)
        val leftoverFits = HashMap<Int, Float>()
        var newcomerOverflow = false
        for (index in newcomers) {
            val fit = resolveSharedDuetScale(finalTotals[index], leftover)
            if (finalTotals[index] > leftover) {
                newcomerOverflow = true
                break
            }
            leftoverFits[index] = fit
        }
        when {
            // Fully continuing set: the committed scales stand untouched.
            newcomers.isEmpty() -> Unit
            // Formation with no committed sections: one shared exact scale.
            continuing.isEmpty() -> {
                val shared = resolveSharedDuetScale(finalTotals.sum(), areaHeight)
                built.indices.forEach { commitScale(it, shared) }
            }
            // A newcomer cannot fit the leftover: rescale the whole set to
            // the uniform shared fit.
            newcomerOverflow -> {
                val shared = resolveSharedDuetScale(finalTotals.sum(), areaHeight)
                built.indices.forEach { commitScale(it, shared) }
            }
            // Survivor-first: continuing sections keep their scales; the
            // newcomer takes what remains.
            else -> newcomers.forEach { index ->
                commitScale(index, leftoverFits.getValue(index))
            }
        }
        sectionScaleCommit.keys.retainAll(orderedIds.toSet())
        built.forEachIndexed { index, triple ->
            if (sectionScales[index] == null) sectionScales[index] = 1f
        }
        val topsByBlock: Map<Int, Float>?
        if (freshSolo) {
            topsByBlock = null
        } else {
            // Scaled adjacency: stack against drawn edges so a shrunk section
            // leaves no gap and a tall newcomer lands in the freed slot.
            val scaled = LinkedHashMap<DuetSectionId, Float>()
            for ((index, id) in orderedIds.withIndex()) {
                var total = 0f
                for (row in built[index].second) total += row.height + row.gapBefore
                scaled[id] = total * (sectionScales[index] ?: 1f)
            }
            // First render of this section set (no anchors yet) centers the
            // stack at the area center deterministically. A stale
            // lastBlockCenter from the previous line or the song-change intro
            // would misplace the whole stack and only settle on the next
            // line change — exactly the first-render overlap.
            val tops = LinkedHashMap(placeDuetSectionTops(
                orderedIds,
                scaled,
                (area.first + area.second) / 2f,
                sectionTops,
                if (sectionTops.isEmpty()) null else lastBlockCenter
            ))
            // Drawn-space chaining with consistency-checked anchors. The
            // first section owns its anchored top (the conveyor slot). Every
            // later section keeps its anchor only while it agrees with the
            // previous section's current drawn bottom; a mismatch means the
            // survivor's height changed after this anchor was committed
            // (e.g. the entrance burst where the primary's secondary lanes
            // publish late and grow the section), and the dependent section
            // re-chains instead of overlapping or leaving a gap.
            var expectedTop = tops[orderedIds[0]]
            var prevBottom = expectedTop?.plus(scaled[orderedIds[0]] ?: 0f)
            for (index in 1 until orderedIds.size) {
                val id = orderedIds[index]
                val anchored = sectionTops[id]
                val chained = prevBottom
                val top = when {
                    anchored != null && chained != null &&
                        kotlin.math.abs(anchored - chained) <= ANCHOR_CONSISTENCY_PX -> anchored
                    chained != null -> chained
                    else -> anchored ?: (area.first + area.second) / 2f
                }
                tops[id] = top
                expectedTop = top
                prevBottom = top + (scaled[id] ?: 0f)
            }
            topsByBlock = tops.mapKeys { (id, _) -> orderedIds.indexOf(id) }
        }
        val positioned = positionRows(rows, layouts, topsByBlock)
        // Anchored sections define their own placement; the free vertical
        // anchor only moves a fresh lone section, preserving legacy behavior.
        // Anchored blocks are then clamped on their drawn bounds into the
        // lyric area as a whole, so the block re-pins instead of running
        // off-screen. The survivor moves only when clipping is unavoidable.
        val drawnBeforeClamp = if (topsByBlock != null) {
            drawnSpanBounds(positioned, topsByBlock, sectionScales)
        } else {
            null
        }
        val clampShift = if (drawnBeforeClamp != null) {
            resolveBlockClampShift(
                drawnBeforeClamp.first,
                drawnBeforeClamp.second,
                area.first,
                area.second
            )
        } else {
            0f
        }
        val finalPositioned = if (topsByBlock == null) {
            applyVerticalBias(positioned)
        } else if (clampShift == 0f) {
            positioned
        } else {
            positioned.map { it.copy(baseline = it.baseline + clampShift) }
        }
        // Anchors are measured from what is actually drawn (post ruby
        // clearance), so the next pass continues from truth, not intent.
        val measured = measuredSectionTops(finalPositioned)
        val committedTops = LinkedHashMap<DuetSectionId, Float>()
        for ((block, top) in measured) {
            if (block >= 0 && block < orderedIds.size) committedTops[orderedIds[block]] = top
        }
        // Full-swap fallback centers on drawn truth (post clamp shift).
        val blockCenter = if (drawnBeforeClamp != null) {
            (drawnBeforeClamp.first + clampShift + drawnBeforeClamp.second + clampShift) / 2f
        } else {
            var spanTop = Float.POSITIVE_INFINITY
            var spanBottom = Float.NEGATIVE_INFINITY
            for (placed in finalPositioned) {
                if (placed.row.kind == RowKind.METADATA) continue
                val rowTop = placed.baseline + placed.row.paint.fontMetrics.ascent
                val rowBottom = rowTop + placed.row.height
                if (rowTop < spanTop) spanTop = rowTop
                if (rowBottom > spanBottom) spanBottom = rowBottom
            }
            if (spanTop.isFinite() && spanBottom.isFinite() && spanBottom > spanTop) {
                (spanTop + spanBottom) / 2f
            } else {
                null
            }
        }
        // TEMPORARY full layout-input trace (remove after device capture):
        // every wrap/size input per section (breaks, texts, words, offsets,
        // groups, secondary rows, base size, fit scale, tops, transitions).
        if (HookLogger.traceEnabled) {
            val inTransition = exitSnapshot != null
            val baseSp = originalPaint.textSize / scaledDensity
            val summary = ordered.mapIndexed { index, (id, data) ->
                val hit = forcedHits.getOrNull(index) ?: false
                val nullOffsets = data.words.count { transportedWordOffset(data.text, it) == null }
                var sec = 0
                if (data.romanized.isNotBlank()) sec += 1
                if (data.translated.isNotBlank()) sec += 2
                if (data.ruby.isNotEmpty()) sec += 4
                val top = committedTops[id]?.roundToInt() ?: -1
                val fit = sectionScales[index] ?: 1f
                val sectionRows = built[index].second
                val rom = sectionRows.filter { it.kind == RowKind.ROMANIZED }.sumOf { it.lines.size }
                val trans = sectionRows.filter { it.kind == RowKind.TRANSLATED }.sumOf { it.lines.size }
                "${id.lineStartMs}..${id.lineEndMs}:${built[index].first.lineCount}:" +
                    "${data.text.length}:${data.words.size}:$nullOffsets:" +
                    "${data.text.hashCode()}:${if (hit) "f" else "-"}:" +
                    "${if (index in unwrappedSections) "u" else "-"}:$top:$sec:" +
                    "${if (inTransition) "t" else "-"}:$fit:$rom:$trans:" +
                    "${data.layoutGroups.size}"
            }
            HookLogger.i(
                "AodDuetLayout",
                "DuetLayout gen=${content.trackGeneration} sections=$summary " +
                    "base=${"%.1f".format(baseSp)} limit=${content.lyricLineLimit} " +
                    "size=${content.textSizeMode}/${content.textSizeCustom} " +
                    "landscape=${landscapeTextScale}@step=${orientationStep} " +
                    "font=${content.fontFamily}/${content.weight} " +
                    "md=${content.metadata.length}:${content.metadata.hashCode()} " +
                    "pal=${content.palette.hashCode()} bri=${content.secondaryTextBright} " +
                    "view=${System.identityHashCode(this)} frame=${width}x${height} " +
                    "anchors=${sectionTops.size} center=$lastBlockCenter meta=$hasMetadata"
            )
        }
        // Precomputed per-section draw transforms: alignment-aware horizontal
        // pivot (START/END/CENTER preserves the alignment edge through the
        // shrink) plus the vertical top. Drawing consumes these; onDraw
        // never rescans extents.
        val sectionTopsForPivot = measuredSectionTops(finalPositioned)
        val sectionPivotsX = HashMap<Int, Float>()
        built.forEachIndexed { index, triple ->
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            val block = triple.third
            block.originalLayout.lines.forEach { line ->
                if (line.startX < left) left = line.startX
                if (line.startX + line.width > right) right = line.startX + line.width
            }
            for (row in triple.second) {
                for (line in row.lines) {
                    if (line.startX < left) left = line.startX
                    if (line.startX + line.width > right) right = line.startX + line.width
                }
            }
            sectionPivotsX[index] = if (left.isFinite() && right > left) {
                resolveDuetSectionPivotX(block.alignment, left, right)
            } else {
                layoutFrameWidth() / 2f
            }
        }
        return Triple(
            LayoutState(
                finalPositioned,
                built[primaryVisualIndex].first,
                blocks,
                sectionScales = sectionScales,
                sectionIds = orderedIds,
                sectionPivotsX = sectionPivotsX
            ),
            committedTops,
            blockCenter
        )
    }

    /** Section tops measured from drawn rows, keyed by visual block index. */
    private fun measuredSectionTops(positioned: List<PositionedRow>): Map<Int, Float> {
        val tops = HashMap<Int, Float>()
        for (placed in positioned) {
            if (placed.row.kind == RowKind.METADATA) continue
            val top = placed.baseline + placed.row.paint.fontMetrics.ascent - placed.row.gapBefore
            val block = placed.row.blockIndex
            val current = tops[block]
            if (current == null || top < current) tops[block] = top
        }
        return tops
    }

    /**
     * Drawn span of lyric rows: each section mapped through its fit scale
     * about its top, which is how the canvas actually draws it. Sections at
     * full size contribute their layout bounds unchanged.
     */
    private fun drawnSpanBounds(
        positioned: List<PositionedRow>,
        topsByBlock: Map<Int, Float>,
        scales: Map<Int, Float>
    ): Pair<Float, Float>? {
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (placed in positioned) {
            if (placed.row.kind == RowKind.METADATA) continue
            val scale = scales[placed.row.blockIndex] ?: 1f
            var rowTop = placed.baseline + placed.row.paint.fontMetrics.ascent
            var rowBottom = rowTop + placed.row.height
            if (scale != 1f) {
                val origin = topsByBlock[placed.row.blockIndex] ?: rowTop
                rowTop = origin + scale * (rowTop - origin)
                rowBottom = origin + scale * (rowBottom - origin)
            }
            if (rowTop < top) top = rowTop
            if (rowBottom > bottom) bottom = rowBottom
        }
        if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return null
        return top to bottom
    }

    /**
     * Lays out one lyric section (original plus its own secondary rows) with
     * the existing builders by scoping content, alignment, and direction to
     * that line, so concurrent sections share wrapping, ruby, and secondary
     * logic with no fork. Returns the wrapped layout, its rows, and the draw
     * data the frame loop needs beyond shared paints and global modes.
     */
    private fun buildLyricBlock(
        line: MainLineData,
        blockIndex: Int,
        maxSecondaryLines: Int = MAX_SECONDARY_LINES,
        forceSingleLine: Boolean = false
    ): Triple<OriginalLayout, List<Row>, BlockDrawData> {
        val savedContent = content
        val savedAlignment = alignment
        val savedDirection = textDirection
        content = content.copy(
            original = line.text,
            romanized = line.romanized,
            translated = line.translated,
            alignedRight = line.alignedRight,
            lineStartMs = line.lineStartMs,
            lineEndMs = line.lineEndMs,
            words = line.words,
            ruby = line.ruby,
            layoutGroups = line.layoutGroups,
            secondLine = null
        )
        val direction = resolvedAodTextDirection(line.text, line.words)
        textDirection = direction
        alignment = when (
            resolvedAodPhysicalAlignment(content.alignmentMode, line.alignedRight, direction)
        ) {
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> Alignment.START
        }
        try {
            val lineId = DuetSectionId(
                content.trackGeneration,
                content.lineStartMs,
                content.lineEndMs
            )
            val originalLayout = buildOriginalLayout(lineId, forceSingleLine)
            val rows = ArrayList<Row>(3)
            if (line.text.isNotBlank()) {
                val metrics = originalPaint.fontMetrics
                val lineHeight = metrics.descent - metrics.ascent + 2f * density
                rows += Row(
                    RowKind.ORIGINAL,
                    line.text,
                    originalPaint,
                    originalRowHeight(
                        lineHeight,
                        originalLayout.lineCount,
                        originalLayout.rubyHeight,
                        originalLayout.lineGap
                    ),
                    8f * density,
                    emptyList(),
                    lineHeight,
                    blockIndex
                )
            }
            val showReading = content.secondaryMode == "Transliteration" ||
                content.secondaryMode == "Both"
            val showTranslation = content.secondaryMode == "Translation" ||
                content.secondaryMode == "Both"
            if (showReading && line.romanized.isNotBlank()) {
                val lines = transliterationLines(originalLayout, maxSecondaryLines, forceSingleLine)
                    ?: wrapSecondaryText(
                        line.romanized,
                        romanizedPaint,
                        originalLayout.lineCount,
                        maxSecondaryLines,
                        forceSingleLine
                    )
                rows += rowWithLines(
                    RowKind.ROMANIZED, line.romanized, romanizedPaint, 2f * density, lines,
                    blockIndex
                )
            }
            if (showTranslation && line.translated.isNotBlank()) {
                rows += rowWithLines(
                    RowKind.TRANSLATED,
                    line.translated,
                    translatedPaint,
                    2f * density,
                    wrapSecondaryText(
                        line.translated,
                        translatedPaint,
                        originalLayout.lineCount,
                        maxSecondaryLines,
                        forceSingleLine
                    ),
                    blockIndex
                )
            }
        return Triple(
                originalLayout,
                rows,
                BlockDrawData(
                    originalLayout,
                    line.words,
                    line.lineStartMs,
                    line.lineEndMs,
                    alignment,
                    direction
                )
            )
        } finally {
            content = savedContent
            alignment = savedAlignment
            textDirection = savedDirection
        }
    }

    /**
     * Free vertical anchor for the full-screen canvas: 0 puts the block at the
     * top padding, 1 at the bottom padding, 0.5 keeps legacy placement exactly.
     * Null restores legacy alignment behavior.
     */
    fun setVerticalBias(bias: Float?) {
        val normalized = bias?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (verticalBias == normalized) return
        verticalBias = normalized
        clearSectionAnchors()
        rebuildLayout()
        invalidate()
    }

    /**
     * Landscape render step. Layout runs in the logical long-axis frame and
     * one rigid transform in `onDraw` maps it onto the fullscreen portrait
     * view, so longer lines survive with no clipping and no oversized child.
     */
    fun setOrientationStep(step: Int) {
        if (step != 0 && step != 90 && step != 180 && step != 270) return
        if (orientationStep == step) return
        orientationStep = step
        sizePaints()
        currentRenderStyle = captureRenderStyle()
        clearSectionAnchors()
        rebuildLayout()
        invalidate()
    }

    private fun applyVerticalBias(positioned: List<PositionedRow>): List<PositionedRow> {
        val bias = verticalBias ?: return positioned
        if (positioned.isEmpty()) return positioned
        var spanTop = Float.POSITIVE_INFINITY
        var spanBottom = Float.NEGATIVE_INFINITY
        for (row in positioned) {
            val ascent = row.row.paint.fontMetrics.ascent
            val descent = row.row.paint.fontMetrics.descent
            if (row.baseline + ascent < spanTop) spanTop = row.baseline + ascent
            if (row.baseline + descent > spanBottom) spanBottom = row.baseline + descent
        }
        val shift = resolveVerticalBiasShift(
            spanTop = spanTop,
            spanBottom = spanBottom,
            topBound = logicalPadTop,
            bottomBound = (layoutFrameHeight() - logicalPadBottom),
            bias = bias
        )
        if (shift == 0f) return positioned
        return positioned.map { it.copy(baseline = it.baseline + shift) }
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
            top.coerceIn(0f, layoutFrameHeight().toFloat()),
            bottom.coerceIn(0f, layoutFrameHeight().toFloat())
        )
    }

    private fun row(kind: RowKind, text: String, paint: Paint, gap: Float, allowWrap: Boolean = true): Row {
        val lines = if (kind == RowKind.METADATA) {
            metadataLineTexts(text).map {
                textLine(it, paint.measureText(it), paint, alignmentFor(kind))
            }
        } else if (allowWrap) {
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
        lines: List<TextLine>,
        blockIndex: Int = 0
    ): Row {
        val metrics = paint.fontMetrics
        val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
        return Row(kind, text, paint, lineHeight * lines.size, gap, lines, lineHeight, blockIndex)
    }

    /**
     * Stacks lyric rows inside the padded frame. A null [sectionTops] keeps
     * the legacy placement exactly (centered, top-aligned, or metadata
     * stacked lone section). A provided map pins each visual section at its
     * top so continuing lines never move; the caller derives those tops from
     * slot arrival order. [blockLayouts] runs in visual section order,
     * index-aligned with [Row.blockIndex].
     */
    private fun positionRows(
        rows: List<Row>,
        blockLayouts: List<OriginalLayout>,
        sectionTops: Map<Int, Float>?
    ): List<PositionedRow> {
        val positioned = ArrayList<PositionedRow>(rows.size)
        val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
        if (metadata != null) {
            val anchor = when (content.metadataAnchor) {
                "bottom" -> "bottom"
                else -> "top"
            }
            val metadataBounds = metadataLayoutBounds(
                anchor,
                layoutFrameHeight().toFloat(),
                logicalPadTop,
                logicalPadBottom.toFloat(),
                metadata.paint.fontMetrics.ascent,
                metadata.paint.fontMetrics.descent,
                10f * density,
                (metadata.lines.size - 1).coerceAtLeast(0) * metadata.lineHeight
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            if (sectionTops == null) {
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
                placeSectionsAtTops(
                    positioned,
                    lyricRows,
                    sectionTops,
                    topPin = metadataBounds.lyricStart
                )
            }
        } else {
            if (sectionTops == null) {
                val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
                val topPadding = logicalPadTop
                val bottomPadding = (layoutFrameHeight() - logicalPadBottom)
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
            } else {
                placeSectionsAtTops(
                    positioned,
                    rows.filter { it.kind != RowKind.METADATA },
                    sectionTops,
                    topPin = logicalPadTop
                )
            }
        }
        // Ruby clearance runs per lyric section: the top section keeps its
        // legacy top-padding bound, while lower sections clear their own top
        // edge so their readings cannot overlap the section above them.
        // Block layouts run in visual section order, index-aligned with
        // Row.blockIndex.
        var result: List<PositionedRow> = positioned
        val lyricGroups = positioned
            .filter { it.row.kind != RowKind.METADATA }
            .groupBy { it.row.blockIndex }
        for ((blockIndex, group) in lyricGroups) {
            val blockLayout = blockLayouts.getOrNull(blockIndex)
                ?: blockLayouts.firstOrNull() ?: continue
            val firstOriginal = group.firstOrNull { it.row.kind == RowKind.ORIGINAL }
                ?: continue
            val firstLine = blockLayout.lines.firstOrNull() ?: continue
            if (firstLine.rubyHeight <= 0f) continue
            val bound = if (blockIndex == 0) {
                logicalPadTop
            } else {
                group.minOf { it.baseline + it.row.paint.fontMetrics.ascent }
            }
            val firstBaseBaseline = firstOriginal.baseline + firstLine.rubyHeight
            val top = rubyClipTop(
                firstBaseBaseline,
                originalPaint.fontMetrics.ascent,
                firstLine.rubyHeight
            )
            val shift = rubyTopShift(top, bound)
            if (shift == 0f) continue
            val groupSet = group.toSet()
            result = result.map {
                if (it in groupSet) it.copy(baseline = it.baseline + shift) else it
            }
        }
        return result
    }

    /**
     * Lays lyric sections top-down from their slot tops. Sections stay in
     * visual order, so a newcomer lands adjacently without shifting the
     * survivor. Unknown blocks (defensive only: every visual block arrives
     * with a top) continue after the lowest placed row.
     */
    private fun placeSectionsAtTops(
        positioned: ArrayList<PositionedRow>,
        lyricRows: List<Row>,
        sectionTops: Map<Int, Float>,
        topPin: Float
    ) {
        var cursor: Float? = null
        for ((block, group) in lyricRows.groupBy { it.blockIndex }.toSortedMap()) {
            var top = sectionTops[block] ?: cursor ?: topPin
            for (row in group) {
                top += row.gapBefore
                positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                top += row.height
            }
            cursor = maxOf(cursor ?: top, top)
        }
        positioned.sortBy { it.baseline }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float, block: BlockDrawData) {
        val originalLayout = block.originalLayout
        val lines = originalLayout.lines
        if (!originalLayout.timed) {
            val progress = if (content.animationMode == "Minimal") 1f else blockProgress(block)
            if (resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode) ==
                "Top to bottom"
            ) {
                drawUntimedTopToBottom(canvas, baseline, progress, block)
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
                        false,
                        block.textDirection
                    )
                    if (clipSave != -1) canvas.restoreToCount(clipSave)
                    precedingRuby += line.rubyHeight
                    lineIndex++
                }
            }
            return
        }
        val position = projectedPosition()
        // TEMPORARY wrapped-line crossing diagnostic (remove after capture):
        // logs once per wrapped-row change per section — transition state,
        // section scale, and the crossing indices — to isolate the
        // wrapped-line-crossing blink from the duet-join dissolve.
        if (HookLogger.traceEnabled && lines.size > 1) {
            val activeIndex = lines.indexOfFirst { line ->
                line.words.any { position >= it.word.startMs && position < it.word.endMs }
            }.takeIf { it >= 0 } ?: lines.indexOfFirst { line ->
                line.words.any { position < it.word.endMs }
            }
            if (activeIndex >= 0) {
                val key = block.lineStartMs
                if (crossingTracker[key] != activeIndex) {
                    crossingTracker[key] = activeIndex
                    HookLogger.i(
                        "AodDuetCrossing",
                        "crossing block=${block.lineStartMs} row=$activeIndex/${lines.size} " +
                            "pos=$position exit=${exitSnapshot != null} " +
                            "scale=${layout.sectionScales[layout.blocks.indexOf(block)] ?: 1f}"
                    )
                }
            }
        }
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
            var precedingWidth = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val width = placed.width
                val wordX = timedWordDrawX(
                    line.startX,
                    line.width,
                    precedingWidth,
                    width,
                    block.textDirection
                )
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
                drawDirectionalText(
                    canvas,
                    word.text,
                    wordX,
                    wordBaseline + y,
                    originalPaint,
                    resolvedAodTextDirection(word.text, listOf(word))
                )
                if (active && content.animationMode == "Gradient") {
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    applySoftSweep(
                        originalPaint,
                        resolvedPalette.sungText,
                        origin = wordX,
                        progress = progress,
                        extent = width,
                        vertical = false,
                        direction = block.textDirection
                    )
                    drawDirectionalText(
                        canvas,
                        word.text,
                        wordX,
                        wordBaseline + y,
                        originalPaint,
                        resolvedAodTextDirection(word.text, listOf(word))
                    )
                    originalPaint.shader = null
                }
                originalPaint.clearShadowLayer()
                canvas.restore()
                precedingWidth += width + placed.gapAfter
                wordIndex++
            }
            if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawUntimedTopToBottom(
        canvas: Canvas,
        baseline: Float,
        progress: Float,
        block: BlockDrawData
    ) {
        val originalLayout = block.originalLayout
        val lines = originalLayout.lines
        val firstLine = lines.firstOrNull()
        val firstLineBaseline = firstLine?.let {
            originalLineBaseline(
                baseline,
                0,
                originalLayout.lineHeight,
                0f,
                it.rubyHeight,
                originalLayout.lineGap
            )
        } ?: baseline
        val blockTop = max(
            logicalPadTop,
            rubyClipTop(firstLineBaseline, originalPaint.fontMetrics.ascent, firstLine?.rubyHeight ?: 0f)
        )
        val blockHeight = originalRowHeight(
            originalLayout.lineHeight,
            lines.size,
            originalLayout.rubyHeight,
            originalLayout.lineGap
        )
        clearBlockSweepShaders()
        drawUntimedLines(canvas, baseline, false, progress, block)
        drawOriginalRubyRows(canvas, baseline, block, bright = false)
        applyBlockSweepShaders(
            origin = blockTop,
            progress = progress,
            extent = blockHeight
        )
        drawUntimedLines(canvas, baseline, true, progress, block)
        drawOriginalRubyRows(canvas, baseline, block, bright = true)
        clearBlockSweepShaders()
    }

    private fun drawContinuousLineFill(
        canvas: Canvas,
        baseline: Float,
        progress: Float,
        block: BlockDrawData
    ) {
        val originalLayout = block.originalLayout
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < originalLayout.lines.size) {
            val line = originalLayout.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            drawLineFill(
                canvas,
                line,
                lineBaseline,
                originalLayout.continuousFill(progress, lineIndex),
                false,
                block.textDirection
            )
            if (clipSave != -1) canvas.restoreToCount(clipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawUntimedLines(
        canvas: Canvas,
        baseline: Float,
        bright: Boolean,
        progress: Float,
        block: BlockDrawData
    ) {
        val originalLayout = block.originalLayout
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < originalLayout.lines.size) {
            val line = originalLayout.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
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
            drawOriginalText(canvas, line, lineBaseline, block.textDirection)
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
            -Float.MAX_VALUE,
            max(logicalPadTop, rubyClipTop(baseBaseline, originalPaint.fontMetrics.ascent, rubyHeight)),
            Float.MAX_VALUE,
            (layoutFrameHeight() - logicalPadBottom)
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

    /** Break cache by line id: first layout wins, later builds reuse its ranges. */
    private var wrapCache = HashMap<DuetSectionId, FrozenLineWrap>()
    /** TEMPORARY: per-section frozen-break hit flags for the current build. */
    private var forcedHits = ArrayList<Boolean>()

    private fun buildOriginalLayout(
        lineId: DuetSectionId?,
        forceSingleLine: Boolean = false
    ): OriginalLayout {
        // Metadata placeholders keep explicit title/artist rows even in Clip or landscape mode.
        if (isSongChangeMetadataPlaceholder(content.original, content.metadata,
                content.lineStartMs, content.lineEndMs, content.words.any { it.endMs > it.startMs })) {
            val lines = metadataLineTexts(content.metadata).map {
                originalLine(it, originalPaint.measureText(it), null, null)
            }
            val metrics = originalPaint.fontMetrics
            return OriginalLayout(lines, metrics.descent - metrics.ascent + 2f * density,
                ORIGINAL_LINE_GAP_DP * density, false)
        }
        val words = coalesceRubyWords(
            content.original,
            content.words.filter { it.text.isNotBlank() },
            content.ruby
        )
        val available = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
        val forced = if (lineId != null && !forceSingleLine && content.overflowMode == "Wrap" &&
            layoutFrameWidth() > 0 && layoutFrameHeight() > 0 && available > 1f
        ) {
            // A freeze from a smaller frame (pre-layout, portrait step,
            // metadata-present area) locks a bloated wrap in forever; repair
            // it once by re-wrapping under the canonical minimal-count policy.
            validFrozenWrap(wrapCache[lineId], content.original)
                ?.takeIf { frozen ->
                    frozenWrapIsMinimal(
                        frozen,
                        measureLine = { range ->
                            originalPaint.measureText(
                                content.original.substring(range.first, range.last + 1)
                            )
                        },
                        gap = 8f * density,
                        available = available
                    )
                }
        } else {
            null
        }
        forcedHits.add(forced != null)
        val lines = when {
            // Explicit single-line presentation: every timed word stays an
            // independent timed word on one visual line — no bucket
            // assignment, no frozen ranges, no wrap-cache traffic. Ruby and
            // source offsets survive because the line spans the full text.
            forceSingleLine && words.isNotEmpty() ->
                layoutWordLines(words, 8f * density, forceSingleLine = true)
            forceSingleLine && content.original.isNotEmpty() -> listOf(
                originalLine(
                    content.original,
                    originalPaint.measureText(content.original),
                    0,
                    content.original.length
                )
            )
            words.isEmpty() ->
                if (content.adaptiveSectioning) layoutTextByGroups(forced)
                else wrapText(content.original, originalPaint, forced)
            else -> layoutWordLines(words, 8f * density, forced)
        }
        val metrics = originalPaint.fontMetrics
        val laidOut = OriginalLayout(
            assignRuby(lines),
            metrics.descent - metrics.ascent + 2f * density,
            ORIGINAL_LINE_GAP_DP * density,
            words.isNotEmpty()
        )
        if (lineId != null && !forceSingleLine && content.overflowMode == "Wrap" &&
            layoutFrameWidth() > 0 && layoutFrameHeight() > 0 && available > 1f
        ) {
            frozenRangesFrom(laidOut.lines.map { it.charStart to it.charEnd }, content.original)?.let {
                if (wrapCache.size >= 256) wrapCache.clear()
                wrapCache[lineId] = it
            }
        }
        return laidOut
    }

    private fun layoutTextByGroups(forced: List<IntRange>? = null): List<OriginalLine> {
        if (forced != null) {
            val synthetic = forced.map { range ->
                val slice = content.original.substring(range.first, range.last + 1)
                AodCanvasWord(slice, "", 0L, 0L, false, range.first, range.last + 1)
            }
            return layoutWordLines(synthetic, 8f * density, forced)
        }
        val ranges = coveredLayoutRanges(content.original, content.layoutGroups)
        if (ranges.isEmpty()) return wrapText(content.original, originalPaint)
        val synthetic = ranges.mapIndexed { index, range ->
            val nextStart = ranges.getOrNull(index + 1)?.first ?: range.last + 1
            val boundaryAfter = index < ranges.lastIndex && content.original
                .substring(range.last + 1, nextStart).any { it.isWhitespace() }
            AodCanvasWord(
                content.original.substring(range.first, range.last + 1),
                "",
                0L,
                0L,
                boundaryAfter,
                range.first,
                range.last + 1
            )
        }
        return layoutWordLines(synthetic, 8f * density)
    }

    private fun layoutWordLines(
        words: List<AodCanvasWord>,
        gap: Float,
        forced: List<IntRange>? = null,
        forceSingleLine: Boolean = false
    ): List<OriginalLine> {
        val available = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
        val maxLines = lyricLayoutLineLimit(words.size)
        val offsets = wordOffsets(words)
        val placed = words.mapIndexed { index, word ->
            val wordWidth = originalPaint.measureText(word.text)
            val gapAfter = if (index == words.lastIndex) {
                0f
            } else {
                authoredWordSeparator(content.original, word, words[index + 1])
                    ?.let(originalPaint::measureText)
                    ?: aodWordGapAfter(word.boundaryAfter, gap)
            }
            PlacedWord(word, wordWidth, gapAfter, offsets[index])
        }
        if (content.overflowMode != "Wrap" || forceSingleLine) {
            return listOf(wordLine(placed))
        }
        if (forced != null) {
            // Frozen breaks from the line's first layout: every word lands in
            // the range owning most of it, so resegmented publications keep
            // the sentence shape with fresh timings. Offset-less words fall
            // back to free layout; empty buckets drop (a degenerate
            // segmentation converges through the derived store below).
            val offsets = wordOffsets(words)
            if (offsets.all { it != null }) {
                val buckets = Array(forced.size) { mutableListOf<PlacedWord>() }
                for ((index, placedWord) in placed.withIndex()) {
                    buckets[majorityRangeIndex(forced, offsets[index]!!)] += placedWord
                }
                val lines = buckets.filter { it.isNotEmpty() }.map { wordLine(it) }
                if (lines.isNotEmpty() && lines.all { it.width <= available }) {
                    return lines
                }
            }
        }
        if (!content.adaptiveSectioning) {
            return legacyAttachedWordLineRanges(
                words,
                placed.map(PlacedWord::width),
                placed.map(PlacedWord::gapAfter),
                available,
                maxLines
            ).map { range ->
                val lineWords = range.map(placed::get)
                wordLine(lineWords)
            }
        }
        val groupIds = attachAodPunctuationGroups(
            words,
            lexicalGroupIds(offsets, content.layoutGroups)
        )
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
        // Include each chunk's trailing separator while packing so rendered
        // inter-chunk gaps cannot escape the padded drawable width.
        val chunkWidths = chunks.map { chunk ->
            // Keep the separator after each chunk while packing. The final
            // separator is removed by wordLine(), but retaining it here is
            // conservative and prevents a rendered inter-chunk gap from
            // escaping the padded drawable width.
            chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        }
        val lines = balancedChunkRanges(chunkWidths, available, maxLines).map { range ->
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

    private fun wrapText(
        text: String,
        paint: Paint,
        forced: List<IntRange>? = null
    ): List<OriginalLine> {
        if (text.isBlank()) return emptyList()
        val available = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
        if (forced != null) {
            val frozen = forced.map { range ->
                val slice = text.substring(range.first, range.last + 1)
                originalLine(slice, paint.measureText(slice), range.first, range.last + 1)
            }
            if (frozen.all { it.width <= available }) return frozen
        }
        if (content.overflowMode != "Wrap") {
            return listOf(originalLine(text, paint.measureText(text), 0, text.length))
        }
        val maxLines = lyricLayoutLineLimit()
        val lines = ArrayList<OriginalLine>(maxLines)
        var remaining = text
        var charStart = 0
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
            val line = remaining.take(count)
            lines += originalLine(line, paint.measureText(line), charStart, charStart + line.length)
            remaining = remaining.drop(count)
            charStart += count
        }
        return lines
    }

    private fun lyricLayoutLineLimit(wordCount: Int = content.words.size): Int =
        resolvedLyricLayoutLineLimit(
            content.lyricLineLimit,
            content.original.length,
            wordCount
        )

    private fun transliterationLines(
        originalLayout: OriginalLayout,
        maxLines: Int = MAX_SECONDARY_LINES,
        hardSingleLine: Boolean = false
    ): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val available = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
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
                gapAfter = if (nextSourceIndex != null && word.boundaryAfter) spaceWidth else 0f,
                startMs = word.startMs,
                endMs = word.endMs
            )
        }
        if (segments.isEmpty()) return null
        val mergedWidth = segments.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        // Single-line cap applies only when the merged line fits: genuinely
        // long readings keep two lines instead of clipping. Hard single-line
        // mode (unwrap-on-floor) drops the escape instead — the section
        // scale now handles width, and the timed segments stay intact on
        // the one line for word-accurate karaoke.
        val effectiveMax = when {
            hardSingleLine -> 1
            maxLines <= 1 && mergedWidth > available -> MAX_SECONDARY_LINES
            else -> maxLines.coerceAtLeast(1)
        }
        return secondaryTimedVisualRanges(
            segments,
            available,
            effectiveMax,
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

    private fun wrapSecondaryText(
        text: String,
        paint: Paint,
        preferredLines: Int,
        maxLines: Int = MAX_SECONDARY_LINES,
        hardSingleLine: Boolean = false
    ): List<TextLine> {
        // Hard single-line mode (unwrap-on-floor) bypasses token wrapping
        // and the two-line escape: one TextLine with the complete text; the
        // section scale handles the width.
        if (!content.adaptiveSectioning || content.overflowMode != "Wrap" || hardSingleLine) {
            return listOf(textLine(text, paint.measureText(text), paint))
        }
        val available = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(1f)
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
        val effectiveCap = if (maxLines <= 1 && paint.measureText(text) > available) {
            MAX_SECONDARY_LINES
        } else {
            maxLines.coerceAtLeast(1)
        }
        val resolvedMaxLines = if (paint.measureText(text) > available) {
            maxOf(preferredLines, effectiveCap)
        } else {
            preferredLines
        }.coerceIn(1, effectiveCap)
        return balancedTokenLineTexts(
            tokens,
            tokens.map(paint::measureText),
            paint.measureText(" "),
            available,
            resolvedMaxLines
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
        clipToPaddedWidth: Boolean,
        direction: AodTextDirection
    ) {
        val x = line.startX
        val clipSave = if (clipToPaddedWidth) canvas.save() else -1
        if (clipToPaddedWidth) canvas.clipRect(logicalPadLeft, logicalPadTop, layoutFrameWidth() - logicalPadRight, layoutFrameHeight() - logicalPadBottom)
        val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off") {
            0.55f * glowSpline(progress)
        } else 0f
        applyGlow(originalPaint, glow)
        originalPaint.shader = null
        setTextAlpha(originalPaint, 0.35f, 1f, resolvedPalette.unsungText)
        drawOriginalText(canvas, line, baseline, direction)
        setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
        applySoftSweep(
            originalPaint,
            resolvedPalette.sungText,
            origin = x,
            progress = progress,
            extent = line.width,
            vertical = false,
            direction = direction
        )
        drawOriginalText(canvas, line, baseline, direction)
        originalPaint.shader = null
        originalPaint.clearShadowLayer()
        if (clipToPaddedWidth) canvas.restoreToCount(clipSave)
    }

    private fun drawOriginalText(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        direction: AodTextDirection
    ) {
        if (line.ruby.isEmpty()) {
            drawDirectionalText(canvas, line.text, line.startX, baseline, originalPaint, direction)
            return
        }
        if (line.textRuns.isEmpty()) {
            drawDirectionalText(canvas, line.text, line.startX, baseline, originalPaint, direction)
            return
        }
        var index = 0
        while (index < line.textRuns.size) {
            val run = line.textRuns[index]
            drawDirectionalTextRun(
                canvas,
                line.text,
                run.start,
                run.end,
                line.startX + run.x,
                baseline,
                originalPaint,
                direction
            )
            index++
        }
    }

    private fun drawText(canvas: Canvas, row: Row, baseline: Float) {
        var lineIndex = 0
        while (lineIndex < row.lines.size) {
            val line = row.lines[lineIndex]
            val lineBaseline = baseline + lineIndex * row.lineHeight
            if (row.kind == RowKind.METADATA) {
                row.paint.color = resolvedPalette.metadataText
                row.paint.alpha = 255
                canvas.drawText(line.text, line.startX, lineBaseline, row.paint)
            } else {
                drawSecondaryLine(canvas, row.paint, line.text, line.startX, lineBaseline)
            }
            lineIndex++
        }
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
        canvasWidth = layoutFrameWidth().toFloat(),
        paddingLeft = logicalPadLeft,
        paddingRight = logicalPadRight,
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

    private fun blockProgress(block: BlockDrawData): Float =
        progress(projectedPosition(), block.lineStartMs, block.lineEndMs)

    /**
     * Draw data for every lyric section in [drawLayout], falling back to the
     * primary line when a layout predates sections (initial empty layout).
     */
    private fun blockDrawDataFor(
        drawLayout: LayoutState,
        drawContent: AodCanvasContent
    ): List<BlockDrawData> = if (drawLayout.blocks.isNotEmpty()) {
        drawLayout.blocks
    } else {
        listOf(
            BlockDrawData(
                drawLayout.original,
                drawContent.words,
                drawContent.lineStartMs,
                drawContent.lineEndMs,
                alignment,
                textDirection
            )
        )
    }

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
        vertical: Boolean,
        direction: AodTextDirection = AodTextDirection.LTR
    ) {
        val shaders = when {
            vertical -> verticalSweepShaders
            direction == AodTextDirection.RTL -> horizontalRtlSweepShaders
            else -> horizontalSweepShaders
        }
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
                    if (direction == AodTextDirection.RTL) {
                        intArrayOf(transparent, middle, color)
                    } else {
                        intArrayOf(color, middle, transparent)
                    },
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            shaders.put(color, shader)
        }
        val safeExtent = extent.coerceAtLeast(0f)
        val zone = gradientSweepZone(progress, safeExtent, SWEEP_BAND_FRACTION, direction)
        val band = zone.end - zone.start
        val start = origin + zone.start
        sweepMatrix.setScale(if (vertical) 1f else band, if (vertical) band else 1f)
        sweepMatrix.postTranslate(if (vertical) 0f else start, if (vertical) start else 0f)
        shader.setLocalMatrix(sweepMatrix)
        paint.shader = shader
    }

    private fun applyBlockSweepShaders(origin: Float, progress: Float, extent: Float) {
        applySoftSweep(originalPaint, resolvedPalette.sungText, origin, progress, extent, vertical = true)
    }

    private fun applyWholeBlockHorizontalSweepShaders(progress: Float) {
        val origin = logicalPadLeft
        val extent = (layoutFrameWidth() - logicalPadLeft - logicalPadRight).coerceAtLeast(0f)
        applySoftSweep(
            originalPaint, resolvedPalette.sungText, origin, progress, extent, false, textDirection
        )
        applySoftSweep(
            romanizedPaint, resolvedPalette.secondaryText, origin, progress, extent, false, textDirection
        )
        applySoftSweep(
            translatedPaint, resolvedPalette.secondaryText, origin, progress, extent, false, textDirection
        )
        applySoftSweep(
            rubyPaint, resolvedPalette.secondaryText, origin, progress, extent, false, textDirection
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
        baseline: Float
    ) {
        setTextAlpha(
            paint,
            staticSecondaryTextFactor(content.secondaryTextBright),
            1f,
            resolvedPalette.secondaryText
        )
        paint.shader = null
        paint.clearShadowLayer()
        drawDirectionalText(
            canvas,
            text,
            x,
            baseline,
            paint,
            resolvedAodTextDirection(text)
        )
    }

    private fun drawDirectionalText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        paint: Paint,
        direction: AodTextDirection
    ) {
        if (text.isEmpty()) return
        canvas.drawTextRun(
            text,
            0,
            text.length,
            0,
            text.length,
            x,
            baseline,
            direction == AodTextDirection.RTL,
            paint
        )
    }

    private fun drawDirectionalTextRun(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        baseline: Float,
        paint: Paint,
        direction: AodTextDirection
    ) {
        if (start >= end) return
        canvas.drawTextRun(
            text,
            start,
            end,
            0,
            text.length,
            x,
            baseline,
            direction == AodTextDirection.RTL,
            paint
        )
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
        val lineHeight: Float,
        /** Lyric section this row belongs to, in visual arrival order: -1 metadata, then 0, 1. */
        val blockIndex: Int = 0
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
        val alignment: Alignment,
        val textDirection: AodTextDirection
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
        val original: OriginalLayout,
        /** Per-section draw data in visual order, index-aligned with [Row.blockIndex]. */
        val blocks: List<BlockDrawData> = emptyList(),
        /** Per-section fit scale by visual block index; missing means full size. */
        val sectionScales: Map<Int, Float> = emptyMap(),
        /** Section identity in visual order, for transition pass matching. */
        val sectionIds: List<DuetSectionId> = emptyList(),
        /** Precomputed alignment-aware horizontal pivot per visual block. */
        val sectionPivotsX: Map<Int, Float> = emptyMap()
    )

    /**
     * Everything draw needs for one lyric section beyond shared paints and
     * global modes: its own wrapped layout, timed words, active window, and
     * resolved reading direction.
     */
    private data class BlockDrawData(
        val originalLayout: OriginalLayout,
        val words: List<AodCanvasWord>,
        val lineStartMs: Long,
        val lineEndMs: Long,
        val alignment: Alignment,
        val textDirection: AodTextDirection
    )

    /** One sung line to lay out: the primary or the concurrent overlap. */
    private data class MainLineData(
        val text: String,
        val romanized: String,
        val translated: String,
        val alignedRight: Boolean,
        val lineStartMs: Long,
        val lineEndMs: Long,
        val words: List<AodCanvasWord>,
        val ruby: List<AodCanvasRuby>,
        val layoutGroups: List<AodCanvasLayoutGroup>
    ) {
        constructor(content: AodCanvasContent) : this(
            content.original,
            content.romanized,
            content.translated,
            content.alignedRight,
            content.lineStartMs,
            content.lineEndMs,
            content.words,
            content.ruby,
            content.layoutGroups
        )

        constructor(second: AodCanvasSecondLine) : this(
            second.text,
            second.romanized,
            second.translated,
            second.alignedRight,
            second.lineStartMs,
            second.lineEndMs,
            second.words,
            second.ruby,
            second.layoutGroups
        )
    }
    private data class TypefaceKey(val family: String, val weight: String)

    companion object {
        internal const val MAX_SECONDARY_LINES = 2
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
        private const val ORIGINAL_LINE_GAP_DP = 4f
        private const val END_EDGE_SAFETY_DP = 4f
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
    }
}

/**
 * Free-anchor shift for a laid-out row span. Null bias and overfull spans
 * return zero, so legacy placement is preserved exactly. The result is
 * clamped to keep the whole span inside the padded bounds.
 */
internal fun resolveVerticalBiasShift(
    spanTop: Float,
    spanBottom: Float,
    topBound: Float,
    bottomBound: Float,
    bias: Float?
): Float {
    if (bias == null || !bias.isFinite()) return 0f
    val free = (bottomBound - topBound) - (spanBottom - spanTop)
    if (free <= 0f) return 0f
    return (free * (bias.coerceIn(0f, 1f) - 0.5f))
        .coerceIn(topBound - spanTop, bottomBound - spanBottom)
}

/**
 * Identity of one sung line for duet slot memory. Timings survive producer
 * text corrections, so a corrected line keeps its canvas slot.
 */
internal data class DuetSectionId(
    val trackGeneration: Long,
    val lineStartMs: Long,
    val lineEndMs: Long
)

/**
 * Visual section order by slot: lines present in the previous build keep
 * their slot, so a continuing line never moves between sections; newcomers
 * inherit vacated slots in current-list order, so a replacement takes the
 * exiting line's position instead of appending below. A full swap (nothing
 * continues) keeps current order. At most two sections exist.
 */
internal fun assignDuetSlots(
    current: List<DuetSectionId>,
    previous: List<DuetSectionId>
): List<DuetSectionId> {
    if (current.size <= 1) return current
    val previousSlot = HashMap<DuetSectionId, Int>(previous.size)
    previous.forEachIndexed { index, id -> previousSlot.putIfAbsent(id, index) }
    val taken = BooleanArray(current.size)
    val slotted = arrayOfNulls<DuetSectionId>(current.size)
    for (index in current.indices) {
        val id = current[index]
        val slot = previousSlot[id]
        if (slot != null && slot < slotted.size && slotted[slot] == null) {
            slotted[slot] = id
            taken[index] = true
        }
    }
    var free = 0
    for (index in current.indices) {
        if (taken[index]) continue
        while (free < slotted.size && slotted[free] != null) free++
        if (free < slotted.size) {
            slotted[free] = current[index]
            free++
        }
    }
    return slotted.filterNotNull()
}

/**
 * Visual block indices whose first sung word still lies ahead of the playhead:
 * prerender placeholders that reserve layout, shrink, and slot space but stay
 * invisible until their window starts. Solo scenes never defer.
 */
internal fun deferredDuetBlockIndices(
    blockStartMs: List<Long>,
    positionMs: Long
): Set<Int> =
    if (blockStartMs.size <= 1) {
        emptySet()
    } else {
        blockStartMs.indices.filter { blockStartMs[it] > positionMs }.toSet()
    }

/**
 * Frozen range owning most of [offset]. Word segmentations flap across
 * publications for the same text, so strict containment would abort the
 * freeze on every resegmentation; majority overlap keeps every word placed
 * and the sentence shape frozen.
 */
internal fun majorityRangeIndex(ranges: List<IntRange>, offset: IntRange): Int {
    var best = 0
    var bestOverlap = Int.MIN_VALUE
    for (index in ranges.indices) {
        val overlap = minOf(offset.last, ranges[index].last) -
            maxOf(offset.first, ranges[index].first) + 1
        if (overlap > bestOverlap) {
            bestOverlap = overlap
            best = index
        }
    }
    return best
}

/**
 * Secondary-row cap for one lyric section: landscape anchored sections
 * (duets and chain solos) keep one pinyin/translation line each so the pair
 * fits without shrinking the survivor; fresh solos and portrait keep legacy
 * multi-line secondaries.
 */
internal fun duetSecondaryLineCap(anchored: Boolean, sideStep: Boolean): Int =
    if (anchored && sideStep) 1 else AodLyricCanvasView.MAX_SECONDARY_LINES

/**
 * Frozen line breaks for one lyric line: the text length they were laid out
 * for plus the wrapped ranges. Timing lanes settle across publications for
 * the same timings, which would otherwise re-wrap (and rebalance) the
 * sentence on every refinement. The first layout wins; later builds reuse
 * its breaks with freshly measured widths and current words, so karaoke
 * fill stays accurate while the sentence shape never moves. Length-keyed
 * rather than text-keyed, so same-length refinements (punctuation, spacing,
 * spelling) hold their shape; a width check at apply time still lets
 * genuinely wider text re-lay out instead of clipping.
 */
internal data class FrozenLineWrap(val textLength: Int, val ranges: List<IntRange>)

/**
 * Usable frozen breaks for [text], or null when the cache misses or the
 * ranges do not cover the text. Ranges may leave a one-character separator
 * (the authored space between wrapped words) uncited between lines; every
 * other character must be covered exactly once.
 */
internal fun validFrozenWrap(cached: FrozenLineWrap?, text: String): List<IntRange>? {
    if (cached == null || cached.textLength != text.length || cached.ranges.isEmpty()) return null
    var cursor = 0
    for (range in cached.ranges) {
        if (range.isEmpty() || range.first < cursor || range.last >= text.length) return null
        if (range.first - cursor > 1) return null
        cursor = range.last + 1
    }
    if (cursor != text.length && cursor + 1 != text.length) return null
    return cached.ranges
}

/** Frozen breaks derived from laid-out lines, or null when any line lacks offsets. */
internal fun frozenRangesFrom(
    lineBounds: List<Pair<Int?, Int?>>,
    text: String
): FrozenLineWrap? {
    if (text.isEmpty()) return null
    val ranges = ArrayList<IntRange>(lineBounds.size)
    for ((start, end) in lineBounds) {
        if (start == null || end == null || end <= start) return null
        ranges += IntRange(start, end - 1)
    }
    return validFrozenWrap(FrozenLineWrap(text.length, ranges), text)?.let {
        FrozenLineWrap(text.length, it)
    }
}

/**
 * Minimum visual lines needed to lay out unit widths within [available]:
 * greedy packing of indivisible units. Contiguous fixed-width units pack
 * optimally greedily, so this is the true minimum for a chunk sequence.
 */
internal fun minimalLineCount(widths: List<Float>, available: Float): Int {
    if (widths.isEmpty() || available <= 0f) return widths.size.coerceAtLeast(1)
    var count = 1
    var width = 0f
    for (item in widths) {
        if (width > 0f && width + item > available) {
            count++
            width = item
        } else {
            width += item
        }
    }
    return count
}

/**
 * Whether frozen breaks are still the minimal wrap under the current
 * geometry: each frozen line measured at its current width, packed greedily.
 * Fewer packed lines than frozen lines means the freeze came from a smaller
 * frame (pre-layout, portrait step, metadata-present area) and must be
 * repaired once instead of locking a bloated wrap in forever.
 */
internal fun frozenWrapIsMinimal(
    ranges: List<IntRange>,
    measureLine: (IntRange) -> Float,
    gap: Float,
    available: Float
): Boolean {
    if (ranges.isEmpty()) return true
    val widths = ranges.map(measureLine)
    if (widths.any { it > available }) return false
    var count = 1
    var width = 0f
    widths.forEachIndexed { index, item ->
        val effective = if (index == 0) item else item + gap
        if (width > 0f && width + effective > available) {
            count++
            width = item
        } else {
            width += effective
        }
    }
    return count >= ranges.size
}

/**
 * Alignment-aware horizontal pivot for one section's draw-scale transform.
 * Scaling around the same reference `alignedStart` positions text with
 * preserves the section's alignment after the shrink: START keeps its
 * physical left edge, END its physical right edge, CENTER its drawn
 * center. A center pivot on a start-aligned section would inset the left
 * edge by (1 - scale) * width / 2 — the one-sided gap.
 */
internal fun resolveDuetSectionPivotX(
    alignment: AodLyricCanvasView.Alignment,
    extentLeft: Float,
    extentRight: Float
): Float = when (alignment) {
    AodLyricCanvasView.Alignment.START -> extentLeft
    AodLyricCanvasView.Alignment.END -> extentRight
    AodLyricCanvasView.Alignment.CENTER -> (extentLeft + extentRight) / 2f
}

/**
 * Transition pass split for a duet-safe crossfade: sections present in both
 * layouts continue (drawn once from the incoming layout at full opacity, so
 * the survivor never crossfades against itself); outgoing-only sections
 * fade out with the exit pass; incoming-only sections fade in with the
 * enter pass. Returns per-pass block-index filters in visual order.
 */
internal data class DuetTransitionPasses(
    val continuingEnterBlocks: Set<Int>,
    val departingExitBlocks: Set<Int>,
    val arrivingEnterBlocks: Set<Int>
)

internal fun resolveDuetTransitionPasses(
    exitIds: List<DuetSectionId>,
    enterIds: List<DuetSectionId>,
    exitLineCounts: List<Int> = emptyList(),
    enterLineCounts: List<Int> = emptyList()
): DuetTransitionPasses {
    val exitSet = exitIds.toSet()
    val enterSet = enterIds.toSet()
    // A continuing section whose wrapped-line count changed re-presented
    // (e.g. the unwrap-on-floor upgrade at a duet join). Survivor-once
    // would swap it instantly; instead it crossfades — drawn fading out in
    // the exit pass and fading in with the enter pass. Without line-count
    // inputs the classification falls back to pure section identity.
    val continuing = buildSet {
        enterIds.forEachIndexed { enterIndex, id ->
            if (id !in exitSet) return@forEachIndexed
            val exitIndex = exitIds.indexOf(id)
            val presentationChanged = exitLineCounts.getOrNull(exitIndex) != null &&
                enterLineCounts.getOrNull(enterIndex) != null &&
                exitLineCounts[exitIndex] != enterLineCounts[enterIndex]
            if (!presentationChanged) add(id)
        }
    }
    return DuetTransitionPasses(
        continuingEnterBlocks = enterIds.indices.filter { enterIds[it] in continuing }.toSet(),
        departingExitBlocks = exitIds.indices.filter { exitIds[it] !in continuing }.toSet(),
        arrivingEnterBlocks = enterIds.indices.filter { enterIds[it] !in continuing }.toSet()
    )
}

/** Floor for shrink-to-fit: below half size the canvas clips instead. */
internal const val MIN_OVERFLOW_SHRINK_SCALE = 0.5f

/**
 * Unwrap-on-floor tolerance: the single-line presentation must come within
 * this much of the wrapped scale to replace it, so wide lines that would
 * unwrap into a far tinier single line stay wrapped while genuinely shrunk
 * sections upgrade to the cleaner one-line form.
 */
internal const val UNWRAP_SCALE_TOLERANCE = 0.15f

/**
 * Whether the unwrapped single-line presentation replaces the shrunk
 * wrapped one.
 */
internal fun shouldUnwrapShrunkSection(
    wrappedScale: Float,
    singleLineScale: Float,
    tolerance: Float = UNWRAP_SCALE_TOLERANCE
): Boolean =
    wrappedScale.isFinite() && singleLineScale.isFinite() &&
        singleLineScale + tolerance >= wrappedScale

/**
 * Exact fit for one-line presentation from drawn (ink) extents — the same
 * bounds drawing uses, so a wide single line shrinks instead of clipping.
 * Unusable inputs contribute 1f: no NaN or zero transform.
 */
internal fun resolveVisualWidthFitScale(
    widestDrawnWidth: Float,
    availableWidth: Float
): Float = when {
    !widestDrawnWidth.isFinite() || !availableWidth.isFinite() ||
        widestDrawnWidth <= 0f || availableWidth <= 0f -> 1f
    widestDrawnWidth <= availableWidth -> 1f
    else -> availableWidth / widestDrawnWidth
}

/**
 * Low absolute floor for the shared duet fit: below this the canvas clips,
 * but a clipped section reads worse than any small scale, so the shared
 * fit stays exact far below the solo shrink floor.
 */
internal const val MIN_SHARED_DUET_SCALE = 0.3f

/**
 * Shared fit for the combined duet stack: full size when it fits, the exact
 * ratio otherwise — floored only at the deep absolute minimum, because a
 * floored overflow clips a section's bottom rows mid-draw.
 */
internal fun resolveSharedDuetScale(
    combinedHeight: Float,
    areaHeight: Float
): Float {
    if (!combinedHeight.isFinite() || !areaHeight.isFinite()) return 1f
    if (combinedHeight <= 0f || areaHeight <= 0f) return 1f
    if (combinedHeight <= areaHeight) return 1f
    return (areaHeight / combinedHeight)
        .coerceIn(MIN_SHARED_DUET_SCALE.coerceIn(0f, 1f), 1f)
}

/** Drawn-space anchor tolerance: within this, a stored top equals the
 * chained position and the anchor holds; outside it, the survivor's height
 * changed after the anchor was committed and the dependent re-chains. */
internal const val ANCHOR_CONSISTENCY_PX = 2f

/**
 * Uniform lyric text scale so an overfull stack fits its area. Returns 1
 * when the stack fits or the inputs are unusable; otherwise the exact fit
 * ratio clamped to the readable floor.
 */
internal fun resolveOverflowShrinkScale(
    totalHeight: Float,
    availableHeight: Float,
    minScale: Float = MIN_OVERFLOW_SHRINK_SCALE
): Float {
    if (!totalHeight.isFinite() || !availableHeight.isFinite()) return 1f
    if (totalHeight <= 0f || availableHeight <= 0f) return 1f
    if (totalHeight <= availableHeight) return 1f
    return (availableHeight / totalHeight).coerceIn(minScale.coerceIn(0f, 1f), 1f)
}

internal fun shouldRecenterAfterDuet(wasDuet: Boolean, sectionCount: Int): Boolean =
    wasDuet && sectionCount == 1

/**
 * Shift that keeps a laid-out lyric block inside its area. A fitting block
 * never moves. An overfull block pins its top and clips at the bottom, so
 * the current line stays visible; a fitting overhang re-pins to the nearest
 * edge instead of running off-screen.
 */
internal fun resolveBlockClampShift(
    spanTop: Float,
    spanBottom: Float,
    areaTop: Float,
    areaBottom: Float
): Float {
    if (!spanTop.isFinite() || !spanBottom.isFinite() ||
        !areaTop.isFinite() || !areaBottom.isFinite()
    ) return 0f
    if (spanBottom <= spanTop || areaBottom <= areaTop) return 0f
    if (spanBottom - spanTop > areaBottom - areaTop) return areaTop - spanTop
    if (spanTop < areaTop) return areaTop - spanTop
    if (spanBottom > areaBottom) return areaBottom - spanBottom
    return 0f
}

/**
 * Whether a layout pass may commit episode state (anchors, order, scale,
 * generation). Blank gap/hidden snapshots, degenerate placeholder timings,
 * and pre-layout zero-size frames still clear the screen, but they must not
 * wipe the episode: the next real build would otherwise re-place and
 * re-settle everything, reading as a resize on every gap. Placeholder
 * snapshots carry the real generation with an empty timing window, so the
 * window check (not the generation) tells them apart.
 */
internal fun shouldCommitLayoutState(
    hasLyricRows: Boolean,
    frameUsable: Boolean,
    timingValid: Boolean
): Boolean = hasLyricRows && frameUsable && timingValid

/**
 * Duet slot tops for one layout pass, in lyric-area coordinates. Sections
 * the canvas already shows keep their exact tops, so a joining, leaving, or
 * changing partner never moves the survivor; newcomers stack adjacently
 * above or below their nearest placed neighbor, keeping the sections
 * connected instead of spread across the frame. With no anchor at all (full
 * swap) the block centers on the last known center so the motion is minimal.
 * Consumed heights include each section's leading gap, matching the row
 * stacking loop exactly.
 */
internal fun placeDuetSectionTops(
    order: List<DuetSectionId>,
    consumed: Map<DuetSectionId, Float>,
    areaCenter: Float,
    lastTops: Map<DuetSectionId, Float>,
    lastBlockCenter: Float?
): Map<DuetSectionId, Float> {
    if (order.isEmpty()) return emptyMap()
    val result = LinkedHashMap<DuetSectionId, Float>()
    for (id in order) {
        lastTops[id]?.let { result[id] = it }
    }
    if (result.size == order.size) return result
    val unplaced = order.filter { it !in result }.toMutableList()
    var progressed = true
    while (unplaced.isNotEmpty() && progressed) {
        progressed = false
        val iterator = unplaced.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val index = order.indexOf(id)
            val previous = order.subList(0, index).lastOrNull { it in result }
            if (previous != null) {
                result[id] = result.getValue(previous) + (consumed[previous] ?: 0f)
                iterator.remove()
                progressed = true
                continue
            }
            val next = order.subList(index + 1, order.size).firstOrNull { it in result }
            if (next != null) {
                result[id] = result.getValue(next) - (consumed[id] ?: 0f)
                iterator.remove()
                progressed = true
            }
        }
    }
    if (unplaced.isNotEmpty()) {
        val total = order.sumOf { (consumed[it] ?: 0f).toDouble() }.toFloat()
        var cursor = (lastBlockCenter ?: areaCenter) - total / 2f
        for (id in order) {
            result[id] = cursor
            cursor += consumed[id] ?: 0f
        }
    }
    return result
}
