package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRow
import org.junit.Assert.assertEquals
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

    @Test
    fun keepAliveTimingRequiresTimedTypeAndPositiveRowDuration() {
        assertTrue(AodProjectionEngine.hasActualLyricTiming(document("Line", 100L, 200L)))
        assertTrue(AodProjectionEngine.hasActualLyricTiming(document("Syllable", 100L, 200L)))
        assertFalse(AodProjectionEngine.hasActualLyricTiming(document("Static", 100L, 200L)))
        assertFalse(AodProjectionEngine.hasActualLyricTiming(document("Line", 100L, 100L)))
        assertFalse(AodProjectionEngine.hasActualLyricTiming(document("Syllable", 0L, 0L)))
    }

    @Test
    fun keepAlivePolicyDefaultsToTimedButAllowsExplicitUnsyncedOverride() {
        assertTrue(AodProjectionEngine.shouldKeepAodAlive(true, true, true, false, true))
        assertFalse(AodProjectionEngine.shouldKeepAodAlive(true, true, true, false, false))
        assertTrue(AodProjectionEngine.shouldKeepAodAlive(true, true, true, true, false))
        assertFalse(AodProjectionEngine.shouldKeepAodAlive(false, true, true, true, true))
        assertFalse(AodProjectionEngine.shouldKeepAodAlive(true, false, true, true, true))
        assertFalse(AodProjectionEngine.shouldKeepAodAlive(true, true, false, true, true))
    }

    @Test
    fun songChangeAndLaterTimedAvailabilityHaveDistinctWakeSignals() {
        val state = state()
        val songChange = AodProjectionEngine.sessionWakeSignal(state, hasTimedLyrics = false)
        val timedAvailable = AodProjectionEngine.sessionWakeSignal(state, hasTimedLyrics = true)

        assertTrue(songChange != 0L)
        assertTrue(timedAvailable != 0L)
        assertTrue(songChange != timedAvailable)
    }

    @Test
    fun loadingPresentationPrefersCurrentMetadataOverAnyStaleLine() {
        assertEquals(
            "New song · Artist",
            AodProjectionEngine.playbackFallback(
                "loading",
                "previous lyric",
                "New song · Artist"
            )
        )
        assertEquals(
            "current lyric",
            AodProjectionEngine.playbackFallback("ready", "current lyric", "Song · Artist")
        )
    }

    @Test
    fun stillPlayingTransportGapKeepsTheKeepAliveIntentAsserted() {
        // A song change publishes a release that is still playing. Dropping keepalive there let the
        // SystemUI coordinator withdraw Xiaomi lifetime suppression for the length of the gap, and
        // a long boundary lost the panel inside that window.
        assertEquals(
            true,
            AodProjectionEngine.retainedTransportGapKeepAlive(
                playbackActive = true,
                lastKeepAliveIntent = true
            )
        )
    }

    @Test
    fun aRealReleaseCarriesNoKeepAliveIntent() {
        assertEquals(
            false,
            AodProjectionEngine.retainedTransportGapKeepAlive(
                playbackActive = false,
                lastKeepAliveIntent = true
            )
        )
        assertEquals(
            false,
            AodProjectionEngine.retainedTransportGapKeepAlive(
                playbackActive = true,
                lastKeepAliveIntent = false
            )
        )
    }

    @Test
    fun preservedTimedDocumentIsReusedOnlyByTheExactReturningSession() {
        val document = document("Syllable", 100L, 200L)
        val sameSession = state()

        assertFalse(AodProjectionEngine.shouldClearMismatchedDocument(document, sameSession))
        assertTrue(
            AodProjectionEngine.shouldClearMismatchedDocument(
                document,
                sameSession.copy(generation = sameSession.generation + 1)
            )
        )
        assertTrue(
            AodProjectionEngine.shouldClearMismatchedDocument(
                document,
                sameSession.copy(trackUri = "spotify:track:next")
            )
        )
        assertTrue(
            AodProjectionEngine.shouldClearMismatchedDocument(
                document,
                sameSession.copy(durationMs = sameSession.durationMs + 1L)
            )
        )
        assertFalse(AodProjectionEngine.shouldClearMismatchedDocument(null, sameSession))
    }

    @Test
    fun abandonedGapClearsOnlyTheDocumentCapturedWhenTheGapBegan() {
        val retained = document("Syllable", 100L, 200L)
        val replacement = retained.copy(processingVersion = 2)

        assertTrue(AodProjectionEngine.shouldClearRetainedDocument(retained, retained, null))
        assertFalse(AodProjectionEngine.shouldClearRetainedDocument(retained, replacement, null))
        assertFalse(AodProjectionEngine.shouldClearRetainedDocument(retained, retained, state()))
        assertFalse(AodProjectionEngine.shouldClearRetainedDocument(null, null, null))
    }

    private fun document(type: String, startMs: Long, endMs: Long) = SpicyBridgeDocument(
        producerId = "producer",
        generation = 7,
        trackUri = "spotify:track:test",
        provider = "test",
        language = "en",
        type = type,
        durationMs = 1_000L,
        processingVersion = 1,
        rows = listOf(
            SpicyBridgeRow(
                role = "LEAD",
                startMs = startMs,
                endMs = endMs,
                fillEndMs = endMs,
                alignedRight = false,
                text = "line",
                romanized = "",
                translated = "",
                words = emptyList()
            )
        )
    )

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
