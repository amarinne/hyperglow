package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRuby
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These exist because the projection was previously unreachable from a JVM test: it read the user
 * ID inline through `android.os.UserHandle`, which is a stub that throws on call. The user is an
 * input now, so the mapping itself can be asserted.
 */
class AodStateProjectorTest {
    @Test
    fun equalCoTimedRowsUseReferenceOrdinalRatherThanStructuralEquality() {
        val source = document("Line")
        val first = source.rows.single().copy(endMs = 5000L, fillEndMs = 5000L)
        val duplicate = first.copy()
        val projected = project(state(), source.copy(rows = listOf(first, duplicate)), positionMs = 500L)
        assertEquals(1, projected.sourceRowOrdinal)
        assertEquals(0, projected.secondLine?.sourceRowOrdinal)
    }

    @Test
    fun coTimedRowsKeepDistinctSourceOrdinalsThroughCorrectionsAndRoleReversal() {
        val source = document("Line")
        val first = source.rows.single().copy(role = "LEAD", endMs = 5000L, fillEndMs = 5000L)
        val second = first.copy(role = "BACKGROUND", text = "other")
        val initial = project(state(), source.copy(rows = listOf(first, second)), positionMs = 500L)
        assertEquals(0, initial.sourceRowOrdinal)
        assertEquals(1, initial.secondLine?.sourceRowOrdinal)
        val reversed = project(state(), source.copy(rows = listOf(
            first.copy(role = "BACKGROUND", text = "corrected"), second.copy(role = "LEAD")
        )), positionMs = 500L)
        assertEquals(1, reversed.sourceRowOrdinal)
        assertEquals(0, reversed.secondLine?.sourceRowOrdinal)
        assertEquals("corrected", reversed.secondLine?.text)
    }

    @Test
    fun projectionCarriesTheSuppliedUserRatherThanReadingTheProcess() {
        val projected = project(state(), document("Line"))

        assertEquals(4242, projected.userId)
    }

    @Test
    fun timedRowAtPositionIsPresentedAsTheActiveLine() {
        val projected = project(state(), document("Line"), positionMs = 500L)

        assertEquals("line", projected.original)
        assertTrue(projected.visible)
        assertTrue(projected.lineLevelSync)
    }

    @Test
    fun wordDocumentKeepsItsTimedWordsAndSecondaryText() {
        val source = document("Word")
        val row = source.rows.single().copy(
            text = "hello world",
            romanized = "reading",
            translated = "translation",
            words = listOf(
                SpicyBridgeWord("hello", "", 0L, 400L, true),
                SpicyBridgeWord("world", "", 400L, 900L, true)
            )
        )

        val projected = project(state(), source.copy(rows = listOf(row)), positionMs = 500L)

        assertEquals("hello world", projected.original)
        assertEquals(listOf("hello", "world"), projected.words.map { it.text })
        assertEquals("reading", projected.romanized)
        assertEquals("translation", projected.translated)
        assertFalse(projected.lineLevelSync)
        assertTrue(projected.keepAlive)
    }

    @Test
    fun fillEndPastActiveWindowIsClampedForRendering() {
        val source = document("Line")
        val row = source.rows.single().copy(endMs = 800L, fillEndMs = 900L)

        val projected = project(
            state(),
            source.copy(rows = listOf(row)),
            positionMs = 500L
        )

        assertEquals(800L, projected.lineEndMs)
    }

    @Test
    fun aiDerivedWholeLineTextPublishedInTheDocumentReachesBothSecondaryRows() {
        val source = document("Line")
        val row = source.rows.single().copy(
            romanized = "AI pronunciation",
            translated = "AI translation"
        )

        val projected = project(
            state().copy(
                romanizedLine = "stale pronunciation",
                translatedLine = "stale translation"
            ),
            source.copy(rows = listOf(row)),
            positionMs = 500L
        )

        assertEquals("AI pronunciation", projected.romanized)
        assertEquals("AI translation", projected.translated)
    }

