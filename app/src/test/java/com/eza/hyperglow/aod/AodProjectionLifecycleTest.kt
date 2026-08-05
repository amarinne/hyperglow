package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
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
        val token = requireNotNull(guard.begin(state))

        guard.invalidate()

        assertFalse(guard.canPublish(token, state, state))
    }

    @Test
    fun newerSameSessionStateRejectsOlderCandidate() {
        val guard = ProjectionPublicationGuard()
        val first = state(sequence = 1L)
        val firstToken = requireNotNull(guard.begin(first))
        val newer = first.copy(sequence = 2L, receivedAtElapsedMs = 20L)
        val newerToken = requireNotNull(guard.begin(newer))

        assertFalse(guard.canPublish(firstToken, first, newer))
        assertTrue(guard.canPublish(newerToken, newer, newer))
    }

    @Test
    fun supersededStateRejectsLayoutBuiltFromPriorState() {
        // Phase 3: the document-reference check is gone; the active row is part of
        // LyricProducerState itself. A layout built from a prior state is rejected because
        // `candidate` (the prior state) no longer equals `current` (the arbiter's active state).
        val guard = ProjectionPublicationGuard()
        val first = state()
        val token = requireNotNull(guard.begin(first))
        val replaced = first.copy(sequence = 2L)

        guard.begin(replaced)

        assertFalse(guard.canPublish(token, first, replaced))
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
    ) = LyricProducerState(
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
        receivedAtElapsedMs = 10L,
        words = null,
        renderModes = ProducerRenderModes(
            weight = "Medium",
            textSize = "normal",
            textSizeCustom = 100,
            secondary = "Main only",
            animation = "Karaoke fill",
            glow = "Off",
            lineSyncFill = "Top to bottom",
            overflow = "Wrap",
            transition = "Fade up",
            font = "spotify"
        )
    )
}
