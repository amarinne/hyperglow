package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodProjectionLifecycleTest {
    @Test
    fun terminalInvalidationRejectsInFlightVisiblePublication() {
        val guard = ProjectionPublicationGuard()
        val state = state()
        val document = Any()
        val token = requireNotNull(guard.begin(state))

        guard.invalidate()

        assertFalse(guard.canPublish(token, state, state, document, document))
    }

    @Test
    fun newerSameSessionStateRejectsOlderCandidate() {
        val guard = ProjectionPublicationGuard()
        val first = state(sequence = 1L)
        val firstToken = requireNotNull(guard.begin(first))
        val newer = first.copy(sequence = 2L, receivedAtElapsedMs = 20L)
        val newerToken = requireNotNull(guard.begin(newer))
        val document = Any()

        assertFalse(guard.canPublish(firstToken, first, newer, document, document))
        assertTrue(guard.canPublish(newerToken, newer, newer, document, document))
    }

    @Test
    fun documentReplacementRejectsLayoutBuiltFromOldDocument() {
        val guard = ProjectionPublicationGuard()
        val state = state()
        val token = requireNotNull(guard.begin(state))
        val oldDocument = Any()
        val newDocument = Any()

        assertFalse(guard.canPublish(token, state, state, oldDocument, newDocument))
    }

    @Test
    fun sessionIdentityIncludesTrackAndCurrentTokenRejectsOldScheduler() {
        val guard = ProjectionPublicationGuard()
        val first = state(trackUri = "spotify:track:first")
        guard.begin(first)
        val switched = first.copy(trackUri = "spotify:track:second")

        guard.begin(switched)

        assertNotNull(guard.current(switched))
        assertTrue(guard.current(first) == null)
    }

    @Test
    fun cancelledDelayedReleaseCannotRetireNewSession() {
        val gate = ProjectionReleaseGate()
        val oldRelease = gate.schedule()

        gate.cancel()
        val newRelease = gate.schedule()

        assertFalse(gate.isCurrent(oldRelease))
        assertTrue(gate.isCurrent(newRelease))
    }

    private fun state(
        sequence: Long = 1L,
        trackUri: String = "spotify:track:test"
    ) = SpicyBridgeState(
        producerId = "producer",
        generation = 7,
        sequence = sequence,
        status = "ready",
        trackUri = trackUri,
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "image",
        line = "line",
        romanizedLine = "reading",
        translatedLine = "translation",
        lineIndex = 0,
        positionMs = 100L,
        durationMs = 1_000L,
        sampledAtElapsedMs = 10L,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 10L
    )
}