    @Test
    fun stateFallbackWithoutDocumentCarriesOnlyOriginalLyric() {
        val projected = project(
            state().copy(
                line = "line",
                romanizedLine = "AI pronunciation",
                translatedLine = "AI translation"
            ),
            document = null,
            positionMs = 500L
        )

        assertEquals("", projected.romanized)
        assertEquals("", projected.translated)
        assertEquals("line", projected.original)
    }

    @Test
    fun chineseDocumentRejectsJapaneseRubyAndRomaji() {
        val source = document("Line", language = "zh-Hant")
        val row = source.rows.single().copy(
            text = "眼神中飄移總是在",
            romanized = "me jinnaka hyou utsuri sou ze zai",
            words = listOf(
                SpicyBridgeWord("眼神中", "me jinnaka", 0L, 400L, true),
                SpicyBridgeWord("飄移總是在", "hyou utsuri sou ze zai", 400L, 900L, true)
            ),
            ruby = listOf(SpicyBridgeRuby(0, 3, "め じんなか"))
        )

        val projected = project(
            state(),
            source.copy(rows = listOf(row)),
            positionMs = 500L
        )

        assertEquals("眼神中飄移總是在", projected.original)
        assertEquals("", projected.romanized)
        assertTrue(projected.words.all { it.romanized.isEmpty() })
        assertTrue(projected.ruby.isEmpty())
    }

    @Test
    fun validChinesePinyinAndJapaneseRubyRemainAvailable() {
        assertFalse(hasLanguageInconsistentKanaRuby(document("Line", "zh-CN"), emptyList()))
        assertFalse(hasLanguageInconsistentKanaRuby(document("Line", "ja"), listOf("めじん")))
    }

    @Test
    fun untimedDocumentIsHeldOnlyBySongChangeLeaseAndSleepsAfterIt() {
        val policy = AodPowerSessionPolicy()
        val held = project(state(), document("Static"), powerSessionPolicy = policy)
        val afterLease = project(
            state(),
            document("Static"),
            nowElapsedMs = 10_000L + AodPowerSessionPolicy.DEFAULT_SONG_CHANGE_LEASE_MS,
            powerSessionPolicy = policy
        )

        assertEquals("♪", held.original)
        assertTrue(held.keepAlive)
        assertFalse(afterLease.keepAlive)
    }

    @Test
    fun untimedDocumentSurvivesLeaseExpiryWhenTheOverrideIsOn() {
        val policy = AodPowerSessionPolicy()
        val prefs = AodRenderConfig(keepAwake = true, keepAwakeUnsynced = true)
        project(state(), document("Static"), prefs = prefs, powerSessionPolicy = policy)
        val afterLease = project(
            state(),
            document("Static"),
            nowElapsedMs = 10_000L + AodPowerSessionPolicy.DEFAULT_SONG_CHANGE_LEASE_MS,
            prefs = prefs,
            powerSessionPolicy = policy
        )

        assertTrue(afterLease.keepAlive)
    }

    @Test
    fun indefiniteIntroNeverHoldsKeepAliveForUntimedSongs() {
        val policy = AodPowerSessionPolicy()
        val intro = SongMetadataIntroPolicy()
        intro.setDurationMs(-1L)
        val shown = project(
            state(title = "title", artist = "artist"),
            document("Static"),
            prefs = AodRenderConfig(keepAwake = true),
            powerSessionPolicy = policy,
            introPolicy = intro
        )
        val afterLease = project(
            state(title = "title", artist = "artist"),
            document("Static"),
            nowElapsedMs = 10_000L + AodPowerSessionPolicy.DEFAULT_SONG_CHANGE_LEASE_MS,
            prefs = AodRenderConfig(keepAwake = true),
            powerSessionPolicy = policy,
            introPolicy = intro
        )

        assertEquals("title\nartist", shown.original)
        assertTrue(shown.keepAlive)
        assertEquals("title\nartist", afterLease.original)
        assertFalse(afterLease.keepAlive)
    }

