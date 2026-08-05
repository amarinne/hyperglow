package com.eza.hyperglow.producer

import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SpicyLyricProducer.toProducerState] — the Spicy EX → boundary normalization.
 *
 * Covers spec clause 5 of `lyric-producer-contract.spec.md`:
 * "A LyricProducer MUST normalize its ingress payload into LyricProducerState before emitting;
 *  the spotify:track: constraint MUST remain internal to SpicyBridgeStateReducer and MUST NOT be
 *  re-imposed at the boundary."
 *
 * The mapping is verified lossless on every shared field and correct on the render-mode
 * (liveCard* → ProducerRenderModes) mapping. We construct [SpicyBridgeState] directly (it is a
 * public data class) and call the internal [SpicyLyricProducer.toProducerState] synchronously,
 * avoiding any coroutine/Android-framework dependency.
 */
class SpicyLyricProducerTest {

    private val producer = SpicyLyricProducer()

    private fun spicyState(
        trackUri: String = "spotify:track:abc",
        liveCardWeight: String = "Bold",
        liveCardTextSize: String = "large",
        liveCardTextSizeCustom: Int = 140,
        liveCardSecondaryMode: String = "Translation",
        liveCardAnimation: String = "Spotlight word",
        liveCardGlow: String = "Soft",
        liveCardLineSyncFill: String = "Left to right",
        liveCardOverflow: String = "Marquee",
        liveCardTransition: String = "Crossfade",
        lyricsFont: String = "sans-serif"
    ) = SpicyBridgeState(
        producerId = "spicy-prod",
        generation = 7,
        sequence = 42L,
        status = "ready",
        trackUri = trackUri,
        title = "Title",
        artist = "Artist",
        album = "Album",
        imageId = "img-1",
        line = "current line",
        romanizedLine = "romanized",
        translatedLine = "translated",
        lineIndex = 3,
        positionMs = 12_000L,
        durationMs = 180_000L,
        sampledAtElapsedMs = 99_000L,
        speed = 1.5f,
        playing = true,
        receivedAtElapsedMs = 100_000L,
        liveCardWeight = liveCardWeight,
        liveCardTextSize = liveCardTextSize,
        liveCardTextSizeCustom = liveCardTextSizeCustom,
        liveCardSecondaryMode = liveCardSecondaryMode,
        liveCardAnimation = liveCardAnimation,
        liveCardGlow = liveCardGlow,
        liveCardLineSyncFill = liveCardLineSyncFill,
        liveCardOverflow = liveCardOverflow,
        liveCardTransition = liveCardTransition,
        lyricsFont = lyricsFont
    )

    @Test
    fun mapsAllSharedFieldsLosslessly() {
        val spicy = spicyState()

        val mapped = producer.toProducerState(spicy)

        assertEquals("spicy-prod", mapped.producerId)
        assertEquals(7, mapped.generation)
        assertEquals(42L, mapped.sequence)
        assertEquals("ready", mapped.status)
        assertEquals("spotify:track:abc", mapped.trackUri)
        assertEquals("Title", mapped.title)
        assertEquals("Artist", mapped.artist)
        assertEquals("Album", mapped.album)
        assertEquals("img-1", mapped.imageId)
        assertEquals("current line", mapped.line)
        assertEquals("romanized", mapped.romanizedLine)
        assertEquals("translated", mapped.translatedLine)
        assertEquals(3, mapped.lineIndex)
        assertEquals(12_000L, mapped.positionMs)
        assertEquals(180_000L, mapped.durationMs)
        assertEquals(99_000L, mapped.sampledAtElapsedMs)
        assertEquals(1.5f, mapped.speed, 0.0001f)
        assertEquals(true, mapped.playing)
        assertEquals(100_000L, mapped.receivedAtElapsedMs)
    }

    @Test
    fun wordsAreNullForLineLevelSpicyState() {
        // SpicyBridgeState carries no per-word timing (that lives in SpicyBridgeDocumentStore);
        // the boundary MUST emit null words for the line-level Spicy path.
        val mapped = producer.toProducerState(spicyState())
        assertNull(mapped.words)
    }

