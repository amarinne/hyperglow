package com.eza.hyperglow.producer

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeLayoutGroup
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.bridge.SpicyBridgeRuby
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SpicyLyricProducer.toProducerState] — the Spicy EX → boundary normalization.
 *
 * Covers spec clause 5 (normalize ingress into [LyricProducerState]; `spotify:track:` stays
 * internal to `SpicyBridgeStateReducer`) and spec clause 9 (the Spicy producer populates the
 * active-row fields from `SpicyBridgeDocumentStore`, computing the active row via
 * `primaryRowAt` at the **sampled position**).
 *
 * We construct [SpicyBridgeState] / [SpicyBridgeDocument] directly (both are public data
 * classes) and call the internal [SpicyLyricProducer.toProducerState] synchronously, avoiding
 * any coroutine/Android-framework dependency.
 */
class SpicyLyricProducerTest {

    private val producer = SpicyLyricProducer()

    private fun spicyState(
        trackUri: String = "spotify:track:abc",
        status: String = "ready",
        line: String = "current line",
        romanizedLine: String = "romanized",
        translatedLine: String = "translated",
        positionMs: Long = 12_000L,
        durationMs: Long = 180_000L,
        lineIndex: Int = 3,
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
        status = status,
        trackUri = trackUri,
        title = "Title",
        artist = "Artist",
        album = "Album",
        imageId = "img-1",
        line = line,
        romanizedLine = romanizedLine,
        translatedLine = translatedLine,
        lineIndex = lineIndex,
        positionMs = positionMs,
        durationMs = durationMs,
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

    private fun row(
        role: String = "LEAD",
        startMs: Long,
        endMs: Long,
        text: String,
        romanized: String = "",
        translated: String = "",
        alignedRight: Boolean = false,
        words: List<SpicyBridgeWord> = emptyList(),
        ruby: List<SpicyBridgeRuby> = emptyList(),
        layoutGroups: List<SpicyBridgeLayoutGroup> = emptyList()
    ) = SpicyBridgeRow(
        role = role,
        startMs = startMs,
        endMs = endMs,
        fillEndMs = endMs,
        alignedRight = alignedRight,
        text = text,
        romanized = romanized,
        translated = translated,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups
    )

    private fun word(startMs: Long, endMs: Long, text: String, boundaryAfter: Boolean = false) =
        SpicyBridgeWord(text, "", startMs, endMs, boundaryAfter)

    private fun document(
        type: String,
        rows: List<SpicyBridgeRow>,
        producerId: String = "spicy-prod",
        generation: Int = 7,
        trackUri: String = "spotify:track:abc",
        durationMs: Long = 180_000L
    ) = SpicyBridgeDocument(
        producerId = producerId,
        generation = generation,
        trackUri = trackUri,
        provider = "test",
        language = "en",
        type = type,
        durationMs = durationMs,
        processingVersion = 1,
        rows = rows
    )

    // --- spec clause 5: shared-field lossless mapping (no document) ---

    @Test
    fun mapsAllSharedFieldsLosslessly() {
        val spicy = spicyState()

        val mapped = producer.toProducerState(spicy, document = null)

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
        assertEquals(12_000L, mapped.positionMs)
        assertEquals(180_000L, mapped.durationMs)
        assertEquals(99_000L, mapped.sampledAtElapsedMs)
        assertEquals(1.5f, mapped.speed, 0.0001f)
        assertEquals(true, mapped.playing)
        assertEquals(100_000L, mapped.receivedAtElapsedMs)
    }

    @Test
    fun withoutDocument_emitsNoneKindAndPreservesIngressLineForFallback() {
        // No document → lyricKind NONE, no active row, but ingress `line`/`romanized`/`translated`
        // preserved so projection's fallback-line branch (document==null, status=="ready") can
        // use them. lineIndex is -1 (no row selected from a document).
        val mapped = producer.toProducerState(spicyState(), document = null)

        assertEquals(LyricKind.NONE, mapped.lyricKind)
        assertFalse(mapped.hasTimedLyrics)
        assertEquals(-1, mapped.lineIndex)
        assertEquals(0L, mapped.lineStartMs)
        assertEquals(0L, mapped.lineEndMs)
        assertFalse(mapped.alignedRight)
        assertNull(mapped.words)
        assertNull(mapped.nextLineStartMs)
        assertTrue(mapped.ruby.isEmpty())
        assertTrue(mapped.layoutGroups.isEmpty())
        assertEquals("current line", mapped.line)
        assertEquals("romanized", mapped.romanizedLine)
        assertEquals("translated", mapped.translatedLine)
    }

    @Test
    fun wordsAreNullWhenNoTimedDocument() {
        // No document → null words (line-level per LyricProducerState contract).
        val mapped = producer.toProducerState(spicyState(), document = null)
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

        val modes = producer.toProducerState(spicy, document = null).renderModes

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
        val nonSpotify = spicyState(trackUri = "content://media/external/audio/123")

        val mapped = producer.toProducerState(nonSpotify, document = null)

        assertEquals("content://media/external/audio/123", mapped.trackUri)
    }

    @Test
    fun staleAfterMsUsesUniformConstant() {
        val mapped = producer.toProducerState(spicyState(), document = null)
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

        val modes = producer.toProducerState(spicy, document = null).renderModes

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

    // --- spec clause 9: active-row computation from SpicyBridgeDocument ---

    @Test
    fun lineDocument_selectsActiveRowAtSampledPositionAndEmitsLineKind() {
        val doc = document(
            type = "Line",
            rows = listOf(
                row(startMs = 1_000, endMs = 3_000, text = "first"),
                row(startMs = 3_500, endMs = 5_000, text = "second", alignedRight = true)
            )
        )
        // positionMs = 4_000 → second line.
        val spicy = spicyState(positionMs = 4_000L, line = "stale-ingress-line")

        val mapped = producer.toProducerState(spicy, doc)

        assertEquals(LyricKind.LINE, mapped.lyricKind)
        assertTrue(mapped.hasTimedLyrics)
        assertEquals(1, mapped.lineIndex)
        assertEquals("second", mapped.line)
        assertEquals("", mapped.romanizedLine) // row.romanized empty
        assertEquals("", mapped.translatedLine)
        assertEquals(3_500L, mapped.lineStartMs)
        assertEquals(5_000L, mapped.lineEndMs) // fillEndMs == endMs
        assertTrue(mapped.alignedRight)
        assertNull(mapped.words) // LINE → null words
        assertNull(mapped.nextLineStartMs) // no row starts after 4000
    }

    @Test
    fun syllableDocument_selectsActiveRowAndEmitsWordsWithSyllableKind() {
        val doc = document(
            type = "Syllable",
            rows = listOf(
                row(
                    startMs = 1_000, endMs = 2_000, text = "hello",
                    words = listOf(
                        word(1_000, 1_500, "he"),
                        word(1_500, 2_000, "llo", boundaryAfter = true)
                    ),
                    ruby = listOf(SpicyBridgeRuby(0, 2, "ha")),
                    layoutGroups = listOf(SpicyBridgeLayoutGroup(0, 5, "word", true, 0.9))
                )
            )
        )
        val spicy = spicyState(positionMs = 1_200L)

        val mapped = producer.toProducerState(spicy, doc)

        assertEquals(LyricKind.SYLLABLE, mapped.lyricKind)
        assertTrue(mapped.hasTimedLyrics)
        assertEquals(0, mapped.lineIndex)
        assertEquals("hello", mapped.line)
        assertEquals(1_000L, mapped.lineStartMs)
        assertEquals(2_000L, mapped.lineEndMs)
        val words = mapped.words
        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals("he", words[0].text)
        assertEquals(1_000L, words[0].startMs)
        assertEquals("llo", words[1].text)
        assertTrue(words[1].boundaryAfter)
        assertEquals(1, mapped.ruby.size)
        assertEquals("ha", mapped.ruby[0].reading)
        assertEquals(1, mapped.layoutGroups.size)
        assertEquals("word", mapped.layoutGroups[0].kind)
    }

    @Test
    fun interludePosition_emitsEmptyLineAndKeepsLineKindFromDocumentType() {
        // Position in a gap between two lines: no active row, but document is timed → INTERLUDE.
        // line must be cleared so projection classifies it as INTERLUDE (not fallback).
        val doc = document(
            type = "Line",
            rows = listOf(
                row(startMs = 1_000, endMs = 3_000, text = "first"),
                row(startMs = 5_000, endMs = 7_000, text = "second")
            )
        )
        val spicy = spicyState(positionMs = 4_000L, line = "ingress-line")

        val mapped = producer.toProducerState(spicy, doc)

        assertEquals(LyricKind.LINE, mapped.lyricKind)
        assertTrue(mapped.hasTimedLyrics)
        assertEquals(-1, mapped.lineIndex)
        assertEquals("", mapped.line) // cleared: document present, no active row
        assertEquals("", mapped.romanizedLine)
        assertEquals("", mapped.translatedLine)
        assertEquals(0L, mapped.lineStartMs)
        assertEquals(0L, mapped.lineEndMs)
        assertNull(mapped.words)
        // nextLineStartMs points at the upcoming line after the sampled position.
        assertEquals(5_000L, mapped.nextLineStartMs)
    }

    @Test
    fun unsyncedDocument_emitsUnsyncedKindAndClearsLine() {
        // A non-timed document type (e.g. "Static") → UNSYNCED.
        val doc = document(
            type = "Static",
            rows = listOf(row(startMs = 0, endMs = 0, text = "plain"))
        )
        val spicy = spicyState(positionMs = 1_000L, line = "ingress-line")

        val mapped = producer.toProducerState(spicy, doc)

        assertEquals(LyricKind.UNSYNCED, mapped.lyricKind)
        assertFalse(mapped.hasTimedLyrics)
        assertEquals(-1, mapped.lineIndex)
        assertEquals("", mapped.line) // document present → cleared (no timed row)
        assertNull(mapped.words)
    }

    @Test
    fun noLyricsStatus_suppressesActiveRowAndEmitsNoneKind() {
        val doc = document(
            type = "Line",
            rows = listOf(row(startMs = 1_000, endMs = 3_000, text = "first"))
        )
        val spicy = spicyState(status = "no_lyrics", positionMs = 2_000L, line = "ingress")

        val mapped = producer.toProducerState(spicy, doc)

        assertEquals(LyricKind.NONE, mapped.lyricKind)
        assertFalse(mapped.hasTimedLyrics)
        assertEquals(-1, mapped.lineIndex)
        // document present but no_lyrics → line cleared (no fallback under no_lyrics).
        assertEquals("", mapped.line)
        assertNull(mapped.words)
    }

    @Test
    fun documentFromDifferentSession_isIgnoredAndFallsBackToNoDocumentBehavior() {
        // Document that does not match the state's session (different generation) MUST be ignored.
        val staleDoc = document(
            type = "Line",
            generation = 99, // mismatch
            rows = listOf(row(startMs = 1_000, endMs = 3_000, text = "first"))
        )
        val spicy = spicyState(positionMs = 2_000L, line = "ingress-fallback")

        val mapped = producer.toProducerState(spicy, staleDoc)

        assertEquals(LyricKind.NONE, mapped.lyricKind)
        assertEquals(-1, mapped.lineIndex)
        // No matched document → ingress `line` preserved for the fallback-line branch.
        assertEquals("ingress-fallback", mapped.line)
    }

    @Test
    fun nextLineStartMsIsFirstRowStartingAfterSampledPosition() {
        val doc = document(
            type = "Line",
            rows = listOf(
                row(startMs = 1_000, endMs = 2_000, text = "a"),
                row(startMs = 3_000, endMs = 4_000, text = "b"),
                row(startMs = 5_000, endMs = 6_000, text = "c")
            )
        )
        // Active row = "a" (pos 1500), next start = 3000.
        val mapped = producer.toProducerState(spicyState(positionMs = 1_500L), doc)

        assertEquals(0, mapped.lineIndex)
        assertEquals("a", mapped.line)
        assertEquals(3_000L, mapped.nextLineStartMs)
    }

    @Test
    fun timedDocumentWithZeroLengthRows_hasTimedLyricsFalse() {
        // rows with endMs == startMs carry no actual timing → hasTimedLyrics false even though
        // the document type is timed.
        val doc = document(
            type = "Line",
            rows = listOf(row(startMs = 1_000, endMs = 1_000, text = "flat"))
        )
        val spicy = spicyState(positionMs = 1_000L)

        val mapped = producer.toProducerState(spicy, doc)

        // lyricKind is still LINE (from document type), but hasTimedLyrics is false.
        assertEquals(LyricKind.LINE, mapped.lyricKind)
        assertFalse(mapped.hasTimedLyrics)
    }
}
