package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodPowerSessionPolicyTest {
    private val first = SpicyPowerSessionState(
        session = ProjectionSessionIdentity("producer", 1, "track:1"),
        playing = true,
        aodEnabled = true,
        keepAwake = true
    )

    @Test
    fun songChangeLeaseBridgesLoadingUntilTimedLyricsArrive() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        assertTrue(policy.resolve(first, 1_000L, persistentKeepAlive = false).keepAlive)
        assertTrue(policy.resolve(first, 7_000L, persistentKeepAlive = false).keepAlive)
        assertTrue(policy.resolve(first, 7_500L, persistentKeepAlive = true).keepAlive)
        assertTrue(policy.resolve(first, 20_000L, persistentKeepAlive = true).keepAlive)
    }

    @Test
    fun untimedSongExpiresNaturallyAfterPresentationLease() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        assertTrue(policy.resolve(first, 1_000L, persistentKeepAlive = false).keepAlive)
        val expired = policy.resolve(first, 9_000L, persistentKeepAlive = false)
        assertFalse(expired.keepAlive)
        assertFalse(expired.presentationLeaseActive)
    }

    @Test
    fun newGenerationStartsFreshLeaseAfterPreviousSongExpired() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)
        policy.resolve(first, 1_000L, persistentKeepAlive = false)
        assertFalse(policy.resolve(first, 9_000L, persistentKeepAlive = false).keepAlive)

        val next = first.copy(
            session = ProjectionSessionIdentity("producer", 2, "track:2")
        )
        assertTrue(policy.resolve(next, 9_001L, persistentKeepAlive = false).keepAlive)
    }

    @Test
    fun disabledMasterPolicyNeverStartsLease() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        assertFalse(policy.resolve(first.copy(keepAwake = false), 1_000L, false).keepAlive)
        assertFalse(policy.resolve(first.copy(aodEnabled = false), 1_000L, false).keepAlive)
        assertFalse(policy.resolve(first.copy(playing = false), 1_000L, false).keepAlive)
    }
}
