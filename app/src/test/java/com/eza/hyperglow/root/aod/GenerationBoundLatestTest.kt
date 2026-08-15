package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.AodStateWireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun keepAliveNeverOverwritesAnUndeliveredFullState() {
        val fullState = hidden(revision = 20L)
        val keepAlive = keepAlive(revision = 21L)

        // The mailbox keeps one message. A keepalive taking that slot loses the only carrier of the
        // new revision, and every keepalive after it is rejected against the revision behind.
        assertFalse(shouldReplacePendingState(fullState, keepAlive))
        assertTrue(shouldReplacePendingState(null, keepAlive))
        assertTrue(shouldReplacePendingState(keepAlive, keepAlive(revision = 22L)))
        assertTrue(shouldReplacePendingState(fullState, hidden(revision = 21L)))
        assertTrue(shouldReplacePendingState(keepAlive, hidden(revision = 21L)))
    }

    private fun hidden(revision: Long) = AodStateWireMessage.Hidden(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = revision * 100L,
        keepAlive = true,
        wakeSignal = 1L,
        playbackActive = true
    )

    private fun keepAlive(revision: Long) = AodStateWireMessage.KeepAlive(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = revision * 100L,
        keepAlive = true,
        wakeSignal = 1L,
        playbackActive = true
    )
}
