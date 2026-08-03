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
