package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AodProjectionIdentityTest {
    @Test
    fun trackGenerationIncludesProducerAndTrackIdentity() {
        val first = state("producer-a", "spotify:track:a")
        val restarted = first.copy(producerId = "producer-b")
        val switched = first.copy(trackUri = "spotify:track:b")

        assertNotEquals(
            AodProjectionEngine.trackGeneration(first),
            AodProjectionEngine.trackGeneration(restarted)
        )
        assertNotEquals(
            AodProjectionEngine.trackGeneration(first),
            AodProjectionEngine.trackGeneration(switched)
        )
    }

    private fun state(producerId: String, trackUri: String) = SpicyBridgeState(
        producerId = producerId,
        generation = 1,
        sequence = 1,
        status = "ready",
        trackUri = trackUri,
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "",
        line = "line",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 100,
        durationMs = 1_000,
        sampledAtElapsedMs = 100,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 100
    )
}
