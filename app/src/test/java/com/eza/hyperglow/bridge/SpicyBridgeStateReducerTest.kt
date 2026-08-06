package com.eza.hyperglow.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyBridgeStateReducerTest {
    @Test
    fun validPayloadIsNormalizedAndAccepted() {
        val reducer = SpicyBridgeStateReducer()

        assertTrue(reducer.accept(payload(renderModes = unknownRenderModes()), NOW_MS))

        val accepted = reducer.current ?: error("missing accepted state")
        assertEquals("Medium", accepted.liveCardWeight)
        assertEquals("Transliteration", accepted.liveCardSecondaryMode)
        assertEquals("Spotlight word", accepted.liveCardAnimation)
        assertEquals(500, accepted.liveCardTextSizeCustom)
        assertEquals(NOW_MS, accepted.receivedAtElapsedMs)
    }

    @Test
    fun protocolIdentityAndBoundsRejectWithoutReplacingCurrentState() {
        val reducer = SpicyBridgeStateReducer()
        assertTrue(reducer.accept(payload(), NOW_MS))
        val accepted = reducer.current

        assertFalse(reducer.accept(payload(protocolVersion = 2, sequence = 2), NOW_MS))
        assertFalse(reducer.accept(payload(producerId = "", sequence = 2), NOW_MS))
        assertFalse(reducer.accept(payload(trackUri = "not-spotify", sequence = 2), NOW_MS))
        assertFalse(reducer.accept(payload(line = "x".repeat(8_193), sequence = 2), NOW_MS))
        assertFalse(
            reducer.accept(
                payload(
                    sequence = 2,
                    renderModes = defaultRenderModes().copy(weight = "x".repeat(17))
                ),
                NOW_MS
            )
        )
        assertEquals(accepted, reducer.current)
    }

    @Test
    fun timingStatusAndSpeedRejectInvalidValues() {
        val reducer = SpicyBridgeStateReducer()

        assertFalse(reducer.accept(payload(positionMs = -1), NOW_MS))
        assertFalse(reducer.accept(payload(positionMs = 3_001), NOW_MS))
        assertFalse(reducer.accept(payload(sampledAtElapsedMs = NOW_MS - 60_001), NOW_MS))
        assertFalse(reducer.accept(payload(sampledAtElapsedMs = NOW_MS + 1_001), NOW_MS))
        assertFalse(reducer.accept(payload(speed = Float.NaN), NOW_MS))
        assertFalse(reducer.accept(payload(speed = 4.01f), NOW_MS))
        assertFalse(reducer.accept(payload(status = "paused"), NOW_MS))
        assertNull(reducer.current)
    }

    @Test
    fun duplicateAndOlderSessionStateCannotReplaceAcceptedState() {
        val reducer = SpicyBridgeStateReducer()
        assertTrue(reducer.accept(payload(generation = 4, sequence = 8), NOW_MS))

        assertFalse(reducer.accept(payload(generation = 4, sequence = 8), NOW_MS))
        assertFalse(reducer.accept(payload(generation = 4, sequence = 7), NOW_MS))
        assertFalse(reducer.accept(payload(generation = 3, sequence = 20), NOW_MS))
        assertTrue(reducer.accept(payload(generation = 5, sequence = 1), NOW_MS))

        assertEquals(5, reducer.current?.generation)
        assertEquals(1L, reducer.current?.sequence)
    }

    @Test
    fun tombstoneRejectsRetiredGenerationButAllowsNewerGeneration() {
        val reducer = SpicyBridgeStateReducer()
        assertTrue(reducer.accept(payload(generation = 4), NOW_MS))

        reducer.clear(PRODUCER_ID, 4)

        assertNull(reducer.current)
        assertFalse(reducer.accept(payload(generation = 4, sequence = 2), NOW_MS))
        assertFalse(reducer.accept(payload(generation = 3, sequence = 2), NOW_MS))
        assertTrue(reducer.accept(payload(generation = 5), NOW_MS))
    }

    @Test
    fun staleStateExpiresAndAValidReplacementCanBeAccepted() {
        val reducer = SpicyBridgeStateReducer()
        assertTrue(reducer.accept(payload(generation = 4), NOW_MS))

        assertFalse(reducer.expireIfStale(NOW_MS + SpicyBridgeStore.STALE_AFTER_MS))
        assertTrue(reducer.expireIfStale(NOW_MS + SpicyBridgeStore.STALE_AFTER_MS + 1))
        assertNull(reducer.current)
        assertTrue(reducer.accept(payload(generation = 3, sequence = 2), NOW_MS + 4_000))
    }

    @Test
    fun reprocessedTextOnTheSameSequenceReplacesHeldText() {
        val reducer = SpicyBridgeStateReducer()
        assertTrue(reducer.accept(payload(generation = 4, sequence = 8), NOW_MS))

        // The producer reprocessed the playing song — transliteration mode changed, or its own
        // cache was cleared — and republished the same logical update with revised text. Dropping
        // this as stale left the old romanization on screen for the rest of the song.
        assertTrue(
            reducer.accept(
                payload(generation = 4, sequence = 8, romanizedLine = "revised"),
                NOW_MS
            )
        )
        assertEquals("revised", reducer.current?.romanizedLine)

        // An exact repeat is still a duplicate and is still dropped.
        assertFalse(
            reducer.accept(
                payload(generation = 4, sequence = 8, romanizedLine = "revised"),
                NOW_MS
            )
        )
    }

    private fun payload(
        protocolVersion: Int = SpicyBridgeStore.PROTOCOL_VERSION,
        producerId: String = PRODUCER_ID,
        generation: Int = 4,
        sequence: Long = 1,
        status: String = "ready",
        trackUri: String = "spotify:track:test",
        line: String = "line",
        romanizedLine: String = "romanized",
        positionMs: Long = 1_000,
        sampledAtElapsedMs: Long = NOW_MS,
        speed: Float = 1f,
        renderModes: SpicyBridgeRenderModes = defaultRenderModes()
    ) = SpicyBridgeStatePayload(
        protocolVersion = protocolVersion,
        producerId = producerId,
        generation = generation,
        sequence = sequence,
        status = status,
        trackUri = trackUri,
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "image",
        line = line,
        romanizedLine = romanizedLine,
        translatedLine = "translated",
        lineIndex = 2,
        positionMs = positionMs,
        durationMs = 3_000,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        playing = true,
        renderModes = renderModes
    )

    private fun defaultRenderModes() = SpicyBridgeRenderModes(
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

    private fun unknownRenderModes() = defaultRenderModes().copy(
        weight = "unknown",
        textSizeCustom = 900,
        secondary = "Romanized",
        animation = "Full"
    )

    companion object {
        private const val PRODUCER_ID = "producer"
        private const val NOW_MS = 100_000L
    }
}
