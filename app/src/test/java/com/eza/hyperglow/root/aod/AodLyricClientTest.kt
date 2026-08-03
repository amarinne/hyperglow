package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLyricClientTest {
    @Test
    fun retryDelayUsesExponentialBackoffWithThirtySecondCap() {
        assertEquals(1_000L, retryDelayMs(1))
        assertEquals(2_000L, retryDelayMs(2))
        assertEquals(4_000L, retryDelayMs(3))
        assertEquals(30_000L, retryDelayMs(6))
        assertEquals(30_000L, retryDelayMs(50))
    }

    @Test
    fun retryMarkersFollowSparseAttemptCurve() {
        assertTrue(shouldLogBindAttempt(1))
        assertTrue(shouldLogBindAttempt(2))
        assertTrue(shouldLogBindAttempt(3))
        assertFalse(shouldLogBindAttempt(4))
        assertTrue(shouldLogBindAttempt(5))
        assertTrue(shouldLogBindAttempt(10))
        assertTrue(shouldLogBindAttempt(20))
        assertFalse(shouldLogBindAttempt(21))
        assertTrue(shouldLogBindAttempt(50))
        assertFalse(shouldLogBindAttempt(51))
    }
}
