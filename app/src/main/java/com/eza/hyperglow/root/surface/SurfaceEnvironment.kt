package com.eza.hyperglow.root.surface

import com.eza.hyperglow.root.projection.LyricSurfaceKind

internal data class SurfaceEnvironment(
    val surfaceKind: LyricSurfaceKind,
    val generation: Long,
    val rootWidth: Int = 0,
    val rootHeight: Int = 0,
    val stockBottom: Int = 0,
    val safeBottom: Int? = null,
    val burnInTranslationX: Float = 0f,
    val burnInTranslationY: Float = 0f,
    val fullAodSupported: Boolean = false,
    val videoDepthSupported: Boolean = false
)
