package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.nextLyricRetentionAnchor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLifetimePolicyTest {
    @Test
    fun powerLifetimeDoesNotDependOnCanvasVisibility() {
        assertTrue(shouldActivateAodPowerLifetime(true, true, true))
        assertFalse(shouldActivateAodPowerLifetime(false, true, true))
        assertFalse(shouldActivateAodPowerLifetime(true, false, true))
        assertFalse(shouldActivateAodPowerLifetime(true, true, false))
    }

    @Test
    fun wakeSignalOnlyFiresForNewContentEvents() {
        assertFalse(isNewAodWakeSignal(9L, 0L))
        assertFalse(isNewAodWakeSignal(9L, 9L))
        assertTrue(isNewAodWakeSignal(0L, 9L))
        assertTrue(isNewAodWakeSignal(8L, 9L))
    }

    @Test
    fun keepAlivePowerSessionSurvivesTransientHiddenEdgeAndRetriesDetachedWake() {
        val timed = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            lineStartMs = 1_000L,
            lineEndMs = 4_000L,
            original = "line"
        )
        val untimedKeepAlive = timed.copy(lineStartMs = 0L, lineEndMs = 0L)

        assertTrue(hasPersistentAodPowerIntent(timed))
        assertTrue(hasPersistentAodPowerIntent(untimedKeepAlive))
        assertFalse(hasPersistentAodPowerIntent(untimedKeepAlive.copy(keepAlive = false)))
        assertFalse(hasPersistentAodPowerIntent(untimedKeepAlive.copy(playbackActive = false)))
        assertTrue(shouldStartAodPowerGrace(true, true, true, true))
        assertFalse(shouldStartAodPowerGrace(true, false, true, true))
        assertFalse(shouldStartAodPowerGrace(true, true, true, false))
        assertTrue(shouldRetryDetachedAodWake(false, true))
        assertFalse(shouldRetryDetachedAodWake(true, true))
        assertFalse(shouldRetryDetachedAodWake(false, false))
    }

    @Test
    fun racedHideRecoversOnceAndNeverFightsXiaomiOwnedDisplayPower() {
        assertTrue(isAodDisplayOffState(1))
        assertFalse(isAodDisplayOffState(2))
        assertFalse(isAodDisplayOffState(3))
        assertFalse(isAodDisplayOffState(4))

        // The captured race: guard activates, presenting AOD vanishes 1.79 s later.
        assertTrue(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = true,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 2_790L
            )
        )
        // One shot per activation: a second off edge in the same window does not re-wake.
        assertFalse(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = false,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 2_790L
            )
        )
        // Past the hide animation, the off edge is Xiaomi's decision to keep.
        assertFalse(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = true,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 1_000L + HIDE_RACE_RECOVERY_WINDOW_MS + 1L
            )
        )
        // Released keepalive, detached surface, and an unarmed guard never recover.
        assertFalse(
            shouldRecoverRacedAodHide(true, false, true, 1_000L, 2_000L)
        )
        assertFalse(
            shouldRecoverRacedAodHide(false, true, true, 1_000L, 2_000L)
        )
        assertFalse(
            shouldRecoverRacedAodHide(true, true, true, Long.MIN_VALUE, 2_000L)
        )
    }

    @Test
    fun managedPositionFallbackStopsRetryingAfterBoundedAttempts() {
        assertTrue(shouldRetryManagedAodPosition(0, 5))
        assertTrue(shouldRetryManagedAodPosition(4, 5))
        assertFalse(shouldRetryManagedAodPosition(5, 5))
    }

    @Test
    fun pausedAodSnapshotStaysVisibleButReleasesKeepAliveImmediately() {
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            positionFollowingEnabled = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val hidden = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = 3_000L,
            keepAlive = false
        )
        val retained = retainedAodSnapshotAfterUpdate(hidden, live, null, null, true, 3_000L, 30_000L)!!

        assertTrue(retained.visible)
        assertFalse(retained.keepAlive)
        assertTrue(retained.positionFollowingEnabled)
        assertEquals(6_000L, retained.positionMs)
        assertEquals(0f, retained.speed)
        assertEquals(
            retained,
            retainedAodSnapshotAfterUpdate(hidden, null, retained, null, true, 8_000L, 30_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(hidden, null, retained, null, true, 33_000L, 30_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(hidden, live, retained, null, false, 8_000L, 30_000L)
        )
    }

    @Test
    fun sharedPauseLingerSupportsImmediateBoundedAndIndefiniteModes() {
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val paused = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = 3_000L
        )

        assertEquals(null, retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 3_000L, 0L))
        listOf(5_000L, 10_000L, 30_000L).forEach { duration ->
            val retained = retainedAodSnapshotAfterUpdate(
                paused, live, null, null, true, 3_000L, duration
            )!!
            assertTrue(retainedAodSnapshotAfterUpdate(
                paused, null, retained, null, true, 3_000L + duration - 1L, duration
            ) != null)
            assertEquals(null, retainedAodSnapshotAfterUpdate(
                paused, null, retained, null, true, 3_000L + duration, duration
            ))
        }
        val indefinite = retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 3_000L, -1L)!!
        assertEquals(
            indefinite,
            retainedAodSnapshotAfterUpdate(paused, null, indefinite, null, true, Long.MAX_VALUE, -1L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 8_000L, 5_000L)
        )
    }

    @Test
    fun replayedPausedStateCannotRestartTheLingerFromItsOwnPublishTime() {
        // The producer republishes the same paused state whenever Spotify revises it, and every
        // publish carries a fresh updatedAtElapsedMs. Anchoring to the message re-froze the lyric
        // each time, so a track paused minutes ago came back for another full linger the moment
        // another player touched the media session — a stale lyric over someone else's AOD.
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            durationMs = 300_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val pausedAt = 3_000L
        val paused = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = pausedAt
        )
        val anchor = nextLyricRetentionAnchor(paused, null, pausedAt)!!
        assertEquals(pausedAt, anchor.atElapsedMs)
        assertTrue(anchor.pauseRetentionEligible)

        val retained = retainedAodSnapshotAfterUpdate(
            paused, live, null, anchor, true, pausedAt, 5_000L
        )!!
        assertEquals(pausedAt, retained.sampledAtElapsedMs)

        // 60 s later the producer republishes the pause with a current timestamp. The anchor is
        // unchanged, so the linger stays expired instead of restarting.
        val republished = paused.copy(updatedAtElapsedMs = 63_000L)
        val replayedAnchor = nextLyricRetentionAnchor(republished, anchor, 63_000L)
        assertEquals(anchor, replayedAnchor)
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                republished, live, null, replayedAnchor, true, 63_000L, 5_000L
            )
        )

        // Playback resuming ends the episode, and the next real pause anchors afresh.
        assertEquals(null, nextLyricRetentionAnchor(live, anchor, 64_000L))
        assertEquals(
            65_000L,
            nextLyricRetentionAnchor(
                paused.copy(updatedAtElapsedMs = 65_000L), null, 65_000L
            )?.atElapsedMs
        )
    }

    @Test
    fun stillPlayingTransportGapRetentionIsBoundedByThePowerGrace() {
        // A hidden edge marked still playing is a transport gap. Nothing else measures it once the
        // producer keeps republishing the gap, so the frozen lyric has to expire on the gap's own
        // bound rather than living on an edge that keeps moving forward.
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            durationMs = 300_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val gap = live.copy(visible = false, updatedAtElapsedMs = 2_000L)
        val anchor = nextLyricRetentionAnchor(gap, null, 2_000L)!!
        assertFalse(anchor.pauseRetentionEligible)

        assertTrue(
            retainedAodSnapshotAfterUpdate(gap, live, null, anchor, true, 2_000L, 5_000L) != null
        )
        assertEquals(
            anchor,
            nextLyricRetentionAnchor(gap.copy(updatedAtElapsedMs = 40_000L), anchor, 40_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                gap.copy(updatedAtElapsedMs = 40_000L), live, null, anchor, true, 40_000L, 5_000L
            )
        )
    }

    @Test
    fun delayedHideReplaysOnlyForInactiveCurrentControllerGeneration() {
        assertTrue(shouldReplaySuppressedPolicyHide(false, 4L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(true, 4L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(false, 3L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(false, 4L, 4L, false))
        assertFalse(shouldReplaySuppressedPolicyHide(false, -1L, 4L, true))
    }

    @Test
    fun unchangedManagedPositionIsReassertedWithoutAnimation() {
        assertFalse(shouldAnimateAodPosition(true, overridden = true, placementChanged = false))
        assertTrue(shouldAnimateAodPosition(true, overridden = true, placementChanged = true))
        assertTrue(shouldAnimateAodPosition(true, overridden = false, placementChanged = false))
        assertFalse(shouldAnimateAodPosition(false, overridden = true, placementChanged = true))
    }
}
