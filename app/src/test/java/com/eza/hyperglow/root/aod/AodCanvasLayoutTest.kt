package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodCanvasLayoutTest {
    @Test
    fun semanticPaletteResolvesOnceToBoundedColors() {
        val default = resolveAodPalette(emptyMap())
        val dimmed = resolveAodPalette(
            mapOf(
                "primaryText" to "dimmed",
                "metadataText" to "dimmed",
                "glow" to "external"
            )
        )

        assertNotEquals(default.primaryText, dimmed.primaryText)
        assertNotEquals(default.metadataText, dimmed.metadataText)
        assertEquals(default.glow, dimmed.glow)
    }

    @Test
    fun sentenceFillSpansWrappedLinesContinuously() {
        assertEquals(listOf(1f, 1f / 3f), splitContinuousFill(0.5f, listOf(100f, 300f)))
    }

    @Test
    fun wrappedTimedTransliterationKeepsOneOrderedWordSequence() {
        val segments = listOf(
            SecondaryTimedSegment("ming yun", 80f, 10f, 0L, 100L),
            SecondaryTimedSegment("que yao", 80f, 10f, 100L, 200L),
            SecondaryTimedSegment("wo men", 80f, 10f, 200L, 300L),
            SecondaryTimedSegment("wei nan", 80f, 0f, 300L, 400L)
        )

        assertEquals(
            listOf(0 until 2, 2 until 4),
            secondaryTimedLineRanges(segments, available = 190f, maxLines = 2)
        )
        assertEquals(1f, secondaryTimedProgress(250L, 100L, 200L), 0.0001f)
        assertEquals(0.5f, secondaryTimedProgress(250L, 200L, 300L), 0.0001f)
        assertEquals(0f, secondaryTimedProgress(250L, 300L, 400L), 0.0001f)
        assertEquals(
            timedWordProgress(250L, 200L, 300L),
            secondaryTimedProgress(250L, 200L, 300L),
            0.0001f
        )
    }

    @Test
    fun adaptiveOffTimedTransliterationKeepsOneTimedVisualLine() {
        val segments = listOf(
            SecondaryTimedSegment("first", 80f, 10f, 0L, 100L),
            SecondaryTimedSegment("second", 80f, 10f, 100L, 200L),
            SecondaryTimedSegment("third", 80f, 0f, 200L, 300L)
        )

        assertEquals(
            listOf(segments.indices),
            secondaryTimedVisualRanges(
                segments,
                available = 100f,
                maxLines = 2,
                wrap = false
            )
        )
        assertEquals(0.5f, secondaryTimedProgress(150L, 100L, 200L), 0.0001f)
    }

    @Test
    fun blankWordReadingDoesNotDiscardOtherTimedReadings() {
        val words = listOf(
            AodCanvasWord("first", "first", 0L, 100L, true),
            AodCanvasWord("missing", "", 100L, 200L, true),
            AodCanvasWord("third", "third", 200L, 300L, false)
        )

        assertEquals(listOf(0, 2), timedRomanizedWordIndexes(words))
    }

    @Test
    fun repeatedTextStillChangesIdentityAcrossRowsAndTracks() {
        val first = AodCanvasLineIdentity(7L, 1_000L, 2_000L, "same")

        assertEquals(first, AodCanvasLineIdentity(7L, 1_000L, 2_000L, "same"))
        assertNotEquals(first, AodCanvasLineIdentity(7L, 3_000L, 4_000L, "same"))
        assertNotEquals(first, AodCanvasLineIdentity(8L, 1_000L, 2_000L, "same"))
    }

    @Test
    fun topToBottomFillUsesOneSharedBlockCoordinate() {
        assertEquals(100f, sharedBlockClipBottom(0f, 100f, 500f), 0.0001f)
        assertEquals(300f, sharedBlockClipBottom(0.5f, 100f, 500f), 0.0001f)
        assertEquals(500f, sharedBlockClipBottom(1f, 100f, 500f), 0.0001f)
    }

    @Test
    fun lineLevelSyncHonorsConfiguredSweepDirection() {
        assertEquals(
            "Left to right (main only)",
            resolvedLineSyncFillMode(true, "Left to right (sentence)")
        )
        assertEquals(
            "Left to right (main only)",
            resolvedLineSyncFillMode(true, "Left to right (main only)")
        )
        assertEquals("None", resolvedLineSyncFillMode(true, "None"))
        assertEquals(
            "Top to bottom",
            resolvedLineSyncFillMode(true, "Top to bottom")
        )
        assertEquals(
            "Left to right (whole block)",
            resolvedLineSyncFillMode(true, "Left to right (whole block)")
        )
    }

    @Test
    fun surfaceProfileOverridesProducerLineLevelSweepDirection() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right (whole block)"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(
            "Left to right (whole block)",
            LyricSnapshot(lineSyncFillMode = "Top to bottom")
                .toAodCanvasContent(profile)
                .lineSyncFillMode
        )
    }

    @Test
    fun surfaceProfileCanSuppressFuriganaWithoutChangingTransportSnapshot() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(rubyVisible = false)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(
            original = "漢字",
            ruby = listOf(LyricRuby(0, 2, "かんじ"))
        )

        assertTrue(snapshot.ruby.isNotEmpty())
        assertTrue(snapshot.toAodCanvasContent(profile).ruby.isEmpty())
    }

    @Test
    fun metadataSizingUsesBoundedScaleAndHeightReservation() {
        assertEquals(0.5f, metadataTextSizeMultiplier(1), 0.0001f)
        assertEquals(1f, metadataTextSizeMultiplier(100), 0.0001f)
        assertEquals(2f, metadataTextSizeMultiplier(900), 0.0001f)
        assertEquals(36f, metadataWidgetHeightDp(100), 0.0001f)
        assertTrue(metadataWidgetHeightDp(200) > metadataWidgetHeightDp(50))
    }

    @Test
    fun loadingMetadataCanMorphOnlyIntoMatchingVisiblePersistentMetadata() {
        assertTrue(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Song · Artist",
                true
            )
        )
        assertFalse(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Other · Artist",
                true
            )
        )
        assertFalse(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Song · Artist",
                false
            )
        )
    }

    @Test
    fun legacyLeftToRightProfileMigratesToMainLyricSweep() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals("Left to right (main only)", profile.lineSyncFillMode)
    }

    @Test
    fun lyricLineLimitSupportsOneThroughFiveAndUnboundedLayout() {
        assertEquals(1, resolvedLyricLayoutLineLimit(1, originalLength = 50, wordCount = 10))
        assertEquals(5, resolvedLyricLayoutLineLimit(5, originalLength = 50, wordCount = 10))
        assertEquals(50, resolvedLyricLayoutLineLimit(0, originalLength = 50, wordCount = 10))
    }

    @Test
    fun secondaryTextBrightnessIsStaticAndProfileControlled() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryTextBright = false)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val content = LyricSnapshot(romanized = "reading")
            .toAodCanvasContent(profile)

        assertFalse(content.secondaryTextBright)
        assertEquals(1f, staticSecondaryTextFactor(true), 0.0001f)
        assertEquals(0.35f, staticSecondaryTextFactor(false), 0.0001f)
    }

    @Test
    fun gradientSweepUsesBroadZoneAndFinishesOutsideVisibleExtent() {
        assertEquals(GradientSweepZone(-40f, 0f), gradientSweepZone(0f, 100f))
        assertEquals(GradientSweepZone(30f, 70f), gradientSweepZone(0.5f, 100f))
        assertEquals(GradientSweepZone(100f, 140f), gradientSweepZone(1f, 100f))
    }

    @Test
    fun lineLevelRowsWithTransportWordsStillUseOneSharedCanvasSweep() {
        val content = LyricSnapshot(
            original = "絡み合う迷宮",
            lineLevelSync = true,
            lineStartMs = 1_000L,
            lineEndMs = 3_000L,
            words = listOf(LyricWord("絡み合う", "karamiau", 1_000L, 2_000L, false))
        ).toAodCanvasContent()

        assertTrue(content.lineLevelSync)
        assertTrue(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = content.lineLevelSync,
                hasOriginalLines = true,
                animationMode = content.animationMode,
                lineStartMs = content.lineStartMs,
                lineEndMs = content.lineEndMs
            )
        )
        assertFalse(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = false,
                hasOriginalLines = true,
                animationMode = content.animationMode,
                lineStartMs = content.lineStartMs,
                lineEndMs = content.lineEndMs
            )
        )
    }

    @Test
    fun rubyStartSelectsContainingWrappedLine() {
        assertEquals(0, rubyLineIndex(4, listOf(0, 8), listOf(8, 16)))
        assertEquals(1, rubyLineIndex(8, listOf(0, 8), listOf(8, 16)))
        assertNull(rubyLineIndex(16, listOf(0, 8), listOf(8, 16)))
    }

    @Test
    fun rubySpanOverhangDoesNotMoveBaseRun() {
        val geometry = rubySpanGeometry(10f, 20f, 30f)
        assertEquals(5f, geometry.spanX, 0.0001f)
        assertEquals(30f, geometry.spanWidth, 0.0001f)
        assertEquals(10f, geometry.baseX, 0.0001f)
        assertEquals(20f, geometry.baseWidth, 0.0001f)
        assertEquals(20f, geometry.rubyCenterX, 0.0001f)
        assertEquals(0f, geometry.extraWidth, 0.0001f)
    }

    @Test
    fun centeredRubyDrawUsesBaseCenterWithoutSubtractingHalfWidthTwice() {
        assertEquals(30f, rubyDrawCenterX(10f, 20f), 0.0001f)
    }

    @Test
    fun narrowerRubyKeepsNeighborCoordinatesUnchanged() {
        val geometry = rubySpanGeometry(10f, 20f, 24f)
        assertEquals(8f, geometry.spanX, 0.0001f)
        assertEquals(24f, geometry.spanWidth, 0.0001f)
        assertEquals(10f, geometry.baseX, 0.0001f)
        assertEquals(0f, geometry.extraWidth, 0.0001f)
    }

    @Test
    fun rubyBaseTextRunsPrecomputePlainAndRubyCoordinates() {
        val measuredEnds = mutableListOf<Int>()

        assertEquals(
            listOf(
                OriginalTextRun(0, 2, 0f),
                OriginalTextRun(2, 4, 20f),
                OriginalTextRun(4, 6, 40f),
                OriginalTextRun(6, 8, 60f)
            ),
            originalTextRuns(
                textLength = 8,
                rubyBaseRuns = listOf(
                    OriginalTextRun(2, 4, 20f),
                    OriginalTextRun(4, 6, 40f)
                )
            ) { end ->
                measuredEnds += end
                end * 10f
            }
        )
        assertEquals(listOf(0, 6), measuredEnds)
    }

    @Test
    fun overlappingRubyBaseTextRunsNeverRedrawConsumedText() {
        assertEquals(
            listOf(
                OriginalTextRun(0, 2, 0f),
                OriginalTextRun(2, 5, 20f),
                OriginalTextRun(5, 7, 30f),
                OriginalTextRun(7, 8, 70f)
            ),
            originalTextRuns(
                textLength = 8,
                rubyBaseRuns = listOf(
                    OriginalTextRun(2, 5, 20f),
                    OriginalTextRun(3, 7, 30f)
                )
            ) { end -> end * 10f }
        )
    }

    @Test
    fun sizeLadderUsesLiveCardMultiplier() {
        assertEquals(28f * 0.68f, baseTextSizeSp("x"), 0.0001f)
        assertEquals(26f * 0.68f, baseTextSizeSp("x".repeat(14)), 0.0001f)
        assertEquals(24f * 0.68f, baseTextSizeSp("x".repeat(22)), 0.0001f)
        assertEquals(23f * 0.68f, baseTextSizeSp("x".repeat(30)), 0.0001f)
    }

    @Test
    fun sizeModeMultipliersMatchSpec() {
        assertEquals(0.9f, textSizeModeMultiplier("small", 100), 0.0001f)
        assertEquals(1f, textSizeModeMultiplier("normal", 100), 0.0001f)
        assertEquals(1.2f, textSizeModeMultiplier("large", 100), 0.0001f)
        assertEquals(1.5f, textSizeModeMultiplier("xlarge", 100), 0.0001f)
        assertEquals(0f, textSizeModeMultiplier("custom", -10), 0.0001f)
        assertEquals(5f, textSizeModeMultiplier("custom", 900), 0.0001f)
    }

    @Test
    fun legacyScrollModeMapsToWrap() {
        assertEquals("Wrap", normalizeAodOverflow("Scroll with lyric"))
        assertEquals("Wrap", normalizeAodOverflow("auto"))
        assertEquals("Clip", normalizeAodOverflow("Clip"))
    }

    @Test
    fun transportedRangesArePrimaryAndInvalidRangesAreIgnored() {
        val word = AodCanvasWord("重複", "", 0L, 1L, false, 3, 5)
        assertEquals(3 until 5, transportedWordOffset("重複 重複", word))
        assertNull(transportedWordOffset("重複", word))
    }

    @Test
    fun currentWordControlsFollowingGap() {
        assertEquals(0f, aodWordGapAfter(false, 8f), 0.0001f)
        assertEquals(8f, aodWordGapAfter(true, 8f), 0.0001f)
    }

    @Test
    fun adaptiveOffNeverWrapsBetweenAttachedWordFragments() {
        val words = listOf(
            AodCanvasWord("hello", "", 0L, 100L, true),
            AodCanvasWord("phra", "", 100L, 200L, false),
            AodCanvasWord("se", "", 200L, 300L, false)
        )

        assertEquals(listOf(0 until 1, 1 until 3), attachedWordRanges(words))
        assertEquals(
            listOf(0 until 1, 1 until 3),
            legacyAttachedWordLineRanges(
                words = words,
                wordWidths = listOf(50f, 40f, 40f),
                gapAfters = listOf(5f, 0f, 0f),
                available = 100f,
                maxLines = 3
            )
        )
    }

    @Test
    fun camouflageFragmentsUseTrailingEdgeBoundaries() {
        val words = listOf(
            AodCanvasWord("My", "My", 0L, 100L, true),
            AodCanvasWord("Camoufla", "Camoufla", 100L, 200L, false),
            AodCanvasWord("ge", "ge", 200L, 300L, false)
        )

        assertEquals(listOf(0 until 1, 1 until 3), attachedWordRanges(words))
        assertEquals("My Camouflage", joinedRomanizedWords(words.map { it.romanized to it.boundaryAfter }))
    }

    @Test
    fun serializedOffsetsPreserveAuthoredJapaneseAndSpaceSeparators() {
        val adjacent = authoredWordSeparator(
            "朝か昼か",
            AodCanvasWord("朝か", "", 0L, 1L, false, 0, 2),
            AodCanvasWord("昼か", "", 1L, 2L, false, 2, 4)
        )
        val spaced = authoredWordSeparator(
            "day night",
            AodCanvasWord("day", "", 0L, 1L, true, 0, 3),
            AodCanvasWord("night", "", 1L, 2L, false, 4, 9)
        )

        assertEquals("", adjacent)
        assertEquals(" ", spaced)
    }

    @Test
    fun rubyCrossingTwoRangesCoalescesWithoutSyntheticBoundaryGap() {
        val words = coalesceRubyWords(
            "甲乙",
            listOf(
                AodCanvasWord("甲", "ka", 0L, 100L, false, 0, 1),
                AodCanvasWord("乙", "otsu", 100L, 200L, false, 1, 2)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertEquals(1, words.size)
        assertEquals("甲乙", words[0].text)
        assertEquals(0, words[0].sourceStart)
        assertEquals(2, words[0].sourceEnd)
    }

    @Test
    fun coalescedRubyWordKeepsFinalTokenBoundaryGap() {
        val words = coalesceRubyWords(
            "甲乙 丙",
            listOf(
                AodCanvasWord("甲", "ka", 0L, 100L, false, 0, 1),
                AodCanvasWord("乙", "otsu", 100L, 200L, true, 1, 2),
                AodCanvasWord("丙", "hei", 200L, 300L, false, 3, 4)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertTrue(words[0].boundaryAfter)
        assertEquals(8f, aodWordGapAfter(words[0].boundaryAfter, 8f), 0.0001f)
    }

    @Test
    fun invalidTransportedRangesDowngradeRubyOwnershipSafely() {
        val words = coalesceRubyWords(
            "甲乙",
            listOf(
                AodCanvasWord("甲", "", 0L, 100L, false, -1, -1),
                AodCanvasWord("乙", "", 100L, 200L, false, 1, 3)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertEquals(2, words.size)
    }

    @Test
    fun metadataBottomReservesSpaceAndStaysAtCanvasBottom() {
        val bounds = metadataLayoutBounds("bottom", 360f, 8f, 8f, -12f, 4f, 10f)
        assertEquals(348f, bounds.metadataBaseline, 0.0001f)
        assertEquals(326f, bounds.lyricEnd, 0.0001f)
    }

    @Test
    fun rubyReservationAndRowStackScaleWithNormalAndXlargeSizes() {
        val normalBase = baseTextSizeSp("x")
        val xlargeBase = normalBase * textSizeModeMultiplier("xlarge", 100)
        val normalRuby = rubyReservation(normalBase, -normalBase * 0.46f)
        val xlargeRuby = rubyReservation(xlargeBase, -xlargeBase * 0.46f)
        assertEquals(normalBase * 0.58f, normalRuby, 0.0001f)
        assertEquals(xlargeBase * 0.58f, xlargeRuby, 0.0001f)
        assertEquals(80f + normalRuby * 2f, originalRowHeight(40f, 2, normalRuby * 2f), 0.0001f)
        assertEquals(40f + xlargeRuby * 2f, originalLineBaseline(0f, 1, 40f, xlargeRuby, xlargeRuby), 0.0001f)
        assertEquals(
            40f + xlargeRuby * 2f + 4f,
            originalLineBaseline(0f, 1, 40f, xlargeRuby, xlargeRuby, 4f),
            0.0001f
        )
        assertEquals(84f + normalRuby * 2f, originalRowHeight(40f, 2, normalRuby * 2f, 4f), 0.0001f)
    }

    @Test
    fun endAlignmentReservesVisualOverhangAndAnimationSafety() {
        assertEquals(
            76f,
            edgeSafeAlignedStart(
                canvasWidth = 200f,
                paddingLeft = 10f,
                paddingRight = 10f,
                visualLeft = 0f,
                visualRight = 110f,
                alignment = "end",
                safetyInset = 4f
            ),
            0.0001f
        )
    }

    @Test
    fun secondaryLineHeightReservesTypefaceBottomOvershoot() {
        assertEquals(26f, safeSecondaryLineHeight(-16f, 6f, 10f), 0.0001f)
    }

    @Test
    fun spotlightBrightnessUsesSinOutSquaredRamp() {
        assertEquals(0.42f, spotlightBrightness(0f), 0.0001f)
        assertEquals(0.71f, spotlightBrightness(0.5f), 0.0001f)
        assertEquals(1f, spotlightBrightness(1f), 0.0001f)
    }

    @Test
    fun spotlightAlphaKeepsActiveWordAboveUnsungFloor() {
        assertEquals(0.56f, spotlightAlpha(0f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(0.71f, spotlightAlpha(0.5f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(1f, spotlightAlpha(1f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(1f, spotlightAlpha(0.25f, SpotlightWordState.SUNG), 0.0001f)
        assertEquals(0.56f, spotlightAlpha(0.75f, SpotlightWordState.UNSUNG), 0.0001f)
    }

    @Test
    fun rubyClipStartsAboveBaseGlyphByReservedBand() {
        assertEquals(40f, rubyClipTop(100f, -40f, 20f), 0.0001f)
    }

    @Test
    fun rubyTopShiftClampsEntireBlockToCanvasPadding() {
        assertEquals(8f, rubyTopShift(4f, 12f), 0.0001f)
        assertEquals(0f, rubyTopShift(12f, 12f), 0.0001f)
        assertEquals(0f, rubyTopShift(20f, 12f), 0.0001f)
    }

    @Test
    fun oldBatteryMotionNormalizesToFluid() {
        assertEquals("Fluid", normalizeAodMotion("Battery"))
        assertEquals("Fluid", normalizeAodMotion("Fluid"))
    }

    @Test
    fun timingLoopIsAlwaysSixteenMsWhileEffectivelyVisibleAuthoritativeAndTimed() {
        val active = EffectiveCadenceInputs(
            attached = true,
            sceneActive = true,
            ownVisible = true,
            windowVisible = true,
            aggregatedVisible = true,
            effectiveAlpha = 1f,
            timedOrTransitionActive = true
        )
        assertTrue(isEffectiveCadenceActive(active))
        assertEquals(16L, frameIntervalForTiming(isEffectiveCadenceActive(active), true))
        assertFalse(isEffectiveCadenceActive(active.copy(sceneActive = false)))
        assertFalse(isEffectiveCadenceActive(active.copy(windowVisible = false)))
        assertFalse(isEffectiveCadenceActive(active.copy(aggregatedVisible = false)))
        assertFalse(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0.01f)))
        assertTrue(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0f, handoffActive = true)))
        assertFalse(
            isEffectiveCadenceActive(
                active.copy(effectiveAlpha = 0f, handoffActive = true, sceneActive = false)
            )
        )
        assertFalse(isEffectiveCadenceActive(active.copy(attached = false)))
        assertEquals(0L, frameIntervalForTiming(false, true))
        assertEquals(0L, frameIntervalForTiming(true, false))
    }

    @Test
    fun verifiedDozeCadenceIgnoresXiaomiGenericVisibilityButKeepsDirectGates() {
        val doze = EffectiveCadenceInputs(
            attached = true,
            sceneActive = true,
            ownVisible = true,
            windowVisible = false,
            aggregatedVisible = false,
            effectiveAlpha = 0f,
            timedOrTransitionActive = true,
            verifiedDozeHost = true
        )

        assertTrue(isEffectiveCadenceActive(doze))
        assertFalse(isEffectiveCadenceActive(doze.copy(attached = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(sceneActive = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(ownVisible = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(timedOrTransitionActive = false)))
    }

    @Test
    fun wordTimingDrivesCadenceWithoutLineBoundsAndNoneDisablesLineSweep() {
        val words = listOf(AodCanvasWord("word", "", 1_000L, 2_000L, false))

        assertTrue(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, words))
        assertTrue(hasActiveCanvasTiming(true, "Top to bottom", 1_000L, 2_000L, emptyList()))
        assertFalse(hasActiveCanvasTiming(true, "None", 1_000L, 2_000L, words))
        assertFalse(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, emptyList()))
        assertFalse(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, words, speed = 0f))
    }

    @Test
    fun cadenceGateStopsWhenHiddenAndRestartsWhenVisibilityReturns() {
        val gate = EffectiveCadenceGate()

        assertEquals(CadenceChange.START, gate.update(true))
        assertEquals(CadenceChange.STOP, gate.update(false))
        assertEquals(CadenceChange.START, gate.update(true))
        assertEquals(CadenceChange.NONE, gate.update(true))
    }

    @Test
    fun drawWakePulseResultLogsOnlyOnOutcomeChange() {
        assertTrue(shouldLogDrawWakePulseResult(null, AodDrawWakePulseResult.SUCCESS))
        assertFalse(
            shouldLogDrawWakePulseResult(
                AodDrawWakePulseResult.SUCCESS,
                AodDrawWakePulseResult.SUCCESS
            )
        )
        assertTrue(
            shouldLogDrawWakePulseResult(
                AodDrawWakePulseResult.SUCCESS,
                AodDrawWakePulseResult.INVOCATION_FAILED
            )
        )
    }

    @Test
    fun exitTransitionDrivesFramesForUntimedIncomingUntilSettled() {
        assertEquals(16L, frameIntervalForTiming(true, false, true))
        assertEquals(0L, frameIntervalForTiming(true, false, false))
        assertFalse(isExitTransitionExpired(1_000L, 1_209L, 210L))
        assertTrue(isExitTransitionExpired(1_000L, 1_210L, 210L))
    }

    @Test
    fun handoffSuppressesDuplicateLineTransition() {
        assertEquals(true, shouldStartLineTransition(true, "Fade up", false))
        assertEquals(false, shouldStartLineTransition(true, "Fade up", true))
        assertEquals(false, shouldStartLineTransition(true, "Fade up", false, resuming = true))
        assertEquals(false, shouldStartLineTransition(true, "None", false))
    }

    @Test
    fun adaptiveCardBoundsCoverIncomingAndOutgoingRowsOnly() {
        assertEquals(
            AodCanvasVerticalBounds(80f, 260f),
            unionAodCanvasVerticalBounds(
                AodCanvasVerticalBounds(100f, 220f),
                AodCanvasVerticalBounds(80f, 260f)
            )
        )
        assertEquals(
            AodCanvasVerticalBounds(100f, 220f),
            unionAodCanvasVerticalBounds(AodCanvasVerticalBounds(100f, 220f), null)
        )
    }

    @Test
    fun lexicalRangeKeepsJapaneseParticleWithPreviousTimedWord() {
        val offsets = listOf(4 until 6, 6 until 7, 7 until 9)
        val groups = listOf(
            AodCanvasLayoutGroup(4, 7, "ja-lexeme", true, 0.95),
            AodCanvasLayoutGroup(7, 12, "ja-lexeme", true, 0.95)
        )

        assertEquals(listOf(0, 0, 1), lexicalGroupIds(offsets, groups))
    }

    @Test
    fun missingLayoutMetadataKeepsLegacyWordWrapping() {
        assertEquals(listOf(null, null), lexicalGroupIds(listOf(0 until 2, 3 until 5), emptyList()))
    }

    @Test
    fun sentenceLayoutKeepsUncoveredPunctuationAndSkipsWhitespaceRuns() {
        val groups = listOf(
            AodCanvasLayoutGroup(0, 2, "zh-icu-word", true, 0.9),
            AodCanvasLayoutGroup(4, 6, "zh-icu-word", true, 0.9)
        )

        assertEquals(
            listOf(0 until 2, 2 until 3, 4 until 6),
            coveredLayoutRanges("音乐， 响起", groups)
        )
    }

    @Test
    fun lexicalChunksBalanceAcrossRequiredLineCount() {
        assertEquals(
            listOf(0 until 2, 2 until 4),
            balancedChunkRanges(listOf(40f, 40f, 40f, 40f), 120f, 3)
        )
    }

    @Test
    fun oversizedLexicalChunkCanEmergencyWrapBeforeBalancing() {
        assertEquals(
            listOf(0 until 1, 1 until 2),
            balancedChunkRanges(listOf(140f, 40f), 120f, 3)
        )
    }

    @Test
    fun legacyWrappingUsesUpstreamGreedyBreaksInsteadOfBalancing() {
        assertEquals(
            listOf(0 until 3, 3 until 4),
            legacyWordLineRanges(
                wordWidths = listOf(40f, 40f, 40f, 40f),
                gapAfters = listOf(0f, 0f, 0f, 0f),
                available = 120f,
                maxLines = 3
            )
        )
    }

    @Test
    fun legacyWrappingLeavesOverflowInTheLastUpstreamCappedLine() {
        assertEquals(
            listOf(0 until 1, 1 until 3),
            legacyWordLineRanges(
                wordWidths = listOf(80f, 80f, 80f),
                gapAfters = listOf(0f, 0f, 0f),
                available = 100f,
                maxLines = 2
            )
        )
    }

    @Test
    fun overlayRectCentersSurfaceWithoutTouchingStockMeasurement() {
        assertEquals(
            AodSurfaceRect(60, 224, 940, 584),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 360)
        )
    }

    @Test
    fun overlayRectShrinksWhenStockContentLeavesLimitedHeight() {
        assertEquals(
            AodSurfaceRect(60, 624, 940, 676),
            calculateAodSurfaceRect(1000, 700, 600, 24, 880, 360)
        )
    }

    @Test
    fun overlayRectNeverExtendsBeyondVisibleRoot() {
        assertEquals(
            AodSurfaceRect(0, 676, 1000, 676),
            calculateAodSurfaceRect(1000, 700, 900, 24, 1200, 360)
        )
    }

    @Test
    fun zeroSizedAodRootIsNeverUsableForRendering() {
        assertEquals(false, hasUsableAodRootSize(0, 700))
        assertEquals(false, hasUsableAodRootSize(1_000, 0))
        assertEquals(false, hasUsableAodRootSize(-1, 700))
        assertEquals(true, hasUsableAodRootSize(1_000, 700))
    }

    @Test
    fun pinyinTokensNeverSplitAtNormalBoundaries() {
        val source = secondaryTokens("tiān tiān bǎ tā guà zuǐ biān dào dǐ shén mó shì zhēn ài")
        val lines = balancedTokenLineTexts(
            source,
            listOf(20f, 20f, 14f, 12f, 20f, 22f, 24f, 20f, 18f, 22f, 20f, 18f, 24f, 12f),
            4f,
            190f,
            2
        )
        assertEquals(source, lines.flatMap(::secondaryTokens))
        assertEquals(true, lines.any { it.contains("zhēn ài") })
    }

    @Test
    fun romanizedWordsRespectAttachedMainRuns() {
        assertEquals(
            "watashi tachi no tsuzuki",
            joinedRomanizedWords(
                listOf(
                    "watashi" to true,
                    "tachi" to true,
                    "no" to true,
                    "tsuzuki" to false
                )
            )
        )
        assertEquals("deshou", joinedRomanizedWords(listOf("desho" to false, "u" to true)))
    }

    @Test
    fun russianSecondaryWrapKeepsWholeWords() {
        val tokens = secondaryTokens("Tut bez tebya, bez tebya vsyo ne tak, vsyo ne tak")
        val lines = balancedTokenLineTexts(tokens, tokens.map { it.length * 8f }, 4f, 190f, 2)
        assertEquals(tokens, lines.flatMap(::secondaryTokens))
    }
}
