package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationBoundLatestTest {
    @Test
    fun latestMessageReplacesEarlierMessageForCurrentGeneration() {
        val pending = GenerationBoundLatest<String>()

        assertTrue(pending.offer(generation = 4L, currentGeneration = 4L, value = "first"))
        assertTrue(pending.offer(generation = 4L, currentGeneration = 4L, value = "latest"))

        assertEquals("latest", pending.take(currentGeneration = 4L))
        assertNull(pending.take(currentGeneration = 4L))
    }

    @Test
    fun staleGenerationIsRejectedAndCannotBeDelivered() {
        val pending = GenerationBoundLatest<String>()

        assertTrue(pending.offer(generation = 6L, currentGeneration = 6L, value = "current"))
        assertEquals(
            false,
            pending.offer(generation = 5L, currentGeneration = 6L, value = "stale")
        )

        assertEquals("current", pending.take(currentGeneration = 6L))
        assertNull(pending.take(currentGeneration = 6L))
    }
}
