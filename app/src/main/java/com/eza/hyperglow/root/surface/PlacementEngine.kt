package com.eza.hyperglow.root.surface

import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.WidgetSpec

internal data class PlacementRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal data class PlacementEnvironment(
    val safeCanvas: PlacementRect,
    val stockClockBottom: Float,
    val bottomReserveTop: Float,
    val notificationTop: Float? = null
)

internal data class WidgetMeasurement(
    val spec: WidgetSpec,
    val height: Float
)

internal data class ResolvedPlacement(
    val contentRect: PlacementRect?,
    val visibleWidgets: List<WidgetSpec>,
    val hiddenWidgets: List<WidgetSpec>
)

internal object PlacementEngine {
    fun resolve(
        profile: CompiledSurfaceProfile,
        environment: PlacementEnvironment,
        measurements: List<WidgetMeasurement>,
        minimumLyricHeight: Float
    ): ResolvedPlacement {
        val canvas = environment.safeCanvas
        if (profile.collisionPolicy == "hide_scene" && environment.notificationTop != null) {
            return ResolvedPlacement(null, emptyList(), measurements.map { it.spec })
        }
        val width = canvas.width * profile.widthFraction
        val left = canvas.left + (canvas.width - width) / 2f
        val safeTop = if (profile.collisionPolicy == "behind_system") {
            canvas.top
        } else {
            maxOf(canvas.top, environment.stockClockBottom)
        }
        val safeBottom = minOf(
            canvas.bottom,
            environment.bottomReserveTop,
            if (profile.collisionPolicy == "behind_system") {
                Float.POSITIVE_INFINITY
            } else {
                environment.notificationTop ?: Float.POSITIVE_INFINITY
            }
        )
        if (safeBottom <= safeTop) return ResolvedPlacement(null, emptyList(), measurements.map { it.spec })
        val maxHeight = minOf(safeBottom - safeTop, canvas.height * profile.maxHeightFraction)
        val kept = measurements.toMutableList()
        val hidden = ArrayList<WidgetSpec>()
        fun totalHeight(): Float = kept.sumOf { it.height.toDouble() }.toFloat()
        while (totalHeight() > maxHeight) {
            val optionalIndex = kept.indexOfLast { it.spec.optional || it.spec.type != "lyrics" }
            if (optionalIndex < 0) break
            hidden += kept.removeAt(optionalIndex).spec
        }
        val lyric = kept.firstOrNull { it.spec.type == "lyrics" }
        if (lyric == null) {
            return ResolvedPlacement(null, emptyList(), measurements.map { it.spec })
        }
        val nonLyricHeight = kept.asSequence()
            .filter { it.spec.type != "lyrics" }
            .sumOf { it.height.toDouble() }
            .toFloat()
        val resolvedLyricHeight = minOf(lyric.height, maxHeight - nonLyricHeight)
        if (resolvedLyricHeight < minimumLyricHeight) {
            return ResolvedPlacement(null, emptyList(), measurements.map { it.spec })
        }
        val height = nonLyricHeight + resolvedLyricHeight
        val top = when (profile.anchor) {
            "screen_center" -> ((canvas.top + canvas.bottom - height) / 2f).coerceIn(safeTop, safeBottom - height)
            "screen_bottom_safe" -> safeBottom - height
            "custom_vertical_bias" -> safeTop + (safeBottom - safeTop - height) * profile.verticalBias
            else -> safeTop
        }
        return ResolvedPlacement(
            PlacementRect(left, top, left + width, top + height),
            kept.map { it.spec },
            hidden
        )
    }
}
