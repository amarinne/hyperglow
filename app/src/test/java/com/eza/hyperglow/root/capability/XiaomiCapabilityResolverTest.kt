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
                aodWakeBroker = true,
                lockscreenGeometry = true,
                linkageDirection = true,
                linkageGeometry = true,
                fullAod = true,
                videoDepth = true
            )
        )

        assertFalse(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertFalse(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertTrue(XiaomiCapability.AOD_WAKE_BROKER in capabilities)
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
                aodHostContainer = true,
                aodPositionUpdates = true,
                aodPositionTarget = true,
                aodLifetimeGuard = true,
                aodWakeBroker = true,
                lockscreenHost = true,
                lockscreenController = true,
                lockscreenHostContainer = true,
                lockscreenGeometry = true,
                linkageDirection = true,
                linkageGeometry = false,
                raiseToAod = true,
                lockscreenEditorGesture = true,
                fullAod = false,
                videoDepth = true
            )
        )

        assertTrue(XiaomiCapability.AOD_SURFACE in capabilities)
        assertTrue(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertTrue(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertTrue(XiaomiCapability.AOD_WAKE_BROKER in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_HOST in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_GEOMETRY in capabilities)
        assertTrue(XiaomiCapability.LINKAGE_DIRECTION in capabilities)
        assertFalse(XiaomiCapability.LINKAGE_GEOMETRY in capabilities)
        assertTrue(XiaomiCapability.RAISE_TO_AOD in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE in capabilities)
        assertFalse(XiaomiCapability.FULL_AOD in capabilities)
        assertTrue(XiaomiCapability.VIDEO_DEPTH in capabilities)
    }

    @Test
    fun unverifiedBuildStillResolvesEverySymbolBackedCapability() {
        val symbols = XiaomiSymbolSnapshot(
            aodSurface = true,
            aodHostContainer = true,
            aodPositionUpdates = true,
            aodPositionTarget = true,
            aodLifetimeGuard = true,
            aodWakeBroker = true,
            lockscreenHost = true,
            lockscreenController = true,
            lockscreenHostContainer = true,
            lockscreenGeometry = true,
            linkageDirection = true,
            raiseToAod = true,
            lockscreenEditorGesture = true
        )
        val capabilities = resolveXiaomiCapabilities(symbols)

        assertTrue(XiaomiCapability.AOD_SURFACE in capabilities)
        assertTrue(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertTrue(XiaomiCapability.AOD_WAKE_BROKER in capabilities)
        assertTrue(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_HOST in capabilities)
        assertTrue(XiaomiCapability.RAISE_TO_AOD in capabilities)
        assertTrue(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE in capabilities)
        assertEquals(
            XiaomiProfileState.EXPERIMENTAL_ACTIVE,
            resolveXiaomiProfileState(verifiedRuntimeProfile = false, capabilities = capabilities)
        )
    }

    @Test
    fun unverifiedSurfaceSymbolsRunButRemainFailClosedPerSymbol() {
        val symbols = XiaomiSymbolSnapshot(
            aodSurface = true,
            aodHostContainer = true
        )
        val capabilities = resolveXiaomiCapabilities(symbols)

        assertEquals(setOf(XiaomiCapability.AOD_SURFACE), capabilities)
        assertEquals(
            XiaomiProfileState.EXPERIMENTAL_ACTIVE,
            resolveXiaomiProfileState(verifiedRuntimeProfile = false, capabilities = capabilities)
        )
        assertTrue(symbols.rawProbes().getValue(XiaomiSymbolProbe.AOD_HOST_CONTAINER))
    }

    @Test
    fun unverifiedBuildWithoutAnySurfaceRemainsUnsupported() {
        val capabilities = resolveXiaomiCapabilities(
            XiaomiSymbolSnapshot(
                aodSurface = true,
                aodPositionUpdates = true,
                lockscreenHost = true
            )
        )

        assertFalse(XiaomiCapability.AOD_SURFACE in capabilities)
        assertFalse(XiaomiCapability.LOCKSCREEN_GEOMETRY in capabilities)
        assertEquals(
            XiaomiProfileState.UNSUPPORTED_PROFILE,
            resolveXiaomiProfileState(verifiedRuntimeProfile = false, capabilities = capabilities)
        )
    }

    @Test
    fun verifiedProfileMissingRequiredSeamReportsMissingSymbols() {
        val symbols = XiaomiSymbolSnapshot(
            aodSurface = true,
            aodHostContainer = false
        )
        val capabilities = resolveXiaomiCapabilities(symbols)

        assertEquals(
            XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS,
            resolveXiaomiProfileState(true, capabilities)
        )
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
