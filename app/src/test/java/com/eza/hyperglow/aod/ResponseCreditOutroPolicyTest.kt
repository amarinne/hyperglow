package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.*
import org.junit.Test

class ResponseCreditOutroPolicyTest {
    private val session = ProjectionSessionIdentity("producer", 7, "spotify:track:test")
    private val credit = "Lyrics from Spicy Lyrics\nuploaded by Uploader, made by Maker"

    @Test fun startsAfterVocalHoldAndExpiresWithoutHeartbeatRenewal() {
        val policy = ResponseCreditOutroPolicy()
        assertFalse(show(policy, position = 8699))
        assertTrue(show(policy, position = 8700, now = 1000))
        assertTrue(show(policy, position = 8900, now = 10999))
        assertFalse(show(policy, position = 9000, now = 11000))
        assertFalse(show(policy, position = 9000, now = 20000))
    }

    @Test fun gapsAndDocumentRefreshesDoNotRestartExpiredWindow() {
        val policy = ResponseCreditOutroPolicy()
        assertTrue(show(policy, now = 1000))
        assertFalse(show(policy, doc = null, now = 5000))
        assertFalse(show(policy, doc = document().copy(responseCredit = "corrected credit"), now = 11000))
    }

    @Test fun seekingBackAndNewSongsPermitNewEpisodes() {
        val policy = ResponseCreditOutroPolicy()
        assertTrue(show(policy, now = 1000))
        assertFalse(show(policy, now = 11000))
        assertFalse(show(policy, position = 8000, now = 12000))
        assertTrue(show(policy, now = 13000))
        assertTrue(policy.shouldShow(session.copy(generation = 8), document(), 9000, 25000, true))
    }

    @Test fun missingCreditsUntimedLyricsAndDisabledSurfacesCannotStartWindow() {
        val policy = ResponseCreditOutroPolicy()
        assertFalse(show(policy, doc = document().copy(responseCredit = "")))
        assertFalse(show(policy, doc = document().copy(responseCredit = "x".repeat(501))))
        assertFalse(show(policy, doc = document().copy(type = "Static")))
        assertFalse(show(policy, eligible = false, now = 1000))
        assertTrue(show(policy, now = 30000))
    }

    @Test fun trailingInstrumentalAndBackgroundRowsDoNotDelayLeadCredit() {
        val doc = document()
        val trailing = doc.rows.single().copy(role = "INTERLUDE", endMs = 30000, fillEndMs = 30000)
        val background = trailing.copy(role = "BACKGROUND")
        assertTrue(show(ResponseCreditOutroPolicy(), doc = doc.copy(rows = doc.rows + trailing + background)))
    }

    @Test fun projectorPublishesStaticCreditWithoutDuetReadingOrTranslation() {
        val policy = ResponseCreditOutroPolicy()
        val doc = document()
        val before = project(doc, policy, 8699, 1000)
        assertEquals("♪", before.original)
        val tail = project(doc, policy, 8700, 2000)
        assertEquals(credit, tail.original)
        assertTrue(tail.visible)
        assertTrue(tail.keepAlive)
        assertEquals("", tail.romanized)
        assertEquals("", tail.translated)
        assertTrue(tail.words.isEmpty())
        assertFalse(tail.lineLevelSync)
        assertEquals("Minimal", tail.animationMode)
        assertEquals("Wrap", tail.overflowMode)
        assertNull(tail.secondLine)
        assertFalse(tail.metadataVisible)
        val publication = encodeNormalizedAodStatePublication(normalizeAodDisplayState(tail), 1L, 2000L)
        val decoded = AodStateWireCodec.decode(publication.envelope) as AodStateWireMessage.Snapshot
        assertEquals(credit, decoded.value.original)
        assertEquals("Minimal", decoded.value.animationMode)
        assertEquals("credit", decoded.value.textSizeMode)
        val expired = project(doc.copy(), policy, 9000, 12000)
        assertEquals("♪", expired.original)
    }

    private fun show(policy: ResponseCreditOutroPolicy, doc: SpicyBridgeDocument? = document(),
        position: Long = 9000, now: Long = 1000, eligible: Boolean = true
    ) = policy.shouldShow(session, doc, position, now, eligible)

    private fun document() = SpicyBridgeDocument(
        producerId = "producer", generation = 7, trackUri = "spotify:track:test",
        provider = "Spicy Lyrics", language = "en", type = "Word", durationMs = 30000,
        processingVersion = 1, responseCredit = credit,
        rows = listOf(SpicyBridgeRow(role = "LEAD", startMs = 1000, endMs = 8000,
            fillEndMs = 8000, alignedRight = false, text = "lyric", romanized = "reading",
            translated = "translation", words = emptyList()))
    )

    private fun project(doc: SpicyBridgeDocument, policy: ResponseCreditOutroPolicy, position: Long, now: Long) =
        projectToDisplay(
            state = SpicyBridgeState(
                producerId = "producer", generation = 7, sequence = 1, status = "ready",
                trackUri = "spotify:track:test", title = "", artist = "", album = "", imageId = "",
                line = "", romanizedLine = "", translatedLine = "", lineIndex = 0,
                positionMs = position, durationMs = 30000, sampledAtElapsedMs = now,
                speed = 1f, playing = true, receivedAtElapsedMs = now),
            document = doc,
            context = AodProjectionContext(userId = 0, nowElapsedMs = now, positionMs = position,
                prefs = AodRenderConfig(keepAwake = true), aodEnabled = true,
                lockscreenEnabled = true, metadataVisible = true),
            metadataIntroPolicy = SongMetadataIntroPolicy(), powerSessionPolicy = AodPowerSessionPolicy(),
            responseCreditOutroPolicy = policy)
}
