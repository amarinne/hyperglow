package com.eza.hyperglow.aod

import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiProfileState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the app-side experimental-mode overlay on [StoredXiaomiCapabilityReport]:
 * when the hook reports EXPERIMENTAL_ELIGIBLE and the user enables experimental mode,
 * [StoredXiaomiCapabilityReport.has] should derive surface capabilities directly from
 * the raw symbol probes, and [StoredXiaomiCapabilityReport.supportState] should report
 * EXPERIMENTAL_ACTIVE. Mirrors the user's device where all 16 probes hit on AOD vc=22313001.
 */
class XiaomiCapabilityStoreExperimentalModeTest {

    private fun allProbesPresent(): Map<String, Boolean> = mapOf(
        "AOD_SURFACE_LIFECYCLE" to true,
        "AOD_HOST_CONTAINER" to true,
        "AOD_POSITION_UPDATE" to true,
        "AOD_POSITION_TARGET" to true,
        "AOD_LIFETIME_POLICY" to true,
        "AOD_WAKE_SEAM" to true,
        "LOCKSCREEN_SECTION_LIFECYCLE" to true,
        "LOCKSCREEN_CONTROLLER" to true,
        "LOCKSCREEN_HOST_CONTAINER" to true,
        "LOCKSCREEN_CLOCK_GEOMETRY" to true,
        "LINKAGE_DIRECTION" to true,
        "LINKAGE_GEOMETRY" to true,
        "RAISE_TO_AOD" to true,
        "LOCKSCREEN_EDITOR_GESTURE" to true,
        "FULL_AOD" to true,
        "VIDEO_DEPTH" to true
    )

    private fun eligibleReport(
        experimentalModeEnabled: Boolean,
        rawProbes: Map<String, Boolean> = allProbesPresent()
    ): StoredXiaomiCapabilityReport = StoredXiaomiCapabilityReport(
        protocolVersion = 2,
        systemUiVersion = "20250121.0(202501210)",
        aodVersion = "DEV-2313.0.0.1-11211901(22313001)",
        verifiedRuntimeProfile = false,
        capabilities = emptySet(),
        profileState = XiaomiProfileState.EXPERIMENTAL_ELIGIBLE,
        rawProbes = rawProbes,
        experimentalModeEnabled = experimentalModeEnabled
    )

    @Test
    fun eligibleWithoutToggleStaysEligibleAndHidesCapabilities() {
        val report = eligibleReport(experimentalModeEnabled = false)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE)
        // Probes are present, but the toggle is off → capabilities stay fail-closed
        // (hook端未上报,app 端也不推导)。
        assertFalse(report.has(XiaomiCapability.AOD_SURFACE))
        assertFalse(report.has(XiaomiCapability.LOCKSCREEN_HOST))
        assertFalse(report.has(XiaomiCapability.RAISE_TO_AOD))
    }

    @Test
    fun toggleUnlocksSurfaceCapabilitiesFromProbes() {
        val report = eligibleReport(experimentalModeEnabled = true)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE)
        assertTrue(report.has(XiaomiCapability.AOD_SURFACE))
        assertTrue(report.has(XiaomiCapability.AOD_POSITION_UPDATES))
        assertTrue(report.has(XiaomiCapability.AOD_LIFETIME_GUARD))
        assertTrue(report.has(XiaomiCapability.AOD_WAKE_BROKER))
        assertTrue(report.has(XiaomiCapability.LOCKSCREEN_HOST))
        assertTrue(report.has(XiaomiCapability.LOCKSCREEN_GEOMETRY))
        assertTrue(report.has(XiaomiCapability.LINKAGE_DIRECTION))
        assertTrue(report.has(XiaomiCapability.LINKAGE_GEOMETRY))
        assertTrue(report.has(XiaomiCapability.RAISE_TO_AOD))
        assertTrue(report.has(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE))
        assertTrue(report.has(XiaomiCapability.FULL_AOD))
        assertTrue(report.has(XiaomiCapability.VIDEO_DEPTH))
    }

    @Test
    fun toggleDoesNotOverrideUnsupportedProfile() {
        // profileState=UNSUPPORTED_PROFILE(符号没探到 surface seam),即使开关打开,
        // supportState 仍为 UNSUPPORTED,has() 仍 fail-closed。
        val report = eligibleReport(
            experimentalModeEnabled = true,
            rawProbes = allProbesPresent().mapValues { false }
        ).copy(profileState = XiaomiProfileState.UNSUPPORTED_PROFILE)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE)
        assertFalse(report.has(XiaomiCapability.AOD_SURFACE))
        assertFalse(report.has(XiaomiCapability.LOCKSCREEN_HOST))
    }

    @Test
    fun toggleFailsClosedWhenAodSurfaceSeamMissing() {
        // AOD_HOST_CONTAINER 缺失 → AOD_SURFACE 整族不推导,即便 AOD_POSITION_UPDATE
        // 等子符号在也不放开。
        val probes = allProbesPresent() + ("AOD_HOST_CONTAINER" to false)
        val report = eligibleReport(experimentalModeEnabled = true, rawProbes = probes)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE)
        assertFalse(report.has(XiaomiCapability.AOD_SURFACE))
        assertFalse(report.has(XiaomiCapability.AOD_POSITION_UPDATES))
        assertFalse(report.has(XiaomiCapability.FULL_AOD))
        // 锁屏 seam 仍完整 → 锁屏 capability 照常放开。
        assertTrue(report.has(XiaomiCapability.LOCKSCREEN_HOST))
    }

    @Test
    fun hookReportedExperimentalActiveStaysActiveWithoutToggle() {
        // hook 端自己判为 EXPERIMENTAL_ACTIVE 时,无需 app 端开关也判 active。
        val report = eligibleReport(experimentalModeEnabled = false)
            .copy(profileState = XiaomiProfileState.EXPERIMENTAL_ACTIVE)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE)
    }

    @Test
    fun noReportFailsClosedEvenWithExperimentalToggle() {
        val report = eligibleReport(experimentalModeEnabled = true)
            .copy(protocolVersion = 0)

        assertTrue(report.supportState() == XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT)
        assertFalse(report.has(XiaomiCapability.AOD_SURFACE))
    }
}
