package com.eza.hyperglow.aod

import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiProfileState
import com.eza.hyperglow.root.capability.XiaomiSymbolProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiCapabilityProtocolTest {
    @Test
    fun v1ReportRemainsAcceptedDuringSystemUiTransition() {
        val report = XiaomiCapabilityBundleCodec.decodeXiaomiCapabilityPayload(
            XiaomiCapabilityWirePayload(
                protocolVersion = 1,
                systemUiVersion = "systemui",
                aodVersion = "aod",
                verifiedRuntimeProfile = true,
                capabilityNames = listOf(
                    XiaomiCapability.AOD_SURFACE.name,
                    XiaomiCapability.LOCKSCREEN_HOST.name,
                    XiaomiCapability.LOCKSCREEN_GEOMETRY.name
                )
            )
        )

        assertEquals(1, report?.protocolVersion)
        assertEquals(XiaomiProfileState.VERIFIED_PROFILE, report?.profileState)
        assertTrue(report?.rawProbes?.isEmpty() == true)
    }

    @Test
    fun v2ReportCarriesBoundedRawProbesAndEffectiveState() {
        val report = XiaomiCapabilityBundleCodec.decodeXiaomiCapabilityPayload(
            XiaomiCapabilityWirePayload(
                protocolVersion = 2,
                reportedAtUtcMillis = 123L,
                profileState = XiaomiProfileState.EXPERIMENTAL_ELIGIBLE.wireValue,
                probeNames = listOf(
                    XiaomiSymbolProbe.AOD_SURFACE_LIFECYCLE.name,
                    XiaomiSymbolProbe.AOD_HOST_CONTAINER.name,
                    "UNKNOWN_FUTURE_PROBE"
                ),
                presentProbeNames = listOf(XiaomiSymbolProbe.AOD_HOST_CONTAINER.name)
            )
        )

        assertEquals(2, report?.protocolVersion)
        assertEquals(XiaomiProfileState.EXPERIMENTAL_ELIGIBLE, report?.profileState)
        assertEquals(false, report?.rawProbes?.get(XiaomiSymbolProbe.AOD_SURFACE_LIFECYCLE))
        assertEquals(true, report?.rawProbes?.get(XiaomiSymbolProbe.AOD_HOST_CONTAINER))
        assertEquals(2, report?.rawProbes?.size)
    }

    @Test
    fun unknownOrOversizedProtocolFailsClosed() {
        assertNull(
            XiaomiCapabilityBundleCodec.decodeXiaomiCapabilityPayload(
                XiaomiCapabilityWirePayload(protocolVersion = 99)
            )
        )
        assertNull(
            XiaomiCapabilityBundleCodec.decodeXiaomiCapabilityPayload(
                XiaomiCapabilityWirePayload(
                    protocolVersion = 2,
                    profileState = XiaomiProfileState.UNSUPPORTED_PROFILE.wireValue,
                    probeNames = List(33) { XiaomiSymbolProbe.AOD_SURFACE_LIFECYCLE.name }
                )
            )
        )
    }
}
