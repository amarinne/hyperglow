package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeRow
import com.eza.hyperglow.bridge.SpicyBridgeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end policy regressions for the producer-state -> AOD keepalive projection. */
class AodKeepaliveRegressionTest {
    @Test
    fun timedLyricsRemainAliveAfterTheSongChangeLeaseExpires() {
        for (type in listOf("Line", "Syllable")) {
            val policy = AodPowerSessionPolicy()
            project(document = document(type), nowElapsedMs = 1_000L, policy = policy)

            assertTrue(
                "$type lyrics must upgrade the presentation lease to persistent keepalive",
                project(document = document(type), nowElapsedMs = 60_000L, policy = policy).keepAlive
            )
        }
    }

    @Test
    fun documentArrivalBeforeOrAfterLeaseExpiryUpgradesToPersistentKeepalive() {
        for (arrivalMs in listOf(7_999L, 8_001L)) {
            val policy = AodPowerSessionPolicy()
            assertTrue(project(document = null, nowElapsedMs = 0L, policy = policy).keepAlive)

            assertTrue(
                "timed document arrival at $arrivalMs ms must establish persistent keepalive",
                project(
                    document = document("Syllable"),
                    nowElapsedMs = arrivalMs,
                    policy = policy
                ).keepAlive
            )
            assertTrue(
                project(
                    document = document("Syllable"),
                    nowElapsedMs = 60_000L,
                    policy = policy
                ).keepAlive
            )
        }
    }

    @Test
    fun noLyricsAndInvalidTimingExpireUnlessTheExplicitOverrideIsEnabled() {
        val candidates = listOf<SpicyBridgeDocument?>(
            null,
            document("Static"),
            document("Syllable", startMs = 100L, endMs = 100L)
        )
        for (candidate in candidates) {
            val defaultPolicy = AodPowerSessionPolicy()
            project(document = candidate, nowElapsedMs = 0L, policy = defaultPolicy)
            assertFalse(
                project(
                    document = candidate,
                    nowElapsedMs = 8_001L,
                    policy = defaultPolicy
                ).keepAlive
            )

            val overridePolicy = AodPowerSessionPolicy()
            val override = AodRenderConfig(keepAwake = true, keepAwakeUnsynced = true)
            project(
                document = candidate,
                nowElapsedMs = 0L,
                prefs = override,
                policy = overridePolicy
            )
            assertTrue(
                project(
                    document = candidate,
                    nowElapsedMs = 60_000L,
                    prefs = override,
                    policy = overridePolicy
                ).keepAlive
            )
        }
    }

    @Test
    fun pauseClearsTheSessionAndResumeStartsAFreshFiniteBudget() {
        val policy = AodPowerSessionPolicy()
        val prefs = AodRenderConfig(keepAwake = true, keepAwakeDurationMs = 300_000L)
        assertTrue(project(document("Line"), 1_000L, prefs, policy = policy).keepAlive)
        assertFalse(project(document("Line"), 301_000L, prefs, policy = policy).keepAlive)

        assertFalse(
            project(
                document("Line"),
                302_000L,
                prefs,
                state = state(playing = false),
                policy = policy
            ).keepAlive
        )
        assertTrue(project(document("Line"), 303_000L, prefs, policy = policy).keepAlive)
        assertTrue(project(document("Line"), 600_000L, prefs, policy = policy).keepAlive)
        assertFalse(project(document("Line"), 603_000L, prefs, policy = policy).keepAlive)
    }

    @Test
    fun masterSwitchesAlwaysWinOverTimedContent() {
        assertFalse(
            project(
                document("Syllable"),
                1_000L,
                AodRenderConfig(keepAwake = false)
            ).keepAlive
        )
        assertFalse(
            project(
                document("Syllable"),
                1_000L,
                AodRenderConfig(keepAwake = true),
                aodEnabled = false
            ).keepAlive
        )
    }

    private fun project(
        document: SpicyBridgeDocument?,
        nowElapsedMs: Long,
        prefs: AodRenderConfig = AodRenderConfig(keepAwake = true),
        state: SpicyBridgeState = state(),
        aodEnabled: Boolean = true,
        policy: AodPowerSessionPolicy = AodPowerSessionPolicy()
    ) = projectToDisplay(
        state = state,
        document = document,
        context = AodProjectionContext(
            userId = 0,
            nowElapsedMs = nowElapsedMs,
            positionMs = 500L,
            prefs = prefs,
            aodEnabled = aodEnabled,
            lockscreenEnabled = true,
            metadataVisible = true
        ),
        metadataIntroPolicy = SongMetadataIntroPolicy(),
        powerSessionPolicy = policy
    )

    private fun document(
        type: String,
        startMs: Long = 0L,
        endMs: Long = 1_000L
    ) = SpicyBridgeDocument(
        producerId = "spicy",
        generation = 7,
        trackUri = "spotify:track:test",
        provider = "test",
        language = "en",
        type = type,
        durationMs = 700_000L,
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

    private fun state(playing: Boolean = true) = SpicyBridgeState(
        producerId = "spicy",
        generation = 7,
        sequence = 1L,
        status = "ready",
        trackUri = "spotify:track:test",
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "",
        line = "",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 500L,
        durationMs = 700_000L,
        sampledAtElapsedMs = 0L,
        speed = if (playing) 1f else 0f,
        playing = playing,
        receivedAtElapsedMs = 0L
    )
}
