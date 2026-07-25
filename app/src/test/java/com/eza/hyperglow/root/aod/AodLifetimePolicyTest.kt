package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricSnapshot
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
    fun timedPowerSessionSurvivesTransientHiddenEdgeAndRetriesDetachedWake() {
        val timed = LyricSnapshot(
            visible = true,
            keepAlive = true,
            lineStartMs = 1_000L,
            lineEndMs = 4_000L,
            original = "line"
        )
        val untimedLease = timed.copy(lineStartMs = 0L, lineEndMs = 0L)

        assertTrue(hasPersistentTimedAodPower(timed))
        assertFalse(hasPersistentTimedAodPower(untimedLease))
        assertTrue(shouldStartTimedAodPowerGrace(true, true, true, true))
        assertFalse(shouldStartTimedAodPowerGrace(true, false, true, true))
        assertFalse(shouldStartTimedAodPowerGrace(true, true, true, false))
        assertTrue(shouldRetryDetachedAodWake(false, true))
        assertFalse(shouldRetryDetachedAodWake(true, true))
        assertFalse(shouldRetryDetachedAodWake(false, false))
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
            keepAlive = true,
            positionFollowingEnabled = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val hidden = live.copy(visible = false, playbackActive = false, keepAlive = false)
        val retained = retainedAodSnapshotAfterUpdate(hidden, live, null, true, 3_000L)!!

        assertTrue(retained.visible)
        assertFalse(retained.keepAlive)
        assertTrue(retained.positionFollowingEnabled)
        assertEquals(6_000L, retained.positionMs)
        assertEquals(0f, retained.speed)
        assertEquals(retained, retainedAodSnapshotAfterUpdate(hidden, null, retained, true, 8_000L))
        assertFalse(retainedAodSnapshotAfterUpdate(hidden, null, retained, true, 32_999L)!!.keepAlive)
        val expired = retainedAodSnapshotAfterUpdate(hidden, null, retained, true, 33_000L)!!
        assertTrue(expired.visible)
        assertFalse(expired.keepAlive)
        assertTrue(expired.positionFollowingEnabled)
        assertEquals(0f, expired.speed)
        assertEquals(null, retainedAodSnapshotAfterUpdate(hidden, live, retained, false, 8_000L))
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
