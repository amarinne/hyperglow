package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockscreenSurfaceControllerTest {
    @Test
    fun selectedLineLimitExpandsEstimateAndNoLimitUsesSafeAreaCeiling() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val threeLines = estimatedLockscreenSceneHeight(base.copy(lyricLineLimit = 3), 1f)
        val fiveLines = estimatedLockscreenSceneHeight(base.copy(lyricLineLimit = 5), 1f)

        assertTrue(fiveLines > threeLines)
        assertTrue(estimatedLockscreenSceneHeight(base.copy(lyricLineLimit = 0), 1f).isInfinite())
    }

    @Test
    fun lockscreenKeepAwakeRequiresActiveVisiblePlayback() {
        assertTrue(shouldKeepLockscreenAwake(true, true, false, true, 1f))
        assertFalse(shouldKeepLockscreenAwake(false, true, false, true, 1f))
        assertFalse(shouldKeepLockscreenAwake(true, false, false, true, 1f))
        assertFalse(shouldKeepLockscreenAwake(true, true, true, true, 1f))
        assertFalse(shouldKeepLockscreenAwake(true, true, false, false, 1f))
        assertFalse(shouldKeepLockscreenAwake(true, true, false, true, 0f))
    }

    @Test
    fun refreshGateCoalescesUntilFrameConsumesRequest() {
        val gate = LatestFrameRequestGate()

        assertTrue(gate.request())
        assertFalse(gate.request())
        assertTrue(gate.consume())
        assertFalse(gate.consume())
        assertTrue(gate.request())
        gate.cancel()
        assertFalse(gate.consume())
    }

    @Test
    fun notificationMotionTrackerSignalsOnlyChangedFingerprints() {
        val tracker = LockscreenMotionChangeTracker()

        assertFalse(tracker.update(10L))
        assertFalse(tracker.update(10L))
        assertTrue(tracker.update(11L))
        tracker.clear()
        assertFalse(tracker.update(11L))
    }

    @Test
    fun identicalFrameGeometryDoesNotRequestAnotherLayoutPass() {
        assertFalse(frameLayoutGeometryChanged(100, 80, 12, 20, 100, 80, 12, 20))
        assertTrue(frameLayoutGeometryChanged(100, 80, 12, 20, 100, 81, 12, 20))
    }

    @Test
    fun reverseAnchorWaitsForMinimumDelayAndQuietGeometry() {
        val gate = LockscreenAnchorStabilityGate(48L, 32L, 240L)
        val initial = LockscreenSceneRect(44, 1420, 1156, 1900)
        val settled = LockscreenSceneRect(44, 1204, 1156, 1684)

        gate.start(1_000L, expectedRect = null)
        assertFalse(gate.observe(initial, 1_000L))
        assertFalse(gate.observe(initial, 1_047L))
        assertFalse(gate.observe(settled, 1_048L))
        assertFalse(gate.observe(settled, 1_079L))
        assertTrue(gate.observe(settled, 1_080L))
    }

    @Test
    fun reverseAnchorRejectsTransientLowerRectUntilPreAodRectReturns() {
        val gate = LockscreenAnchorStabilityGate(48L, 32L, 240L)
        val expected = LockscreenSceneRect(44, 1204, 1156, 1684)
        val transient = LockscreenSceneRect(44, 1420, 1156, 1900)

        gate.start(1_000L, expected)
        assertFalse(gate.observe(transient, 1_048L))
        assertFalse(gate.observe(transient, 1_239L))
        assertTrue(gate.observe(expected, 1_240L))
    }

    @Test
    fun reverseAnchorWithoutHistoryUsesBoundedFallbackBeforeCoordinatorTimeout() {
        val gate = LockscreenAnchorStabilityGate(48L, 32L, 240L)
        val first = LockscreenSceneRect(44, 1420, 1156, 1900)
        val changing = LockscreenSceneRect(44, 1380, 1156, 1860)

        gate.start(1_000L, expectedRect = null)
        assertFalse(gate.observe(first, 1_048L))
        assertTrue(gate.observe(changing, 1_240L))
    }

    @Test
    fun settledRectTrackerPreservesLastQuietLockscreenGeometry() {
        val tracker = LockscreenSettledRectTracker(96L)
        val settled = LockscreenSceneRect(44, 1204, 1156, 1684)
        val transient = LockscreenSceneRect(44, 1420, 1156, 1900)

        tracker.observe(settled, 1_000L)
        assertEquals(settled, tracker.settledRect(1_096L))
        tracker.observe(transient, 1_100L)
        assertEquals(settled, tracker.settledRect(1_150L))
    }

    @Test
    fun sceneStartsBelowClockAndReservesBottomArea() {
        assertEquals(
            LockscreenSceneRect(60, 316, 940, 780),
            calculateLockscreenSceneRect(1000, 900, 300, 16, 120, 880)
        )
    }

    @Test
    fun notificationCollisionShrinksSceneBeforeOverlap() {
        assertEquals(
            LockscreenSceneRect(60, 316, 940, 484),
            calculateLockscreenSceneRect(1000, 900, 300, 16, 120, 880, 500)
        )
    }

    @Test
    fun notificationAboveLyricRegionFailsClosed() {
        assertEquals(
            LockscreenSceneRect(60, 316, 940, 316),
            calculateLockscreenSceneRect(1000, 900, 300, 16, 120, 880, 250)
        )
    }

    @Test
    fun notificationCollisionUsesVisibleContentInsteadOfFullscreenStackContainer() {
        val candidates = listOf(
            LockscreenNotificationCandidate(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                0
            ),
            LockscreenNotificationCandidate(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                900,
                1200
            ),
            LockscreenNotificationCandidate(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView",
                720,
                860
            )
        )

        assertEquals(720, topmostLockscreenNotificationTop(candidates, 2670))
        assertEquals(
            LockscreenNotificationBounds(720, 1200),
            lockscreenNotificationBounds(candidates, 2670)
        )
    }

    @Test
    fun notificationLayoutChoosesLargerFreeRegionBelowMediaContent() {
        assertEquals(
            com.eza.hyperglow.root.surface.PlacementRect(0f, 1350f, 1200f, 2310f),
            largestLockscreenFreeRegion(
                rootWidth = 1200,
                rootHeight = 2670,
                clockBottom = 578,
                margin = 50,
                bottomReserve = 360,
                notificationBounds = LockscreenNotificationBounds(955, 1300)
            )
        )
    }

    @Test
    fun safeCardLayoutUsesRemainingRegionAfterNotifications() {
        assertEquals(
            com.eza.hyperglow.root.surface.PlacementRect(0f, 1325f, 1200f, 2310f),
            lockscreenCardRegionAfterNotifications(
                rootWidth = 1200,
                rootHeight = 2670,
                clockBottom = 578,
                topMargin = 50,
                notificationGap = 25,
                bottomReserve = 360,
                notificationBounds = LockscreenNotificationBounds(955, 1300)
            )
        )
    }

    @Test
    fun safeCardLayoutWithoutNotificationsStartsBelowClock() {
        assertEquals(
            com.eza.hyperglow.root.surface.PlacementRect(0f, 628f, 1200f, 2310f),
            lockscreenCardRegionAfterNotifications(
                rootWidth = 1200,
                rootHeight = 2670,
                clockBottom = 578,
                topMargin = 50,
                notificationGap = 25,
                bottomReserve = 360,
                notificationBounds = null
            )
        )
    }

    @Test
    fun notificationBoundsCarryNativeCardWidthForLyricOutline() {
        assertEquals(
            LockscreenNotificationBounds(900, 1300, 42, 1158),
            lockscreenNotificationBounds(
                listOf(
                    LockscreenNotificationCandidate(
                        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView",
                        top = 900,
                        bottom = 1300,
                        left = 42,
                        right = 1158
                    )
                ),
                hostHeight = 2670
            )
        )
    }

    @Test
    fun notificationLayoutUsesActualHeightInsteadOfExpandedLayoutHeight() {
        assertEquals(
            LockscreenNotificationCandidate(
                className = "ExpandableNotificationRow",
                top = 1213,
                bottom = 1389,
                left = 44,
                right = 1156
            ),
            lockscreenNotificationCandidateFromLayout(
                className = "ExpandableNotificationRow",
                stackLeft = 0,
                stackTop = 0,
                childX = 44f,
                childY = 1213f,
                layoutWidth = 1112,
                layoutHeight = 404,
                actualHeight = 176,
                clipTopAmount = 0,
                clipBottomAmount = 0,
                clipBounds = LockscreenNotificationClipBounds(0, 0, 1112, 176)
            )
        )
    }

    @Test
    fun mediaAndNotificationUnionEndsAtActualVisibleBottom() {
        val media = lockscreenNotificationCandidateFromLayout(
            "MiuiMediaHeaderView", 0, 0, 44f, 597f,
            1112, 582, 582, 0, 0,
            LockscreenNotificationClipBounds(0, 0, 1112, 582)
        )!!
        val notification = lockscreenNotificationCandidateFromLayout(
            "ExpandableNotificationRow", 0, 0, 44f, 1213f,
            1112, 404, 176, 0, 0,
            LockscreenNotificationClipBounds(0, 0, 1112, 176)
        )!!

        assertEquals(
            LockscreenNotificationBounds(597, 1389, 44, 1156),
            lockscreenNotificationBounds(listOf(media, notification), 2670)
        )
    }

    @Test
    fun notificationClipAmountsLimitVisibleLayoutState() {
        assertEquals(
            LockscreenNotificationCandidate("ExpandableNotificationRow", 1020, 1170, 60, 1140),
            lockscreenNotificationCandidateFromLayout(
                "ExpandableNotificationRow", 0, 0, 40f, 1000f,
                1120, 300, 200, 20, 30,
                LockscreenNotificationClipBounds(20, 10, 1100, 180)
            )
        )
    }

    @Test
    fun invisibleNotificationChildrenAreReservedOnlyDuringLinkage() {
        assertFalse(shouldIncludeLockscreenNotificationChild(4, 0f, linkageActive = false))
        assertTrue(shouldIncludeLockscreenNotificationChild(4, 0f, linkageActive = true))
        assertTrue(shouldIncludeLockscreenNotificationChild(0, 1f, linkageActive = false))
        assertFalse(shouldIncludeLockscreenNotificationChild(8, 1f, linkageActive = true))
    }

    @Test
    fun cachedNotificationBoundsPersistWhileNotificationStateRemainsActive() {
        val valid = LockscreenNotificationBounds(955, 1300)

        assertEquals(
            LockscreenNotificationGeometry(valid, valid),
            resolveLockscreenNotificationGeometry(true, null, valid, 2670)
        )
    }

    @Test
    fun inactiveNotificationStateClearsCachedBoundsImmediately() {
        val valid = LockscreenNotificationBounds(955, 1300)

        assertEquals(
            LockscreenNotificationGeometry(null, null),
            resolveLockscreenNotificationGeometry(false, null, valid, 2670)
        )
    }

    @Test
    fun activeNotificationStateWithoutCachedBoundsFailsClosed() {
        assertEquals(
            LockscreenNotificationGeometry(
                effectiveBounds = LockscreenNotificationBounds(0, 2670),
                cachedBounds = null
            ),
            resolveLockscreenNotificationGeometry(true, null, null, 2670)
        )
    }

    @Test
    fun changedValidNotificationBoundsReplaceCachedBounds() {
        val cached = LockscreenNotificationBounds(955, 1300)
        val current = LockscreenNotificationBounds(893, 1380, 127, 1073)

        assertEquals(
            LockscreenNotificationGeometry(current, current),
            resolveLockscreenNotificationGeometry(true, current, cached, 2670)
        )
    }

    @Test
    fun clockBottomPrefersXiaomiAnchorAndUsesDeepestFallbackOnlyWhenMissing() {
        assertEquals(
            578,
            preferredLockscreenClockBottom(2670, preferred = 578, fallbacks = listOf(837, 994))
        )
        assertEquals(
            837,
            preferredLockscreenClockBottom(2670, preferred = 0, fallbacks = listOf(720, 837))
        )
        assertEquals(837, maximumLockscreenClockBottom(2670, listOf(578, 837, 720)))
        assertEquals(2670, maximumLockscreenClockBottom(2670, listOf(578, 3000)))
        assertEquals(0, maximumLockscreenClockBottom(2670, emptyList()))
    }

    @Test
    fun unknownNotificationContentDoesNotBypassFailClosedCaller() {
        val candidates = listOf(
            LockscreenNotificationCandidate("android.view.View", 500),
            LockscreenNotificationCandidate("unknown.CustomNotification", 600)
        )

        assertEquals(null, topmostLockscreenNotificationTop(candidates, 2670))
        assertTrue(
            isLockscreenNotificationContentClass(
                "com.android.systemui.statusbar.notification.zen.ZenModeView"
            )
        )
    }

    @Test
    fun impossibleGeometryFailsAsZeroHeight() {
        assertEquals(
            LockscreenSceneRect(60, 896, 940, 896),
            calculateLockscreenSceneRect(1000, 900, 880, 16, 20, 880)
        )
    }

    @Test
    fun visibilityRequiresEverySecurityAndCapabilityGate() {
        val allowed = LockscreenVisibilityInputs(
            featureEnabled = true,
            supported = true,
            defaultTheme = true,
            primaryDisplay = true,
            keyguardShowing = true,
            bouncerShowing = false,
            freshSnapshot = true,
            usableArea = true
        )

        assertTrue(shouldShowLockscreen(allowed))
        assertFalse(shouldShowLockscreen(allowed.copy(featureEnabled = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(supported = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(defaultTheme = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(primaryDisplay = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(keyguardShowing = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(bouncerShowing = true)))
        assertFalse(shouldShowLockscreen(allowed.copy(freshSnapshot = false)))
        assertFalse(shouldShowLockscreen(allowed.copy(usableArea = false)))
    }

    @Test
    fun snapshotEligibilityRejectsDisabledDemoFailedAndStaleStates() {
        val snapshot = com.eza.hyperglow.root.projection.LyricSnapshot(
            revision = 1,
            trackGeneration = 1,
            updatedAtElapsedMs = 1_000,
            visible = true,
            playbackActive = true,
            lockscreenEnabled = true,
            original = "line"
        )

        assertTrue(shouldRenderLockscreenSnapshot(snapshot, true, false, 2_000))
        assertFalse(shouldRenderLockscreenSnapshot(snapshot, false, false, 2_000))
        assertFalse(shouldRenderLockscreenSnapshot(snapshot, true, true, 2_000))
        assertFalse(
            shouldRenderLockscreenSnapshot(
                snapshot.copy(metadata = "AOD DEMO · layout"),
                true,
                false,
                2_000
            )
        )
        assertFalse(shouldRenderLockscreenSnapshot(snapshot, true, false, 6_001))
    }

    @Test
    fun pausedSnapshotFreezesAtProjectedPositionAndReleasesKeepAlive() {
        val snapshot = com.eza.hyperglow.root.projection.LyricSnapshot(
            visible = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1.25f,
            keepAlive = true,
            original = "line"
        )

        val frozen = freezeLockscreenSnapshot(snapshot, 3_000L)

        assertEquals(6_500L, frozen.positionMs)
        assertEquals(3_000L, frozen.sampledAtElapsedMs)
        assertEquals(0f, frozen.speed)
        assertFalse(frozen.keepAlive)
        assertTrue(frozen.visible)
    }

    @Test
    fun lockscreenSnapshotLifetimeFollowsStockMediaPlayerPresence() {
        val live = com.eza.hyperglow.root.projection.LyricSnapshot(
            visible = true,
            playbackActive = true,
            original = "live"
        )
        val hidden = live.copy(visible = false)
        val retained = live.copy(original = "paused", speed = 0f)

        assertEquals(live, resolveLockscreenMediaSnapshot(live, retained, true))
        assertEquals(retained, resolveLockscreenMediaSnapshot(hidden, retained, true))
        assertEquals(null, resolveLockscreenMediaSnapshot(live, retained, false))
        assertEquals(null, resolveLockscreenMediaSnapshot(hidden, retained, false))
        assertEquals(
            live,
            resolveLockscreenMediaSnapshot(
                live,
                retained,
                mediaPlayerPresent = false,
                transitionSourceActive = true
            )
        )
        assertEquals(
            retained,
            resolveLockscreenMediaSnapshot(
                hidden,
                retained,
                mediaPlayerPresent = false,
                transitionSourceActive = true
            )
        )
    }

    @Test
    fun repeatedHiddenReplayKeepsFrozenPausedSnapshot() {
        val live = com.eza.hyperglow.root.projection.LyricSnapshot(
            visible = true,
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
            updatedAtElapsedMs = 3_000L
        )
        val first = retainedLockscreenSnapshotAfterUpdate(hidden, live, null, null, 3_000L, 10_000L)!!
        val replayed = retainedLockscreenSnapshotAfterUpdate(hidden, live, first, null, 8_000L, 10_000L)

        assertEquals(first, replayed)
        assertEquals(6_000L, replayed!!.positionMs)
        assertEquals(0f, replayed.speed)
        assertEquals(
            null,
            retainedLockscreenSnapshotAfterUpdate(hidden, live, null, null, 13_000L, 10_000L)
        )
    }

    @Test
    fun nonSpotifyAndExpiredPauseSnapshotsDoNotRender() {
        val live = com.eza.hyperglow.root.projection.LyricSnapshot(
            visible = true,
            lockscreenEnabled = true,
            updatedAtElapsedMs = 1_000L,
            original = "line"
        )
        val paused = live.copy(
            playbackActive = false,
            pauseRetentionEligible = true,
            speed = 0f
        )

        assertFalse(shouldRenderLockscreenSnapshot(live, true, false, 2_000L))
        assertTrue(shouldRenderLockscreenSnapshot(paused, true, false, 2_000L))
        assertEquals(
            null,
            retainedLockscreenSnapshotAfterUpdate(
                paused.copy(visible = false),
                live.copy(playbackActive = true),
                null,
                null,
                3_000L,
                0L
            )
        )
    }

    @Test
    fun sameHostAttachIsIdempotentAndInsertionStaysBehindNativeChildren() {
        val host = Any()

        assertTrue(shouldReuseLockscreenHost(host, host, true))
        assertFalse(shouldReuseLockscreenHost(host, Any(), true))
        assertFalse(shouldReuseLockscreenHost(host, host, false))
        assertEquals(0, LOCKSCREEN_INSERTION_INDEX)
    }
}
