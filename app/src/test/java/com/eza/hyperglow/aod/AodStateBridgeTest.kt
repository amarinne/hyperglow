package com.eza.hyperglow.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodStateBridgeTest {
    @Test
    fun identicalStateIsSuppressed() {
        val state = state()

        assertFalse(shouldRepublish(state, state.copy(positionMs = 1_100L, sampledAtElapsedMs = 1_100L)))
    }

    @Test
    fun discontinuousPositionForcesPublish() {
        val last = state(positionMs = 1_000L, sampledAtElapsedMs = 1_000L)
        val next = last.copy(positionMs = 1_851L, sampledAtElapsedMs = 1_100L)

        assertTrue(shouldRepublish(last, next))
    }

    @Test
    fun transportedRangesSurviveTrimmedDisplayMapping() {
        assertEquals(0 to 4, trimAodSourceRange(6, 4, 2, 2, 6))
        assertEquals(-1 to -1, trimAodSourceRange(6, 4, 2, -1, -1))
        assertEquals(-1 to -1, trimAodSourceRange(6, 4, 2, 4, 2))
    }

    @Test
    fun speedChangeForcesPublish() {
        val last = state()

        assertTrue(shouldRepublish(last, last.copy(speed = 1.25f)))
    }

    @Test
    fun adaptiveSectioningDefaultsOnAndChangesRepublishState() {
        val last = state()

        assertTrue(last.adaptiveSectioning)
        assertTrue(shouldRepublish(last, last.copy(adaptiveSectioning = false)))
    }

    @Test
    fun burnInScheduleChangesRepublishState() {
        val last = state()

        assertTrue(shouldRepublish(last, last.copy(burnInPattern = "four_corner")))
        assertTrue(shouldRepublish(last, last.copy(burnInIntervalMs = 120_000L)))
    }

    @Test
    fun pausedHiddenThenTerminalHiddenRepublishes() {
        val paused = AodDisplayState(visible = false, pauseRetentionEligible = true)

        assertTrue(shouldRepublish(paused, paused.copy(pauseRetentionEligible = false)))
    }

    @Test
    fun utf16TruncationNeverSplitsEmojiSurrogatePair() {
        val source = "x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS - 1) + "😀"

        val normalized = normalizeAodDisplayState(state().copy(original = source))

        assertEquals(AodStateWireLimits.MAX_LYRIC_CHARS - 1, normalized.original.length)
        assertTrue(normalized.original.isNotEmpty())
        assertTrue(AodStateWireCodec.encode(
            encodeNormalizedAodStatePublication(normalized, 9L, 10L).message
        ) != null)
    }

    @Test
    fun keepAliveRefreshAlsoRefreshesReconnectReplayTimestamp() {
        val current = requireNotNull(
            encodeNormalizedAodStatePublication(
                normalizeAodDisplayState(state()),
                revision = 9L,
                updatedAtElapsedMs = 10L
            ).message as? AodStateWireMessage.Snapshot
        )

        val refreshed = refreshAodStateWireSnapshot(current, updatedAtElapsedMs = 4_010L)

        assertEquals(4_010L, refreshed.updatedAtElapsedMs)
        assertEquals(current.value, refreshed.value)
        assertEquals(current.revision, refreshed.revision)
    }

    @Test
    fun fullSnapshotIsRepublishedOnItsOwnCadence() {
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 10L, lastFullPublishAtElapsedMs = Long.MIN_VALUE))
        assertFalse(shouldRepublishFullSnapshot(nowElapsedMs = 4_000L, lastFullPublishAtElapsedMs = 0L))
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 4_500L, lastFullPublishAtElapsedMs = 0L))
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 9_000L, lastFullPublishAtElapsedMs = 0L))
    }

    @Test
    fun aggregateOverflowPublishesHiddenFailClosedRevision() {
        val normalized = normalizeAodDisplayState(
            state().copy(
                keepAlive = true,
                words = List(100) {
                    AodDisplayWord("x".repeat(500), "", 0L, 1L, false)
                }
            )
        )

        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)

        assertTrue(publication.message is AodStateWireMessage.Hidden)
        assertFalse(publication.message.keepAlive)
        assertEquals(9L, publication.message.revision)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    private fun state(
        positionMs: Long = 1_000L,
        sampledAtElapsedMs: Long = 1_000L
    ) = AodDisplayState(
        visible = true,
        original = "line",
        lineStartMs = 0L,
        lineEndMs = 2_000L,
        durationMs = 2_000L,
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs
    )
}
