package com.eza.hyperglow.root.surface

import com.eza.hyperglow.root.projection.LyricSurfaceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacePolicyResolverTest {
    @Test
    fun extendedAodModesMapCapabilitiesButKeepStableWidgetsDisabled() {
        val policy = SurfacePolicyResolver.resolve(
            LyricSurfaceKind.AOD,
            fullAodSupported = true,
            videoDepthSupported = true
        )

        assertTrue(policy.fullAodSupported)
        assertTrue(policy.videoDepthSupported)
        assertFalse(policy.artworkAllowed)
        assertFalse(policy.progressAllowed)
    }

    @Test
    fun lockscreenArtworkStaysDisabledUntilBoundedProviderExists() {
        val policy = SurfacePolicyResolver.resolve(LyricSurfaceKind.LOCKSCREEN)

        assertFalse(policy.artworkAllowed)
        assertTrue(policy.progressAllowed)
    }
}