    @Test
    fun renderModesMappedFromLiveCardFieldsOneToOne() {
        val spicy = spicyState(
            liveCardWeight = "Bold",
            liveCardTextSize = "large",
            liveCardTextSizeCustom = 140,
            liveCardSecondaryMode = "Translation",
            liveCardAnimation = "Spotlight word",
            liveCardGlow = "Soft",
            liveCardLineSyncFill = "Left to right",
            liveCardOverflow = "Marquee",
            liveCardTransition = "Crossfade",
            lyricsFont = "sans-serif"
        )

        val modes = producer.toProducerState(spicy).renderModes

        assertEquals("Bold", modes.weight)
        assertEquals("large", modes.textSize)
        assertEquals(140, modes.textSizeCustom)
        assertEquals("Translation", modes.secondary)
        assertEquals("Spotlight word", modes.animation)
        assertEquals("Soft", modes.glow)
        assertEquals("Left to right", modes.lineSyncFill)
        assertEquals("Marquee", modes.overflow)
        assertEquals("Crossfade", modes.transition)
        assertEquals("sans-serif", modes.font)
    }

    @Test
    fun boundaryDoesNotReImposeSpotifyTrackConstraint() {
        // The wrapper maps whatever trackUri SpicyBridgeState carries — it MUST NOT reject or
        // rewrite a non-spotify URI. The constraint lives in SpicyBridgeStateReducer (which
        // would never produce such a state), but the boundary itself is agnostic.
        val nonSpotify = spicyState(trackUri = "content://media/external/audio/123")

        val mapped = producer.toProducerState(nonSpotify)

        assertEquals("content://media/external/audio/123", mapped.trackUri)
    }

    @Test
    fun staleAfterMsUsesUniformConstant() {
        // Spec invariant: STALE_AFTER_MS = 3000ms uniformly for both producers.
        val mapped = producer.toProducerState(spicyState())
        assertEquals(LyricProducerState.STALE_AFTER_MS, mapped.staleAfterMs)
        assertEquals(3_000L, mapped.staleAfterMs)
    }

    @Test
    fun defaultRenderModesMapWhenLiveCardFieldsAreDefaults() {
        val spicy = SpicyBridgeState(
            producerId = "p", generation = 1, sequence = 1L, status = "ready",
            trackUri = "spotify:track:x", title = "", artist = "", album = "", imageId = "",
            line = "", romanizedLine = "", translatedLine = "", lineIndex = 0,
            positionMs = 0L, durationMs = 1_000L, sampledAtElapsedMs = 0L, speed = 1f,
            playing = true, receivedAtElapsedMs = 0L
        )

        val modes = producer.toProducerState(spicy).renderModes

        // SpicyBridgeState defaults per the data class definition.
        assertEquals("Medium", modes.weight)
        assertEquals("normal", modes.textSize)
        assertEquals(100, modes.textSizeCustom)
        assertEquals("Main only", modes.secondary)
        assertEquals("Karaoke fill", modes.animation)
        assertEquals("Off", modes.glow)
        assertEquals("Top to bottom", modes.lineSyncFill)
        assertEquals("Wrap", modes.overflow)
        assertEquals("Fade up", modes.transition)
        assertEquals("spotify", modes.font)
    }

    @Test
    fun activeRowFieldsEmitDefaultsUntilDocumentCouplingMigrated() {
        // Spec clause 9: until the Spicy producer is wired to SpicyBridgeDocumentStore for the
        // active-row fields, it emits them at defaults. AodProjectionEngine still reads the
        // document store directly for its project() internals on the Spicy path. This test locks
        // that documented deviation so the engine-switch step can detect when it changes.
        val mapped = producer.toProducerState(spicyState())

        assertEquals(LyricKind.NONE, mapped.lyricKind)
        assertEquals(false, mapped.hasTimedLyrics)
        assertEquals(false, mapped.alignedRight)
        assertEquals(0L, mapped.lineStartMs)
        assertEquals(0L, mapped.lineEndMs)
        assertNull(mapped.nextLineStartMs)
        assertTrue(mapped.ruby.isEmpty())
        assertTrue(mapped.layoutGroups.isEmpty())
    }
}