    @Test
    fun noLyricsStatusSuppressesTransitionAndSecondaryText() {
        val projected = project(state(status = "no_lyrics"), document = null)

        assertEquals("♪", projected.original)
        assertEquals("None", projected.transitionMode)
        assertEquals("", projected.romanized)
        assertEquals("", projected.translated)
    }

    @Test
    fun aodDisabledWithheldKeepAliveEvenWhileTimedLyricsPlay() {
        val projected = project(
            state(),
            document("Line"),
            positionMs = 500L,
            aodEnabled = false
        )

        assertFalse(projected.keepAlive)
        assertFalse(projected.aodEnabled)
    }

    @Test
    fun instrumentalIntroRowIsAnInterludeAndKeepsTheSongMetadataUp() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 1_000L
        )

        // The producer sends the opening instrumental as its own INTERLUDE row. Reading that as a
        // sung line made the intro look like a song that opens on vocals, which suppressed the
        // song-change metadata for the whole gap it was meant to occupy.
        assertEquals("title\nartist", projected.original)
    }

    @Test
    fun sungRowStillTakesTheRowFromTheMetadata() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 13_000L
        )

        assertEquals("line", projected.original)
    }

    @Test
    fun singleSongInfoLayoutJoinsTitleAndArtistWithMiddleDot() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 1_000L,
            prefs = AodRenderConfig(keepAwake = true, metadataLayout = "single")
        )

        assertEquals("title · artist", projected.metadata)
        assertEquals("title · artist", projected.original)
        assertEquals("single", projected.metadataLayout)
    }

    @Test
    fun stackedSongInfoLayoutKeepsTitleAndArtistOnSeparateLines() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 1_000L
        )

        assertEquals("title\nartist", projected.metadata)
        assertEquals("title\nartist", projected.original)
        assertEquals("stacked", projected.metadataLayout)
    }

    @Test
    fun songChangeInfoToggleOffKeepsTheLyricRowOnItsNormalContent() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 1_000L,
            prefs = AodRenderConfig(keepAwake = true, songChangeInfoEnabled = false)
        )

        assertEquals("\u2022 \u2022 \u2022", projected.original)
    }

    @Test
    fun overlappingBackgroundRowProjectsAsSecondLine() {
        val document = SpicyBridgeDocument(
            producerId = "producer",
            generation = 7,
            trackUri = "spotify:track:test",
            provider = "test",
            language = "en",
            type = "Line",
            durationMs = 30_000L,
            processingVersion = 1,
            rows = listOf(
                SpicyBridgeRow(
                    role = "LEAD",
                    startMs = 12_000L,
                    endMs = 20_000L,
                    fillEndMs = 20_000L,
                    alignedRight = false,
                    text = "lead",
                    romanized = "",
                    translated = "",
                    words = emptyList()
                ),
                SpicyBridgeRow(
                    role = "BACKGROUND",
                    startMs = 14_000L,
                    endMs = 18_000L,
                    fillEndMs = 18_000L,
                    alignedRight = false,
                    text = "background",
                    romanized = "",
                    translated = "",
                    words = emptyList()
                )
            )
        )

        val overlapped = project(state(), document, positionMs = 15_000L)

        assertEquals("lead", overlapped.original)
        assertEquals("background", overlapped.secondLine?.text)
        assertEquals(18_000L, overlapped.secondLine?.lineEndMs)

        val lingering = project(state(), document, positionMs = 19_000L)

        assertEquals("lead", lingering.original)
        assertEquals("background", lingering.secondLine?.text)

        val ended = project(state(), document, positionMs = 21_000L)

        assertNull(ended.secondLine)
    }

    @Test
    fun duetToggleWithdrawsTheConcurrentRowAtTheSource() {
        val document = SpicyBridgeDocument(
            producerId = "producer",
            generation = 7,
            trackUri = "spotify:track:test",
            provider = "test",
            language = "en",
            type = "Line",
            durationMs = 30_000L,
            processingVersion = 1,
            rows = listOf(
                SpicyBridgeRow(
                    role = "LEAD",
                    startMs = 12_000L,
                    endMs = 20_000L,
                    fillEndMs = 20_000L,
                    alignedRight = false,
                    text = "lead",
                    romanized = "",
                    translated = "",
                    words = emptyList()
                ),
                SpicyBridgeRow(
                    role = "BACKGROUND",
                    startMs = 14_000L,
                    endMs = 18_000L,
                    fillEndMs = 18_000L,
                    alignedRight = false,
                    text = "background",
                    romanized = "",
                    translated = "",
                    words = emptyList()
                )
            )
        )
        val overlapped = project(state(), document, positionMs = 15_000L, duetEnabled = false)

        assertEquals("lead", overlapped.original)
        assertNull(overlapped.secondLine)
    }

    private fun documentWithIntro() = SpicyBridgeDocument(
        producerId = "producer",
        generation = 7,
        trackUri = "spotify:track:test",
        provider = "test",
        language = "en",
        type = "Line",
        durationMs = 30_000L,
        processingVersion = 1,
        rows = listOf(
            SpicyBridgeRow(
                role = "INTERLUDE",
                startMs = 0L,
                endMs = 12_000L,
                fillEndMs = 12_000L,
                alignedRight = false,
                text = "\u2022 \u2022 \u2022",
                romanized = "",
                translated = "",
                words = emptyList()
            ),
            SpicyBridgeRow(
                role = "LEAD",
                startMs = 12_000L,
                endMs = 20_000L,
                fillEndMs = 20_000L,
                alignedRight = false,
                text = "line",
                romanized = "",
                translated = "",
                words = emptyList()
            )
        )
    )

    private fun project(
        state: SpicyBridgeState,
        document: SpicyBridgeDocument?,
        positionMs: Long = 100L,
        nowElapsedMs: Long = 10_000L,
        prefs: AodRenderConfig = AodRenderConfig(keepAwake = true),
        aodEnabled: Boolean = true,
        duetEnabled: Boolean = true,
        powerSessionPolicy: AodPowerSessionPolicy = AodPowerSessionPolicy(),
        introPolicy: SongMetadataIntroPolicy = SongMetadataIntroPolicy()
    ) = projectToDisplay(
        state = state,
        document = document,
        context = AodProjectionContext(
            userId = 4242,
            nowElapsedMs = nowElapsedMs,
            positionMs = positionMs,
            prefs = prefs,
            aodEnabled = aodEnabled,
            lockscreenEnabled = true,
            metadataVisible = true,
            duetEnabled = duetEnabled
        ),
        metadataIntroPolicy = introPolicy,
        powerSessionPolicy = powerSessionPolicy
    )

    private fun document(type: String, language: String = "en") = SpicyBridgeDocument(
        producerId = "producer",
        generation = 7,
        trackUri = "spotify:track:test",
        provider = "test",
        language = language,
        type = type,
        durationMs = 1_000L,
        processingVersion = 1,
        rows = listOf(
            SpicyBridgeRow(
                role = "LEAD",
                startMs = 0L,
                endMs = 900L,
                fillEndMs = 900L,
                alignedRight = false,
                text = "line",
                romanized = "",
                translated = "",
                words = emptyList()
            )
        )
    )

    private fun state(
        status: String = "ready",
        title: String = "",
        artist: String = ""
    ) = SpicyBridgeState(
        producerId = "producer",
        generation = 7,
        sequence = 1,
        status = status,
        trackUri = "spotify:track:test",
        title = title,
        artist = artist,
        album = "album",
        imageId = "",
        line = "",
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
