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
    fun unknownPackageProfileRejectsMutatingAodCapabilities() {
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
                raiseToAod = true,
                lockscreenEditorGesture = true
            ),
            verifiedRuntimeProfile = false
        )

        assertFalse(XiaomiCapability.AOD_SURFACE in capabilities)
        assertFalse(XiaomiCapability.AOD_LIFETIME_GUARD in capabilities)
        assertFalse(XiaomiCapability.AOD_WAKE_BROKER in capabilities)
        assertFalse(XiaomiCapability.AOD_POSITION_UPDATES in capabilities)
        assertFalse(XiaomiCapability.LOCKSCREEN_HOST in capabilities)
        assertFalse(XiaomiCapability.RAISE_TO_AOD in capabilities)
        assertFalse(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE in capabilities)
    }

    @Test
    fun unverifiedSurfaceSymbolsAreReportableButRemainFailClosed() {
        val symbols = XiaomiSymbolSnapshot(
            aodSurface = true,
            aodHostContainer = true
        )
        val capabilities = resolveXiaomiCapabilities(
            symbols,
            verifiedRuntimeProfile = false
        )

        assertEquals(emptySet<XiaomiCapability>(), capabilities)
        assertEquals(
            XiaomiProfileState.EXPERIMENTAL_ELIGIBLE,
            resolveXiaomiProfileState(
                symbols,
                verifiedRuntimeProfile = false,
                capabilities = capabilities
            )
        )
        assertTrue(symbols.rawProbes().getValue(XiaomiSymbolProbe.AOD_HOST_CONTAINER))
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
            resolveXiaomiProfileState(symbols, true, capabilities)
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

    @Test
    fun aodVersionCode22313001IsAcceptedAsVerified() {
        // 用户设备实测:AOD vc=22313001,所有 16 个符号 probe 全部命中,
        // 结构与验证版 22327001 一致 —— 纳入白名单后应判为 verified。
        assertTrue(
            XiaomiCapabilityResolver.isVerifiedRuntimeProfile(
                "20250121.0(202501210)",
                "DEV-2313.0.0.1-11211901(22313001)"
            )
        )
        // SystemUI vc 不匹配仍拒绝。
        assertFalse(
            XiaomiCapabilityResolver.isVerifiedRuntimeProfile(
                "unknown(999999999)",
                "DEV-2313.0.0.1-11211901(22313001)"
            )
        )
        // AOD vc 不在白名单仍拒绝。
        assertFalse(
            XiaomiCapabilityResolver.isVerifiedRuntimeProfile(
                "20250121.0(202501210)",
                "DEV-9999.0.0.1(99999999)"
            )
        )
    }
}
