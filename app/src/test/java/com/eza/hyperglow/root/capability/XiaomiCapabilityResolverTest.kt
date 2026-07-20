package com.eza.hyperglow.root.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiCapabilityResolverTest {
    @Test
    fun unknownBuildHasNoCapabilities() {
        assertEquals(emptySet<XiaomiCapability>(), resolveXiaomiCapabilities(XiaomiSymbolSnapshot()))
    }

    @Test
    fun dependentCapabilitiesFailClosed() {
        val capabilities = resolveXiaomiCapabilities(
            XiaomiSymbolSnapshot(
                aodPositionUpdates = true,
                aodLifetimeGuard = true,
                lockscreenGeometry = true,
                linkageDirection = true,
                linkageGeometry = true,
                fullAod = true,
                videoDepth = true
            )
        )

        assertFalse(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertFalse(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertFalse(XiaomiCapability.LOCKSCREEN_GEOMETRY in capabilities)
        assertFalse(XiaomiCapability.LINKAGE_DIRECTION in capabilities)
        assertFalse(XiaomiCapability.LINKAGE_GEOMETRY in capabilities)
        assertFalse(XiaomiCapability.FULL_AOD in capabilities)
        assertFalse(XiaomiCapability.VIDEO_DEPTH in capabilities)
    }

    @Test
    fun verifiedSymbolsResolveIndependentCapabilitySet() {
        val capabilities = resolveXiaomiCapabilities(
            XiaomiSymbolSnapshot(
                aodSurface = true,
                aodPositionUpdates = true,
                aodLifetimeGuard = true,
                lockscreenHost = true,
                lockscreenGeometry = true,
                linkageDirection = true,
                linkageGeometry = false,
                raiseToAod = true,
                fullAod = false,
                videoDepth = true
            )
        )

        assertTrue(XiaomiCapability.AOD_SURFACE in capabilities)
        assertTrue(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertTrue(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_HOST in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_GEOMETRY in capabilities)
        assertTrue(XiaomiCapability.LINKAGE_DIRECTION in capabilities)
        assertFalse(XiaomiCapability.LINKAGE_GEOMETRY in capabilities)
        assertTrue(XiaomiCapability.RAISE_TO_AOD in capabilities)
        assertFalse(XiaomiCapability.FULL_AOD in capabilities)
        assertTrue(XiaomiCapability.VIDEO_DEPTH in capabilities)
    }

    @Test
    fun unknownPackageProfileRejectsMutatingAodCapabilities() {
        val capabilities = resolveXiaomiCapabilities(
            XiaomiSymbolSnapshot(
                aodSurface = true,
                aodPositionUpdates = true,
                aodLifetimeGuard = true,
                lockscreenHost = true,
                lockscreenGeometry = true,
                linkageDirection = true,
                raiseToAod = true
            ),
            verifiedRuntimeProfile = false
        )

        assertFalse(XiaomiCapability.AOD_SURFACE in capabilities)
        assertFalse(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertFalse(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertFalse(XiaomiCapability.LOCKSCREEN_HOST in capabilities)
        assertFalse(XiaomiCapability.RAISE_TO_AOD in capabilities)
    }

    @Test
    fun packageVersionPairMustMatchVerifiedLiveBuild() {
        assertTrue(
            XiaomiCapabilityResolver.isVerifiedRuntimeProfile(
                "16.03.251211.r(202501210)",
                "DEV-2327.0.0.1-03022115(22327001)"
            )
        )
        assertFalse(
            XiaomiCapabilityResolver.isVerifiedRuntimeProfile(
                "unknown",
                "DEV-2327.0.0.1-03022115(22327001)"
            )
        )
    }
}
