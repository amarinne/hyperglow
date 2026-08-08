package com.eza.hyperglow.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSetupPolicyTest {
    @Test
    fun completeVerifiedSetupIsReady() {
        val result = resolveHyperGlowSetupChecks(completeInput())

        assertEquals("ready", result.setupState)
        assertTrue(result.setupFailures.isEmpty())
        assertTrue(result.profileSupported)
        assertTrue(result.requiredPackagesPresent)
    }

    @Test
    fun unsupportedProfileAndRootDenialFailClosed() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(
                rootAccessStatus = "denied",
                profileState = "unsupported_profile"
            )
        )

        assertEquals("failed", result.setupState)
        assertTrue(result.setupFailures.contains("root_access"))
        assertTrue(result.setupFailures.contains("unsupported_profile"))
        assertFalse(result.profileSupported)
    }

    @Test
    fun experimentalActiveProfileIsReadyNotAnUnsupportedFailure() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(profileState = "experimental_active")
        )

        assertEquals("ready", result.setupState)
        assertTrue(result.setupFailures.isEmpty())
        assertTrue(result.profileSupported)
    }

    @Test
    fun verifiedProfileMissingSymbolsIsReadyNotAnUnsupportedFailure() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(profileState = "verified_profile_missing_symbols")
        )

        assertEquals("ready", result.setupState)
        assertTrue(result.profileSupported)
    }

    @Test
    fun retiredExperimentalEligibleProfileStillFailsClosed() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(profileState = "experimental_eligible")
        )

        assertEquals("failed", result.setupState)
        assertTrue(result.setupFailures.contains("unsupported_profile"))
        assertFalse(result.profileSupported)
    }

    @Test
    fun absentCapabilityReportDoesNotAlsoReportUnsupportedProfile() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(
                capabilityReportPresent = false,
                profileState = "no_systemui_report"
            )
        )

        assertEquals("failed", result.setupState)
        assertTrue(result.setupFailures.contains("capability_report"))
        assertFalse(result.setupFailures.contains("unsupported_profile"))
    }

    @Test
    fun absentSpotifyProducerIsWarningNotFalseCompatibilityFailure() {
        val result = resolveHyperGlowSetupChecks(
            completeInput().copy(spotifyProducerBridgePresent = false)
        )

        assertEquals("warning", result.setupState)
        assertEquals(listOf("spotify_bridge"), result.setupFailures)
    }

    private fun completeInput() = HyperGlowSetupInput(
        rootAccessStatus = "granted",
        capabilityReportPresent = true,
        systemUiCallbackPresent = true,
        profileState = "verified_profile",
        spotifyProducerBridgePresent = true,
        systemUiPackagePresent = true,
        xiaomiAodPackagePresent = true,
        spotifyPackagePresent = true
    )
}
