package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodPositionUpdateTest {
    @Test
    fun brightLinkageReservesTopClockMorphRegion() {
        assertEquals(AodRenderedClockBounds(0, 935), brightLinkageClockBounds(2670))
    }

    @Test
    fun physicalBrightClockMorphDoesNotDependOnLyricLinkageAcceptance() {
        assertTrue(shouldUseBrightClockMorphGeometry(true, true, false, 2))
        assertTrue(shouldUseBrightClockMorphGeometry(true, false, true, 2))
        assertFalse(shouldUseBrightClockMorphGeometry(true, true, false, 3))
        assertFalse(shouldUseBrightClockMorphGeometry(false, true, true, 2))
    }

    @Test
    fun managedClockBoundsRemainSingleStableGeometryAuthority() {
        assertEquals(
            AodRenderedClockBounds(1463, 2037),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(1851, 2425),
                controlledTop = 1463,
                controlledBottom = 2037,
                measuredTop = 1907,
                measuredBottom = 2350
            )
        )
    }

    @Test
    fun exactPhysicalClockBoundsOverrideDivergedManagedTarget() {
        assertEquals(
            AodRenderedClockBounds(263, 837),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 1851,
                controlledBottom = 2425,
                measuredTop = 1851,
                measuredBottom = 2425,
                exactPhysicalBounds = AodRenderedClockBounds(263, 837)
            )
        )
    }

    @Test
    fun exactClockCollisionAuthorityPrefersVisibleSystemUiThenAodTarget() {
        val systemUi = AodRenderedClockBounds(263, 837)
        val aodTarget = AodRenderedClockBounds(1851, 2425)

        assertEquals(systemUi, selectPhysicalAodClockBounds(systemUi, aodTarget))
        assertEquals(aodTarget, selectPhysicalAodClockBounds(null, aodTarget))
        assertEquals(
            aodTarget,
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(426, 837),
                controlledTop = aodTarget.top,
                controlledBottom = aodTarget.bottom,
                measuredTop = 426,
                measuredBottom = 837,
                exactPhysicalBounds = selectPhysicalAodClockBounds(null, null)
            )
        )
    }

    @Test
    fun opposingClockCrossfadeUsesManagedTargetBounds() {
        assertEquals(
            AodRenderedClockBounds(1851, 2425),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(426, 837),
                controlledTop = 1851,
                controlledBottom = 2425,
                measuredTop = 426,
                measuredBottom = 2425
            )
        )
    }

    @Test
    fun aodRevealProgressUsesSmoothElapsedTimeCurve() {
        assertEquals(0f, smoothAodRevealProgress(0f), 0.0001f)
        assertEquals(0.5f, smoothAodRevealProgress(0.5f), 0.0001f)
        assertEquals(1f, smoothAodRevealProgress(1f), 0.0001f)
    }

    @Test
    fun settledSnapshotAndLayoutRefreshCannotBypassAodAuthorityOrEligibility() {
        assertTrue(shouldRenderAodSnapshot(true, true, true, true, false))
        assertFalse(shouldRenderAodSnapshot(false, true, true, true, false))
        assertFalse(shouldRenderAodSnapshot(true, true, false, true, false))
        assertFalse(shouldRenderAodSnapshot(true, true, true, false, false))
        assertFalse(shouldRenderAodSnapshot(true, true, true, true, true))
        assertFalse(
            shouldRenderAodSnapshot(
                true,
                true,
                true,
                true,
                false,
                spotifyAuthorized = false
            )
        )
    }

    @Test
    fun positionUpdatesCoalesceToLatestValue() {
        val coalescer = AodPositionUpdateCoalescer()

        assertTrue(coalescer.offer(AodPositionUpdate(3, 2f, 4f, 600)))
        assertFalse(coalescer.offer(AodPositionUpdate(3, 8f, 12f, 580)))
        assertEquals(AodPositionUpdate(3, 8f, 12f, 580), coalescer.drain(3))
        assertNull(coalescer.drain(3))
    }

    @Test
    fun detachedGenerationCancelsPendingUpdate() {
        val coalescer = AodPositionUpdateCoalescer()
        coalescer.offer(AodPositionUpdate(3, 8f, 12f))

        assertNull(coalescer.drain(4))
    }

    @Test
    fun horizontalBurnInTranslationMovesAndClampsOverlaySlot() {
        assertEquals(
            AodSurfaceRect(80, 224, 960, 676),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, 20)
        )
        assertEquals(
            AodSurfaceRect(120, 224, 1000, 676),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, 500)
        )
    }

    @Test
    fun translatedStockBottomUsesWindowCoordinates() {
        assertEquals(240, stockBottomInRoot(100, 260, 80))
    }

    @Test
    fun laterStockBottomRecomputesVerticalSlot() {
        val before = calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700)
        val after = calculateAodSurfaceRect(1000, 700, 260, 24, 880, 700)

        assertEquals(224, before.top)
        assertEquals(284, after.top)
    }

    @Test
    fun fingerprintBoundaryReservesBottomSafeRegion() {
        assertEquals(
            AodSurfaceRect(60, 224, 940, 526),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, safeBottom = 550)
        )
    }

    @Test
    fun liveLinkageGeometryExposesFullTopAndBottomClockZones() {
        assertEquals(
            AodClockZoneBounds(100f, 900f, 120, 420, 920, 1220),
            resolveAodClockZoneBounds(
                AodClockGeometry(
                    mode = 3,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300
                )
            )
        )
    }

    @Test
    fun moduleManagedSixZonePatternMovesClockAndLyricsAcrossFullCanvas() {
        val geometry = AodClockGeometry(3, 120f, 100f, 20, 300, 18)
        val first = managedAodClockDecision("six_zone", 0, 0, 200f, geometry)!!
        val second = managedAodClockDecision("six_zone", 1, 0, 200f, geometry)!!

        assertEquals(AodSceneZone.CLOCK_BOTTOM, first.zone)
        assertEquals(18, first.appliedTranslationX)
        assertEquals(900f, first.appliedTranslationY, 0.0001f)
        assertEquals(AodSceneZone.CLOCK_TOP, second.zone)
        assertEquals(-18, second.appliedTranslationX)
        assertEquals(100f, second.appliedTranslationY, 0.0001f)
        assertEquals(
            com.eza.hyperglow.root.surface.PlacementRect(0f, 120f, 1080f, 896f),
            aodSceneSafeCanvas(1080, 2400, first.clockTop, first.lyricTopSafe, 24, first.zone)
        )
    }

    @Test
    fun configuredPatternsHaveBoundedDeterministicCycles() {
        assertEquals(1, aodBurnInPatternSlots("static_top").size)
        assertEquals(1, aodBurnInPatternSlots("static_bottom").size)
        assertEquals(6, aodBurnInPatternSlots("six_zone").size)
        assertEquals(4, aodBurnInPatternSlots("four_corner").size)
        assertEquals(2, aodBurnInPatternSlots("vertical_swap").size)
        val geometry = AodClockGeometry(3, 120f, 100f, 20, 300, 18)
        assertEquals(
            managedAodClockDecision("vertical_swap", 0, 0, 200f, geometry),
            managedAodClockDecision("vertical_swap", 2, 0, 200f, geometry)
        )
        val static = managedAodClockDecision("static_bottom", 0, 0, 200f, geometry)
        val staticTop = managedAodClockDecision("static_top", 0, 0, 200f, geometry)
        assertEquals(static, managedAodClockDecision("static_bottom", 100, 0, 200f, geometry))
        assertEquals(staticTop, managedAodClockDecision("static_top", 100, 0, 200f, geometry))
        assertEquals(AodSceneZone.CLOCK_BOTTOM, static?.zone)
        assertEquals(AodSceneZone.CLOCK_TOP, staticTop?.zone)
        assertEquals(0, static?.appliedTranslationX)
        assertFalse(managedAodPatternRepeats("static_top"))
        assertFalse(managedAodPatternRepeats("static_bottom"))
        assertTrue(managedAodPatternRepeats("six_zone"))
    }

    @Test
    fun stockLinkageInitialPositionIsDeterministicBeforeFirstTranslationCallback() {
        assertEquals(
            AodNaturalTranslation(0, 500f),
            naturalAodTranslation(
                AodClockGeometry(
                    mode = 3,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300,
                    translationXStep = 18
                ),
                moveCurrent = 8
            )
        )
    }

    @Test
    fun standardAodInitialPositionUsesXiaomiGridIndex() {
        assertEquals(
            AodNaturalTranslation(18, 300f),
            naturalAodTranslation(
                AodClockGeometry(
                    mode = 0,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300,
                    translationXStep = 18
                ),
                moveCurrent = 16
            )
        )
    }

    @Test
    fun managedAnchorReappliesWhenXiaomiGeometryChangesAcrossScreenState() {
        val before = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!
        val after = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 160f, 90f, 40, 360)
        )!!

        assertTrue(managedAodPlacementChanged(before, after))
        assertFalse(managedAodPlacementChanged(after, after.copy(requestedTranslationY = 999f)))
    }

    @Test
    fun failedManagedAdvanceRollsBackOnlyUnchangedAttempt() {
        val attempted = managedAodClockDecision(
            "six_zone",
            2,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!

        assertTrue(shouldRollbackFailedManagedAdvance(2, attempted, 2, attempted))
        assertFalse(shouldRollbackFailedManagedAdvance(3, attempted, 2, attempted))
        assertFalse(
            shouldRollbackFailedManagedAdvance(
                2,
                attempted.copy(appliedTranslationX = attempted.appliedTranslationX + 1),
                2,
                attempted
            )
        )
    }

    @Test
    fun failedStockRestoreClearsOnlyCapturedManagedPlacement() {
        val captured = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!

        assertTrue(shouldClearFailedManagedRestore(0, captured, 0, captured))
        assertFalse(
            shouldClearFailedManagedRestore(
                0,
                captured.copy(appliedTranslationY = captured.appliedTranslationY + 1f),
                0,
                captured
            )
        )
        assertFalse(shouldClearFailedManagedRestore(1, captured, 0, captured))
        assertFalse(shouldClearFailedManagedRestore(0, null, 0, captured))
    }

    @Test
    fun clockBottomZoneUsesEntireAlreadyBoundedLyricRegion() {
        assertEquals(1f, aodPlacementMaxHeightFraction(0.42f, AodSceneZone.CLOCK_BOTTOM))
        assertEquals(0.42f, aodPlacementMaxHeightFraction(0.42f, AodSceneZone.CLOCK_TOP))
    }

    @Test
    fun renderedClockPositionOwnsTheFreeSideDuringManagedMotion() {
        assertEquals(
            AodSceneZone.CLOCK_TOP,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_BOTTOM,
                AodRenderedClockBounds(120, 420),
                rootHeight = 2400,
                margin = 24
            )
        )
        assertEquals(
            AodSceneZone.CLOCK_BOTTOM,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1740, 2040),
                rootHeight = 2400,
                margin = 24
            )
        )
        assertEquals(
            AodSceneZone.STOCK,
            resolveRenderedAodSceneZone(
                AodSceneZone.STOCK,
                AodRenderedClockBounds(1740, 2040),
                rootHeight = 2400,
                margin = 24
            )
        )
    }

    @Test
    fun renderedClockNeverOverridesManagedTarget() {
        assertEquals(
            AodRenderedClockBounds(120, 420),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(700, 1100),
                controlledTop = 120,
                controlledBottom = 420,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
        assertEquals(
            AodRenderedClockBounds(120, 420),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 120,
                controlledBottom = 420,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
        assertEquals(
            AodRenderedClockBounds(80, 380),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 420,
                controlledBottom = 120,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
    }

    @Test
    fun unsupportedWallpaperModePassesThroughWithoutClockMutation() {
        val decision = managedAodClockDecision(
            "six_zone",
            0,
            10,
            50f,
            AodClockGeometry(2, 120f, 100f, 20, 300)
        )

        assertNull(decision)
    }
}
