package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These exist because the projection was previously unreachable from a JVM test: it read the user
 * ID inline through `android.os.UserHandle`, which is a stub that throws on call. The user is an
 * input now, so the mapping itself can be asserted.
 */
class AodStateProjectorTest {
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
    fun providerFallbackCarriesAiDerivedActiveLineWithoutADocument() {
        val projected = project(
            state().copy(
                line = "line",
                romanizedLine = "AI pronunciation",
                translatedLine = "AI translation"
            ),
            document = null,
            positionMs = 500L
        )

        assertEquals("AI pronunciation", projected.romanized)
        assertEquals("AI translation", projected.translated)
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
        assertEquals("title \u00b7 artist", projected.original)
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
    fun songChangeInfoToggleOffKeepsTheLyricRowOnItsNormalContent() {
        val projected = project(
            state(title = "title", artist = "artist"),
            documentWithIntro(),
            positionMs = 1_000L,
            prefs = AodRenderConfig(keepAwake = true, songChangeInfoEnabled = false)
        )

        assertEquals("\u2022 \u2022 \u2022", projected.original)
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
        powerSessionPolicy: AodPowerSessionPolicy = AodPowerSessionPolicy()
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
            metadataVisible = true
        ),
        metadataIntroPolicy = SongMetadataIntroPolicy(),
        powerSessionPolicy = powerSessionPolicy
    )

    private fun document(type: String) = SpicyBridgeDocument(
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
