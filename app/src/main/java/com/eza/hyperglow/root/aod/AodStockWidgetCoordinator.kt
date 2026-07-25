package com.eza.hyperglow.root.aod

internal enum class AodSceneZone {
    STOCK,
    CLOCK_TOP,
    CLOCK_BOTTOM
}

internal data class AodClockGeometry(
    val mode: Int,
    val baseTranslationY: Float,
    val translationYStep: Float,
    val viewTop: Int,
    val viewHeight: Int,
    val translationXStep: Int = 0
)

internal data class AodClockZoneBounds(
    val topTranslationY: Float,
    val bottomTranslationY: Float,
    val topContentTop: Int,
    val topContentBottom: Int,
    val bottomContentTop: Int,
    val bottomContentBottom: Int
) {
    fun contentTop(zone: AodSceneZone, fallback: Int): Int = when (zone) {
        AodSceneZone.CLOCK_TOP -> topContentTop
        AodSceneZone.CLOCK_BOTTOM -> bottomContentTop
        AodSceneZone.STOCK -> fallback
    }

    fun contentBottom(zone: AodSceneZone, fallback: Int): Int = when (zone) {
        AodSceneZone.CLOCK_TOP -> topContentBottom
        AodSceneZone.CLOCK_BOTTOM -> bottomContentBottom
        AodSceneZone.STOCK -> fallback
    }
}

internal data class AodClockPlacementDecision(
    val requestedTranslationX: Int,
    val requestedTranslationY: Float,
    val appliedTranslationX: Int,
    val appliedTranslationY: Float,
    val clockTop: Int,
    val clockBottom: Int,
    val lyricTopSafe: Int,
    val zone: AodSceneZone,
    val zoneChanged: Boolean,
    val overridden: Boolean
)

internal data class AodBurnInPatternSlot(
    val zone: AodSceneZone,
    val horizontalStep: Int
)

internal data class AodNaturalTranslation(
    val x: Int,
    val y: Float
)

internal fun naturalAodTranslation(
    geometry: AodClockGeometry,
    moveCurrent: Int
): AodNaturalTranslation? {
    if (!geometry.baseTranslationY.isFinite() ||
        !geometry.translationYStep.isFinite() ||
        geometry.translationYStep <= 0f ||
        geometry.viewHeight <= 0
    ) return null
    val halfStep = moveCurrent / 2
    val verticalStep: Int
    val horizontalStep: Int
    when (geometry.mode) {
        0 -> {
            horizontalStep = halfStep % 3 - 1
            verticalStep = halfStep / 3
        }
        2, 3 -> {
            horizontalStep = 0
            verticalStep = halfStep
        }
        else -> return null
    }
    return AodNaturalTranslation(
        x = geometry.translationXStep * horizontalStep,
        y = geometry.baseTranslationY +
            geometry.translationYStep * verticalStep - geometry.viewTop
    )
}

internal fun aodBurnInPatternSlots(pattern: String): List<AodBurnInPatternSlot> = when (pattern) {
    "static_top" -> listOf(
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, 0)
    )
    "static_bottom" -> listOf(
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, 0)
    )
    "vertical_swap" -> listOf(
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, 0),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, 0)
    )
    "four_corner" -> listOf(
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, 1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, -1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, -1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, 1)
    )
    else -> listOf(
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, 1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, -1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, 0),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, 1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_BOTTOM, -1),
        AodBurnInPatternSlot(AodSceneZone.CLOCK_TOP, 0)
    )
}

internal fun managedAodPatternRepeats(pattern: String): Boolean =
    pattern != "static_top" && pattern != "static_bottom"

internal fun managedAodPlacementChanged(
    previous: AodClockPlacementDecision,
    next: AodClockPlacementDecision
): Boolean = previous.appliedTranslationX != next.appliedTranslationX ||
    previous.appliedTranslationY != next.appliedTranslationY ||
    previous.clockTop != next.clockTop ||
    previous.clockBottom != next.clockBottom ||
    previous.lyricTopSafe != next.lyricTopSafe ||
    previous.zone != next.zone

internal fun managedAodClockDecision(
    pattern: String,
    step: Int,
    requestedTranslationX: Int,
    requestedTranslationY: Float,
    geometry: AodClockGeometry
): AodClockPlacementDecision? {
    val bounds = resolveAodClockZoneBounds(geometry) ?: return null
    val slots = aodBurnInPatternSlots(pattern)
    val slot = slots[Math.floorMod(step, slots.size)]
    val appliedY = when (slot.zone) {
        AodSceneZone.CLOCK_TOP -> bounds.topTranslationY
        AodSceneZone.CLOCK_BOTTOM -> bounds.bottomTranslationY
        AodSceneZone.STOCK -> requestedTranslationY
    }
    val requestedTop = (requestedTranslationY + geometry.viewTop).toInt()
    val requestedBottom = requestedTop + geometry.viewHeight
    return AodClockPlacementDecision(
        requestedTranslationX = requestedTranslationX,
        requestedTranslationY = requestedTranslationY,
        appliedTranslationX = geometry.translationXStep * slot.horizontalStep,
        appliedTranslationY = appliedY,
        clockTop = bounds.contentTop(slot.zone, requestedTop),
        clockBottom = bounds.contentBottom(slot.zone, requestedBottom),
        lyricTopSafe = bounds.topContentTop.coerceAtLeast(0),
        zone = slot.zone,
        zoneChanged = true,
        overridden = true
    )
}

internal fun resolveAodClockZoneBounds(geometry: AodClockGeometry): AodClockZoneBounds? {
    if (geometry.mode !in CONTROLLED_AOD_MODES ||
        !geometry.baseTranslationY.isFinite() ||
        !geometry.translationYStep.isFinite() ||
        geometry.translationYStep <= 0f ||
        geometry.viewHeight <= 0
    ) return null
    val topTranslation = geometry.baseTranslationY - geometry.viewTop
    val bottomTranslation = topTranslation + geometry.translationYStep * VERTICAL_ZONE_STEPS
    if (!topTranslation.isFinite() || !bottomTranslation.isFinite() ||
        bottomTranslation <= topTranslation
    ) return null
    val topContentTop = (topTranslation + geometry.viewTop).toInt()
    val bottomContentTop = (bottomTranslation + geometry.viewTop).toInt()
    return AodClockZoneBounds(
        topTranslationY = topTranslation,
        bottomTranslationY = bottomTranslation,
        topContentTop = topContentTop,
        topContentBottom = topContentTop + geometry.viewHeight,
        bottomContentTop = bottomContentTop,
        bottomContentBottom = bottomContentTop + geometry.viewHeight
    )
}

private val CONTROLLED_AOD_MODES = setOf(0, 3)
private const val VERTICAL_ZONE_STEPS = 8f
