package com.eza.hyperglow.root.surface

import com.eza.hyperglow.root.projection.LyricSurfaceKind

internal data class SurfaceRenderPolicy(
    val maxWidgets: Int,
    val artworkAllowed: Boolean,
    val progressAllowed: Boolean,
    val maximumHeightFraction: Float,
    val minimumAnimationDurationMs: Int,
    val maximumAnimationDurationMs: Int,
    val fullAodSupported: Boolean,
    val videoDepthSupported: Boolean
)

internal object SurfacePolicyResolver {
    fun resolve(
        surfaceKind: LyricSurfaceKind,
        fullAodSupported: Boolean = false,
        videoDepthSupported: Boolean = false
    ): SurfaceRenderPolicy = when (surfaceKind) {
        LyricSurfaceKind.LOCKSCREEN -> SurfaceRenderPolicy(
            maxWidgets = 8,
            artworkAllowed = false,
            progressAllowed = true,
            maximumHeightFraction = 0.8f,
            minimumAnimationDurationMs = 150,
            maximumAnimationDurationMs = 600,
            fullAodSupported = false,
            videoDepthSupported = false
        )
        LyricSurfaceKind.AOD -> SurfaceRenderPolicy(
            maxWidgets = 4,
            artworkAllowed = false,
            progressAllowed = false,
            maximumHeightFraction = 0.5f,
            minimumAnimationDurationMs = 150,
            maximumAnimationDurationMs = 600,
            fullAodSupported = fullAodSupported,
            videoDepthSupported = videoDepthSupported
        )
    }
}
