package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.root.projection.LyricSnapshot

internal fun LyricSnapshot.toAodCanvasContent(
    profile: CompiledSurfaceProfile? = null
): AodCanvasContent = AodCanvasContent(
    trackGeneration = trackGeneration,
    metadata = metadata,
    original = original,
    romanized = romanized,
    translated = translated,
    alignedRight = alignedRight,
    lineLevelSync = lineLevelSync,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = words.map {
        AodCanvasWord(
            it.text,
            it.romanized,
            it.startMs,
            it.endMs,
            it.boundaryAfter,
            it.sourceStart,
            it.sourceEnd
        )
    },
    ruby = if (profile?.rubyVisible == false) {
        emptyList()
    } else {
        ruby.map { AodCanvasRuby(it.start, it.end, it.reading) }
    },
    layoutGroups = layoutGroups.map {
        AodCanvasLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
    },
    weight = profile?.weight ?: weight,
    textSizeMode = profile?.textSize ?: textSizeMode,
    textSizeCustom = profile?.textSizeCustom ?: textSizeCustom,
    secondaryMode = profile?.secondaryMode ?: secondaryMode,
    animationMode = profile?.animation ?: animationMode,
    glowMode = profile?.glow ?: glowMode,
    motionMode = normalizeAodMotion(motionMode),
    lineSyncFillMode = when (profile?.lineSyncFillMode) {
        "Left to right" -> "Left to right (whole block)"
        "None",
        "Top to bottom",
        "Left to right (main only)",
        "Left to right (whole block)" -> profile.lineSyncFillMode
        else -> lineSyncFillMode
    },
    overflowMode = profile?.overflow ?: overflowMode,
    transitionMode = transitionMode,
    fontFamily = profile?.fontFamily ?: fontFamily,
    alignmentMode = profile?.alignment ?: alignmentMode,
    metadataVisible = profile?.metadataVisible ?: metadataVisible,
    metadataAnchor = if ((profile?.metadataAnchor ?: metadataAnchor) == "bottom") "bottom" else "top",
    metadataSizePercent = profile?.metadataSizePercent ?: 100,
    adaptiveSectioning = profile?.adaptiveSectioning ?: adaptiveSectioning,
    palette = profile?.palette.orEmpty()
)
