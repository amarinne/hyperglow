package com.eza.hyperglow.bridge

import com.eza.hyperglow.aod.AodProjectionEngine
import com.eza.hyperglow.aod.ProjectionSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class SpicyBridgeDocumentTest {
    @Test
    fun leadRowWinsOverConcurrentBackgroundRow() {
        val background = row("BACKGROUND", 1_000, 3_000, "bg")
        val lead = row("LEAD", 1_500, 2_500, "lead")
        val document = document(listOf(background, lead))

        assertEquals("lead", document.primaryRowAt(2_000)?.text)
        assertEquals("bg", document.primaryRowAt(1_200)?.text)
        assertNull(document.primaryRowAt(3_000))
    }

    @Test
    fun projectedPositionUsesSparseAnchorAndClampsDuration() {
        val state = SpicyBridgeState(
            producerId = "producer",
            generation = 1,
            sequence = 1,
            status = "ready",
            trackUri = "spotify:track:test",
            title = "title",
            artist = "artist",
            album = "album",
            imageId = "",
            line = "line",
            romanizedLine = "",
            translatedLine = "",
            lineIndex = 0,
            positionMs = 1_000,
            durationMs = 2_000,
            sampledAtElapsedMs = 10_000,
            speed = 2f,
            playing = true,
            receivedAtElapsedMs = 10_000
        )

        assertEquals(1_500, AodProjectionEngine.projectedPosition(state, 10_250))
        assertEquals(2_000, AodProjectionEngine.projectedPosition(state, 20_000))
    }

    @Test
    fun keepAliveUsesBoundedFourSecondCadence() {
        assertEquals(true, AodProjectionEngine.keepAliveDue(0, 1_000))
        assertEquals(false, AodProjectionEngine.keepAliveDue(10_000, 13_999))
        assertEquals(true, AodProjectionEngine.keepAliveDue(10_000, 14_000))
    }

    @Test
    fun playbackFallbackKeepsLoadingAndStaticNoLyricsStatesVisible() {
        assertEquals(true, AodProjectionEngine.shouldShowPlaybackFallback("loading", true))
        assertEquals(true, AodProjectionEngine.shouldShowPlaybackFallback("no_lyrics", true))
        assertEquals(false, AodProjectionEngine.shouldShowPlaybackFallback("loading", false))
        assertEquals(false, AodProjectionEngine.shouldShowPlaybackFallback("ready", true))
        assertEquals("♪", AodProjectionEngine.staticPlaybackPlaceholder("no_lyrics"))
        assertEquals(null, AodProjectionEngine.staticPlaybackPlaceholder("loading"))
    }

    @Test
    fun nonplayingLoadingEdgeIsTransportGapButReadyPauseIsRealPause() {
        assertTrue(
            AodProjectionEngine.isPlayingTransportGap(
                state(3_000, status = "loading").copy(playing = false)
            )
        )
        assertFalse(
            AodProjectionEngine.isPlayingTransportGap(
                state(3_000, status = "ready").copy(playing = false)
            )
        )
        assertFalse(AodProjectionEngine.isPlayingTransportGap(state(3_000, status = "loading")))
    }

    @Test
    fun songChangeNonPlayingEdgeNeverCommitsPauseRetention() {
        val playing = state(3_000, status = "ready", generation = 40)
        val ending = playing.copy(playing = false)
        val pending = ProjectionSessionIdentity.from(ending)

        assertTrue(AodProjectionEngine.pauseConfirmWindowMs() >= 1_500L)
        assertTrue(
            AodProjectionEngine.shouldCommitPauseRetention(pending, ending, currentActive = true)
        )
        assertFalse(
            AodProjectionEngine.shouldCommitPauseRetention(pending, playing, currentActive = true)
        )
        assertFalse(
            AodProjectionEngine.shouldCommitPauseRetention(
                pending,
                ending.copy(generation = 41, status = "loading", playing = true),
                currentActive = true
            )
        )
        assertFalse(
            AodProjectionEngine.shouldCommitPauseRetention(pending, ending, currentActive = false)
        )
        assertFalse(AodProjectionEngine.shouldCommitPauseRetention(pending, null, currentActive = true))
    }

    @Test
    fun confirmedPauseDoesNotReopenStillPlayingGrace() {
        val paused = state(3_000, status = "ready", generation = 40).copy(playing = false)
        val session = ProjectionSessionIdentity.from(paused)
        val nextSong = ProjectionSessionIdentity.from(paused.copy(generation = 41))

        assertTrue(AodProjectionEngine.shouldOpenPauseGrace(session, null))
        assertFalse(AodProjectionEngine.shouldOpenPauseGrace(session, session))
        assertTrue(AodProjectionEngine.shouldOpenPauseGrace(nextSong, session))
        assertFalse(
            AodProjectionEngine.shouldOpenPauseGrace(
                ProjectionSessionIdentity.from(paused.copy(status = "loading")),
                session
            )
        )
        assertTrue(
            AodProjectionEngine.isPlayingTransportGap(paused.copy(status = "loading"))
        )
    }

    @Test
    fun fallbackRefreshUsesFourSecondsAndRejectsOldStatusOrSession() {
        val loading = state(durationMs = 3_000, status = "loading", generation = 7)
        val expected = AodProjectionEngine.fallbackRefreshSession(loading)

        assertEquals(1_000L, AodProjectionEngine.fallbackRefreshIntervalMs())
        assertTrue(AodProjectionEngine.canRefreshFallback(expected, loading))
        assertFalse(AodProjectionEngine.canRefreshFallback(expected, loading.copy(status = "no_lyrics")))
        assertFalse(AodProjectionEngine.canRefreshFallback(expected, loading.copy(generation = 8)))
        assertFalse(AodProjectionEngine.canRefreshFallback(expected, loading.copy(playing = false)))
    }

    @Test
    fun sourceRangeParsingKeepsMissingAndPresentRangesAndDowngradesInvalid() {
        assertEquals(-1 to -1, normalizeSpicySourceRange(8, -1, -1))
        assertEquals(2 to 6, normalizeSpicySourceRange(8, 2, 6))
        assertEquals(-1 to -1, normalizeSpicySourceRange(8, 6, 2))
        assertEquals(-1 to -1, normalizeSpicySourceRange(8, 2, 9))
    }

    @Test
    fun bridgeV2PrefersCanonicalBoundaryAndV1NormalizesLegacyFlagOnce() {
        assertEquals(true, normalizeSpicyBoundaryAfter(2, true, true))
        assertEquals(false, normalizeSpicyBoundaryAfter(2, false, false))
        assertEquals(true, normalizeSpicyBoundaryAfter(1, null, false))
        assertEquals(false, normalizeSpicyBoundaryAfter(1, null, true))
        assertNull(normalizeSpicyBoundaryAfter(2, null, false))
    }

    @Test
    fun onlyLineAndSyllableDocumentsAreTimed() {
        assertEquals(true, AodProjectionEngine.isTimedDocumentType("Line"))
        assertEquals(true, AodProjectionEngine.isTimedDocumentType("Syllable"))
        assertEquals(false, AodProjectionEngine.isTimedDocumentType("Static"))
        assertEquals(false, AodProjectionEngine.isTimedDocumentType("Unknown"))
        assertEquals(true, AodProjectionEngine.isLineLevelDocumentType("Line"))
        assertEquals(false, AodProjectionEngine.isLineLevelDocumentType("Syllable"))
        assertEquals(true, AodProjectionEngine.isEffectiveLineLevelSync("Line", 4))
        assertEquals(false, AodProjectionEngine.isEffectiveLineLevelSync("Syllable", 4))
        assertEquals(true, AodProjectionEngine.isEffectiveLineLevelSync("Syllable", 0))
        assertEquals(false, AodProjectionEngine.isEffectiveLineLevelSync("Unknown", 0))
    }

    @Test
    fun documentTimingMustMatchAcceptedStateDuration() {
        val document = document(listOf(row("LEAD", 1_000, 3_000, "line")), durationMs = 3_000)

        assertTrue(isValidSpicyBridgeDocumentTiming(document, acceptedDurationMs = 3_000))
        assertFalse(isValidSpicyBridgeDocumentTiming(document, acceptedDurationMs = 3_001))
    }

    @Test
    fun laterDurationChangeInvalidatesAcceptedDocument() {
        val document = document(listOf(row("LEAD", 1_000, 3_000, "line")), durationMs = 3_000)

        assertTrue(document.matches(state(durationMs = 3_000)))
        assertFalse(document.matches(state(durationMs = 3_001)))
    }

    @Test
    fun rowAndWordTimingCannotExceedAcceptedStateDuration() {
        assertFalse(isValidSpicyBridgeDocumentTiming(
            document(listOf(row("LEAD", 1_000, 3_001, "line")), durationMs = 3_000),
            acceptedDurationMs = 3_000
        ))
        assertFalse(isValidSpicyBridgeDocumentTiming(
            document(listOf(row("LEAD", 1_000, 3_000, "line").copy(fillEndMs = 3_001))),
            acceptedDurationMs = 3_000
        ))
        assertFalse(isValidSpicyBridgeDocumentTiming(
            document(listOf(row("LEAD", 1_000, 3_000, "line").copy(
                words = listOf(SpicyBridgeWord("word", "", 2_500, 3_001, false))
            ))),
            acceptedDurationMs = 3_000
        ))
    }

    @Test
    fun malformedIntervalsFailClosed() {
        assertFalse(isValidSpicyBridgeDocumentTiming(
            document(listOf(row("LEAD", 1_000, 2_800, "line").copy(fillEndMs = 2_900))),
            acceptedDurationMs = 3_000
        ))
        assertFalse(isValidSpicyBridgeDocumentTiming(
            document(listOf(row("LEAD", 1_000, 3_000, "line").copy(
                words = listOf(SpicyBridgeWord("word", "", 2_500, 2_400, false))
            ))),
            acceptedDurationMs = 3_000
        ))
    }

    @Test
    fun fillEndMayPrecedeActiveWindowEnd() {
        val document = document(listOf(
            row("LEAD", 1_000, 3_000, "line").copy(fillEndMs = 2_500)
        ))

        assertTrue(isValidSpicyBridgeDocumentTiming(document, acceptedDurationMs = 3_000))
    }

    @Test
    fun transportedRenderModesPreserveCurrentProducerValues() {
        val current = SpicyBridgeRenderModes(
            weight = "Bold",
            textSize = "xlarge",
            textSizeCustom = 375,
            secondary = "Both",
            animation = "Spotlight word",
            glow = "Subtle line",
            lineSyncFill = "Left to right (sentence)",
            overflow = "Scroll with lyric",
            transition = "Crossfade",
            font = "apple"
        )

        assertEquals(current, normalizeSpicyBridgeRenderModes(current))
    }

    @Test
    fun unknownRenderModesUseProducerDefaultsAndLegacyAliases() {
        assertEquals(
            SpicyBridgeRenderModes(
                weight = "Medium",
                textSize = "normal",
                textSizeCustom = 500,
                secondary = "Transliteration",
                animation = "Spotlight word",
                glow = "Off",
                lineSyncFill = "Top to bottom",
                overflow = "Wrap",
                transition = "Fade up",
                font = "spotify"
            ),
            normalizeSpicyBridgeRenderModes(
                SpicyBridgeRenderModes(
                    weight = "unknown",
                    textSize = "huge",
                    textSizeCustom = 900,
                    secondary = "Romanized",
                    animation = "Full",
                    glow = "auto",
                    lineSyncFill = "Diagonal",
                    overflow = "Marquee",
                    transition = "Slide",
                    font = "comic"
                )
            )
        )
    }

    @Test
    fun documentMetadataRejectsMalformedIdentityAndSize() {
        val valid = SpicyBridgeDocumentMetadata(
            documentVersion = 1,
            producerId = "producer",
            generation = 7,
            trackUri = "spotify:track:test",
            compressedBytes = 4_096
        )

        assertTrue(isValidSpicyBridgeDocumentMetadata(valid))
        assertTrue(isValidSpicyBridgeDocumentMetadata(valid.copy(documentVersion = 2)))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(documentVersion = 3)))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(producerId = "")))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(generation = -1)))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(trackUri = "not-spotify")))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(compressedBytes = 0)))
        assertFalse(isValidSpicyBridgeDocumentMetadata(valid.copy(compressedBytes = 1_048_577)))
    }

    @Test
    fun documentCommitOrderRejectsDuplicateAndOlderRevisionWithinSession() {
        val order = SpicyDocumentCommitOrder()
        val first = SpicyDocumentSessionIdentity("producer", 7, "spotify:track:first")
        val second = SpicyDocumentSessionIdentity("producer", 8, "spotify:track:second")

        assertTrue(order.accept(first, 2L))
        assertFalse(order.accept(first, 2L))
        assertFalse(order.accept(first, 1L))
        assertTrue(order.accept(first, 3L))
        assertTrue(order.accept(second, 1L))
    }

    @Test
    fun compressedDocumentReaderRequiresExactDeclaredLength() {
        val compressed = gzip("document")

        assertEquals(
            "document",
            readBoundedSpicyDocumentGzip(
                ByteArrayInputStream(compressed),
                compressed.size
            ).toString(Charsets.UTF_8)
        )
        assertThrowsIOException {
            readBoundedSpicyDocumentGzip(ByteArrayInputStream(compressed), compressed.size - 1)
        }
        assertThrowsIOException {
            readBoundedSpicyDocumentGzip(ByteArrayInputStream(compressed), compressed.size + 1)
        }
    }

    @Test
    fun compressedDocumentReaderRejectsTruncatedGzip() {
        val compressed = gzip("document")
        val truncated = compressed.copyOf(compressed.size - 2)

        assertThrowsIOException {
            readBoundedSpicyDocumentGzip(ByteArrayInputStream(truncated), truncated.size)
        }
    }

    @Test
    fun delayedFirstPipeCannotOverwriteCompletedSecondPipe() {
        val firstBytes = gzip("first")
        val secondBytes = gzip("second")
        val firstInput = PipedInputStream(firstBytes.size)
        val firstOutput = PipedOutputStream(firstInput)
        val secondInput = PipedInputStream(secondBytes.size)
        val secondOutput = PipedOutputStream(secondInput)
        val firstStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val order = SpicyDocumentCommitOrder()
        val identity = SpicyDocumentSessionIdentity("producer", 7, "spotify:track:test")
        try {
            secondOutput.write(secondBytes)
            secondOutput.close()
            val first = executor.submit<Boolean> {
                firstStarted.countDown()
                readBoundedSpicyDocumentGzip(firstInput, firstBytes.size)
                order.accept(identity, 1L)
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> {
                readBoundedSpicyDocumentGzip(secondInput, secondBytes.size)
                order.accept(identity, 2L)
            }

            assertTrue(second.get(1, TimeUnit.SECONDS))
            firstOutput.write(firstBytes)
            firstOutput.close()
            assertFalse(first.get(1, TimeUnit.SECONDS))
        } finally {
            runCatching { firstOutput.close() }
            runCatching { secondOutput.close() }
            runCatching { firstInput.close() }
            runCatching { secondInput.close() }
            executor.shutdownNow()
        }
    }

    private fun row(role: String, start: Long, end: Long, text: String) = SpicyBridgeRow(
        role, start, end, end, false, text, "", "", emptyList()
    )

    private fun document(rows: List<SpicyBridgeRow>, durationMs: Long = 3_000) = SpicyBridgeDocument(
        "producer", 1, "spotify:track:test", "provider", "ja", "timed", durationMs, 1, rows
    )

    private fun state(
        durationMs: Long,
        status: String = "ready",
        generation: Int = 1
    ) = SpicyBridgeState(
        producerId = "producer",
        generation = generation,
        sequence = 1,
        status = status,
        trackUri = "spotify:track:test",
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "",
        line = "line",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 1_000,
        durationMs = durationMs,
        sampledAtElapsedMs = 10_000,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 10_000
    )

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value.toByteArray()) }
        output.toByteArray()
    }

    private fun assertThrowsIOException(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("Expected IOException")
    }
}
