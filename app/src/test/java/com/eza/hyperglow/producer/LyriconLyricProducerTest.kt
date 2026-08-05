package com.eza.hyperglow.producer

import io.github.proify.lyricon.lyric.model.LyricWord as LyriconLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LyriconLyricProducer]: SDK callback → boundary mapping, and the
 * [TimingNavigator]-driven active-line computation (Phase 3).
 *
 * The listener callbacks are driven directly via the producer's internal listener objects. A
 * fixed fake clock (`{ 0L }`) is injected so [LyriconLyricProducer.emit] can run without
 * Android's [android.os.SystemClock].
 *
 * (Listener callback params of type [LyriconSubscriber] are ignored by the producer's callback
 * bodies — only the connection/state transitions matter — so we pass an unchecked null
 * reference, which is safe because the parameter is never dereferenced.)
 */
class LyriconLyricProducerTest {

    private val producer = LyriconLyricProducer { 0L }

    private val unusedSubscriber: LyriconSubscriber get() = null as LyriconSubscriber

    private fun line(begin: Long, end: Long, text: String, words: List<LyriconLyricWord>? = null) =
        RichLyricLine(
            begin = begin,
            end = end,
            text = text,
            words = words,
            translation = "t-$text",
            roma = "r-$text"
        )

    private fun word(begin: Long, end: Long, text: String) = LyriconLyricWord(
        begin = begin, end = end, text = text
    )

    /** A 3-line song with a gap between line 0 and line 1 to test "previous line" behavior. */
    private fun threeLineSong(): Song = Song(
        id = "song-1",
        name = "Test Song",
        artist = "Test Artist",
        duration = 8_000L,
        lyrics = listOf(
            line(1_000, 3_000, "first", listOf(word(1_000, 1_500, "first"))),
            line(3_500, 5_000, "second", listOf(word(3_500, 4_000, "sec"), word(4_000, 5_000, "ond"))),
            line(5_000, 7_000, "third", words = null)
        )
    )

    // --- Connection listener mapping ---

    @Test
    fun initialConnectionIsDisconnectedAndStateIsNull() {
        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnected_mapsToConnected() {
        producer.connectionListener.onConnected(unusedSubscriber)
        assertEquals(ProducerConnection.CONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onReconnected_mapsToReconnected() {
        producer.connectionListener.onReconnected(unusedSubscriber)
        assertEquals(ProducerConnection.RECONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onDisconnected_mapsToDisconnectedAndClearsState() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertTrue("song change emits state", producer.state.value != null)

        producer.connectionListener.onDisconnected(unusedSubscriber)

        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnectTimeout_mapsToConnectTimeoutAndClearsState() {
        producer.connectionListener.onConnectTimeout(unusedSubscriber)
        assertEquals(ProducerConnection.CONNECT_TIMEOUT, producer.connection.value)
        assertNull(producer.state.value)
    }

    // --- Active-line computation (Phase 3: TimingNavigator-driven) ---

    @Test
    fun onSongChanged_emitsMetadataOnlyStateBeforeFirstPosition() {
        producer.playerListener.onSongChanged(threeLineSong())

        val state = producer.state.value
        assertNotNull(state)
        assertEquals("lyricon", state!!.producerId)
        assertEquals("Test Song", state.title)
        assertEquals("Test Artist", state.artist)
        assertEquals(8_000L, state.durationMs)
        assertEquals("lyricon:song-1", state.trackUri)
        // No position yet → lineIndex -1, empty line, no words.
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
        assertEquals(1, state.generation) // incremented on song change
    }

    @Test
    fun positionBeforeFirstLine_yieldsNoActiveLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(500L) // before line 0 (begin=1000)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
        assertEquals(500L, state.positionMs)
    }

    @Test
    fun positionWithinFirstLine_selectsLine0WithItsWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L) // within line 0 [1000,3000]

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals("r-first", state.romanizedLine)
        assertEquals("t-first", state.translatedLine)
        val words = state.words
        assertNotNull(words)
        assertEquals(1, words!!.size)
        assertEquals("first", words[0].text)
        assertEquals(1_000L, words[0].startMs)
        assertEquals(1_500L, words[0].endMs)
    }

    @Test
    fun positionInGapBetweenLines_showsPreviousLine() {
        // Gap: line 0 ends at 3000, line 1 begins at 3500. Position 3200 is in the gap.
        // findTargetIndex returns the last line with begin <= pos → index 0.
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(3_200L)

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
    }

    @Test
    fun positionWithinSecondLine_selectsLine1WithTwoWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // within line 1 [3500,5000]

        val state = producer.state.value!!
        assertEquals(1, state.lineIndex)
        assertEquals("second", state.line)
        val words = state.words
        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals("sec", words[0].text)
        assertEquals(3_500L, words[0].startMs)
        assertEquals("ond", words[1].text)
        assertEquals(4_000L, words[1].startMs)
        assertEquals(5_000L, words[1].endMs)
    }

    @Test
    fun positionWithinLineWithoutWords_emitsNullWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(6_000L) // within line 2 [5000,7000], words=null

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals("third", state.line)
        assertNull(state.words)
    }

    @Test
    fun positionAfterLastLine_clampsToLastLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(8_500L) // beyond end of last line

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals("third", state.line)
    }

    @Test
    fun lineChange_updatesWordsAndLineIndex() {
        producer.playerListener.onSongChanged(threeLineSong())
        // Line 0
        producer.playerListener.onPositionChanged(2_000L)
        val s0 = producer.state.value!!
        assertEquals(0, s0.lineIndex)
        assertEquals(1, s0.words!!.size)

        // Advance to line 1
        producer.playerListener.onPositionChanged(4_200L)
        val s1 = producer.state.value!!
        assertEquals(1, s1.lineIndex)
        assertEquals("second", s1.line)
        assertEquals(2, s1.words!!.size)
        // Sequence increments on each emit.
        assertTrue("sequence advances", s1.sequence > s0.sequence)
    }

    @Test
    fun positionOnlyUpdateWithinSameLine_reusesCachedWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // line 1
        val words1 = producer.state.value!!.words

        producer.playerListener.onPositionChanged(4_800L) // still line 1
        val words2 = producer.state.value!!.words

        // Same line → same cached word list instance (no reallocation).
        assertTrue("words cached across position-only update", words1 === words2)
        assertEquals(4_800L, producer.state.value!!.positionMs)
    }

    @Test
    fun seekTo_resetsNavigatorCacheAndRecomputesLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // line 1
        assertEquals(1, producer.state.value!!.lineIndex)

        // Seek back into line 0's range.
        producer.playerListener.onSeekTo(2_000L)

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals(2_000L, state.positionMs)
    }

    @Test
    fun onPlaybackStateChanged_reEmitsWithUpdatedPlayingFlag() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L)
        val before = producer.state.value!!
        assertEquals(false, before.playing) // default

        producer.playerListener.onPlaybackStateChanged(true)

        val after = producer.state.value!!
        assertEquals(true, after.playing)
        assertEquals(1f, after.speed, 0.0001f)
        // Same line (position unchanged) → same words cached.
        assertEquals(before.words, after.words)
    }

    @Test
    fun onPlaybackStateChanged_pausingSetsSpeedToZero() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPlaybackStateChanged(false)

        val state = producer.state.value!!
        assertEquals(false, state.playing)
        assertEquals(0f, state.speed, 0.0001f)
    }

    @Test
    fun onSongChangedNull_clearsSongAndState() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertTrue(producer.state.value != null)

        producer.playerListener.onSongChanged(null)

        assertNull(producer.state.value)
    }

    @Test
    fun onActiveProviderChangedNull_clearsState() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onActiveProviderChanged(null)
        assertNull(producer.state.value)
    }

    @Test
    fun songChange_incrementsGeneration() {
        producer.playerListener.onSongChanged(threeLineSong())
        val gen1 = producer.state.value!!.generation

        producer.playerListener.onSongChanged(threeLineSong())
        val gen2 = producer.state.value!!.generation

        assertTrue("generation increments on song change", gen2 > gen1)
    }

    @Test
    fun songWithNoLyrics_emitsMetadataOnlyStateOnPosition() {
        val noLyrics = Song(id = "nolyrics", name = "No Lyrics", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noLyrics)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals("No Lyrics", state.title)
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
    }

    @Test
    fun producerId_isLyricon() {
        assertEquals(LyricSource.LYRICON, producer.id)
    }

    @Test
    fun trackUriIsSyntheticLyriconPrefix() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertEquals("lyricon:song-1", producer.state.value!!.trackUri)
    }

    @Test
    fun trackUriFallsBackToNameWhenIdAbsent() {
        val noId = Song(id = null, name = "Fallback Name", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noId)
        assertEquals("lyricon:Fallback Name", producer.state.value!!.trackUri)
    }

    // --- Phase 3: active-row fields (lyricKind / lineStartMs / lineEndMs / hasTimedLyrics /
    //     nextLineStartMs / ruby / layoutGroups / alignedRight) populated by emit() ---

    @Test
    fun rowFields_populatedFromActiveSyllableLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L) // line 0 [1000,3000], has words

        val state = producer.state.value!!
        assertEquals(LyricKind.SYLLABLE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics)
        assertEquals(1_000L, state.lineStartMs)
        assertEquals(3_000L, state.lineEndMs)
        // Next line begins at 3500 → nextLineStartMs.
        assertEquals(3_500L, state.nextLineStartMs)
        // Lyricon carries no alignment / ruby / layout-group concepts.
        assertEquals(false, state.alignedRight)
        assertTrue(state.ruby.isEmpty())
        assertTrue(state.layoutGroups.isEmpty())
    }

    @Test
    fun rowFields_populatedFromLineLevelLineWithoutWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(6_000L) // line 2 [5000,7000], words=null

        val state = producer.state.value!!
        assertEquals(LyricKind.LINE, state.lyricKind)
        assertEquals(5_000L, state.lineStartMs)
        assertEquals(7_000L, state.lineEndMs)
        // Line 2 is the last → no next line.
        assertNull(state.nextLineStartMs)
        // words null for a line-level line.
        assertNull(state.words)
        // hasTimedLyrics is song-level (other lines have timing).
        assertEquals(true, state.hasTimedLyrics)
    }

    @Test
    fun noLyricsSong_emitsNoneKindAndNoTimedLyrics() {
        val noLyrics = Song(id = "nolyrics", name = "No Lyrics", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noLyrics)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals(LyricKind.NONE, state.lyricKind)
        assertEquals(false, state.hasTimedLyrics)
        assertNull(state.nextLineStartMs)
        assertEquals(0L, state.lineStartMs)
        assertEquals(0L, state.lineEndMs)
    }

    @Test
    fun beforeFirstLine_lyricKindIsSongLevelAndNextLineStartIsFirstBegin() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(500L) // before line 0 (begin=1000)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        // No active line, but the song has timed lyrics with words → song-level SYLLABLE so the
        // engine can tell "timed lyrics exist, between lines" (INTERLUDE) from "no lyrics".
        assertEquals(LyricKind.SYLLABLE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics)
        assertEquals(1_000L, state.nextLineStartMs) // first line begins at 1000
        assertEquals(0L, state.lineStartMs) // no active row
        assertEquals(0L, state.lineEndMs)
    }

    @Test
    fun nextLineStartMs_advancesAsPositionMovesThroughSong() {
        producer.playerListener.onSongChanged(threeLineSong())

        producer.playerListener.onPositionChanged(2_000L) // before line 1 (3500)
        assertEquals(3_500L, producer.state.value!!.nextLineStartMs)

        producer.playerListener.onPositionChanged(4_200L) // before line 2 (5000)
        assertEquals(5_000L, producer.state.value!!.nextLineStartMs)

        producer.playerListener.onPositionChanged(6_000L) // past the last line's begin
        assertNull(producer.state.value!!.nextLineStartMs)
    }

    @Test
    fun songWithOnlyLineLevelLyrics_songLevelKindIsLineBeforeFirstPosition() {
        // All lines have words=null → song-level kind is LINE (not SYLLABLE) before first line.
        val lineLevel = Song(
            id = "ll", name = "LL", artist = "A", duration = 5_000L,
            lyrics = listOf(
                line(1_000, 2_000, "a", words = null),
                line(2_500, 3_500, "b", words = null)
            )
        )
        producer.playerListener.onSongChanged(lineLevel)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals(LyricKind.LINE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics) // lines have end > begin
        assertEquals(1_000L, state.nextLineStartMs)

        // Within a line-level line, kind stays LINE.
        producer.playerListener.onPositionChanged(1_500L)
        assertEquals(LyricKind.LINE, producer.state.value!!.lyricKind)
    }
}
