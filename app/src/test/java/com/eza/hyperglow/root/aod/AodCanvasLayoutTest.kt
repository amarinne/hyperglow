package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSecondLine
import com.eza.hyperglow.root.projection.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodCanvasLayoutTest {
    @Test
    fun metadataSeparatorsProduceTrimmedRowsWithoutEmptyLines() {
        assertEquals(listOf("Song", "Artist"), metadataLineTexts("Song · Artist"))
        assertEquals(listOf("Song", "Mix", "Artist"), metadataLineTexts("Song · Mix · Artist"))
        assertEquals(listOf("Song", "Artist"), metadataLineTexts("Song ·\n Artist"))
        assertEquals(listOf("Song"), metadataLineTexts("Song"))
    }

    @Test
    fun metadataBoundsReserveBothRowsAtEitherAnchor() {
        val top = metadataLayoutBounds("top", 200f, 10f, 10f, -12f, 4f, 8f, 20f)
        assertEquals(54f, top.lyricStart, 0.01f)
        val bottom = metadataLayoutBounds("bottom", 200f, 10f, 10f, -12f, 4f, 8f, 20f)
        assertEquals(166f, bottom.metadataBaseline, 0.01f)
        assertEquals(146f, bottom.lyricEnd, 0.01f)
    }

    @Test
    fun standalonePunctuationAttachesWithoutProducerGroups() {
        fun groups(vararg texts: String) = attachAodPunctuationGroups(
            texts.map { AodCanvasWord(it, "", 0L, 0L, true) }, List(texts.size) { null })
        val closed = groups("hello", ",", "world")
        assertEquals(closed[0], closed[1])
        assertTrue(closed[1] != closed[2])
        val opened = groups("hello", "(", "world", ")")
        assertEquals(opened[1], opened[2])
        assertEquals(opened[2], opened[3])
        val quoted = groups("\"", "hello", "\"", "world")
        assertEquals(quoted[0], quoted[1])
        assertEquals(quoted[1], quoted[2])
        assertTrue(quoted[2] != quoted[3])
    }

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
    fun semanticPaletteSupportsPresetsAndIndependentMetadata() {
        val lavender = resolveAodPalette(
            mapOf("primaryText" to "lavender", "metadataText" to "#9998A4")
        )
        assertEquals(lavender.primaryText, lavender.sungText)
        assertEquals(lavender.primaryText, lavender.unsungText)
        assertNotEquals(lavender.primaryText, lavender.metadataText)

        val mint = resolveAodPalette(
            mapOf("primaryText" to "mint", "sungText" to "#62D891", "glow" to "#B9A8FF")
        )
        assertEquals(0xFF62D891.toInt(), mint.sungText)
        assertEquals(0xFFB9A8FF.toInt(), mint.glow)
        assertNotEquals(lavender.primaryText, mint.primaryText)
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
        val first = AodLineTransitionKey(7L, 1_000L, null)

        assertEquals(first, AodLineTransitionKey(7L, 1_000L, null))
        assertNotEquals(first, AodLineTransitionKey(7L, 3_000L, null))
        assertNotEquals(first, AodLineTransitionKey(8L, 1_000L, null))
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
    fun rtlDirectionUsesFirstStrongTextAndTimedWordFallback() {
        assertEquals(AodTextDirection.RTL, resolvedAodTextDirection("... ۱۲۳ عايزة"))
        assertEquals(AodTextDirection.RTL, resolvedAodTextDirection("שיר בעברית"))
        assertEquals(AodTextDirection.LTR, resolvedAodTextDirection("English ثم عربي"))
        assertEquals(
            AodTextDirection.RTL,
            resolvedAodTextDirection(
                "(۱۲۳)",
                listOf(AodCanvasWord("عايزة", "", 0L, 1L, true))
            )
        )
    }

    @Test
    fun rtlAutoAlignmentAndTimedWordsStartAtLogicalRightEdge() {
        assertEquals(
            "end",
            resolvedAodPhysicalAlignment("auto", false, AodTextDirection.RTL)
        )
        assertEquals(
            "start",
            resolvedAodPhysicalAlignment("auto", true, AodTextDirection.RTL)
        )
        assertEquals(
            170f,
            timedWordDrawX(10f, 200f, 0f, 40f, AodTextDirection.RTL),
            0.0001f
        )
        assertEquals(
            120f,
            timedWordDrawX(10f, 200f, 50f, 40f, AodTextDirection.RTL),
            0.0001f
        )
        assertEquals(
            60f,
            timedWordDrawX(10f, 200f, 50f, 40f, AodTextDirection.LTR),
            0.0001f
        )
    }

    @Test
    fun rtlGradientSweepMirrorsTheHorizontalReadingDirection() {
        assertEquals(
            GradientSweepZone(100f, 140f),
            gradientSweepZone(0f, 100f, direction = AodTextDirection.RTL)
        )
        assertEquals(
            GradientSweepZone(30f, 70f),
            gradientSweepZone(0.5f, 100f, direction = AodTextDirection.RTL)
        )
        assertEquals(
            GradientSweepZone(-40f, 0f),
            gradientSweepZone(1f, 100f, direction = AodTextDirection.RTL)
        )
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
    fun lexicalChunkPackingAccountsForRenderedSeparators() {
        // Two 45px chunks with a 15px separator exceed a 100px drawable area.
        assertEquals(
            listOf(0 until 1, 1 until 2),
            balancedChunkRanges(listOf(60f, 45f), 100f, 2)
        )
    }

    @Test
    fun punctuationUsesWritingSystemAttachmentClasses() {
        assertTrue(aodPunctuationAttachToPrevious('.'.code))
        assertTrue(aodPunctuationAttachToPrevious('。'.code))
        assertTrue(aodPunctuationAttachToNext('('.code))
        assertTrue(aodPunctuationAttachToNext('「'.code))
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
    fun landscapeCanvasUsesLongAndShortAxesFromAnyRootOrientation() {
        assertEquals(AodCanvasSize(2670, 1200), aodLandscapeCanvasSize(1200, 2670))
        assertEquals(AodCanvasSize(2670, 1200), aodLandscapeCanvasSize(2670, 1200))
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

    @Test
    fun lockscreenMappingStaysSoloWhileAodKeepsOverlap() {
        val snapshot = LyricSnapshot(
            original = "lead",
            secondLine = LyricSecondLine(text = "background")
        )

        assertEquals("background", snapshot.toAodCanvasContent().secondLine?.text)
        assertNull(
            snapshot.toAodCanvasContent(includeSecondLine = false).secondLine
        )
    }

    @Test
    fun lineTransitionKeyIgnoresLaneRevisions() {
        val base = LyricSnapshot(
            trackGeneration = 7L,
            original = "lead",
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            secondLine = LyricSecondLine(text = "bg", lineStartMs = 2000L, lineEndMs = 6000L)
        ).toAodCanvasContent()
        val revised = LyricSnapshot(
            trackGeneration = 7L,
            original = "lead revised",
            lineStartMs = 1000L,
            lineEndMs = 5500L,
            secondLine = LyricSecondLine(text = "bg revised", lineStartMs = 2000L, lineEndMs = 6200L)
        ).toAodCanvasContent()

        assertEquals(aodLineTransitionKey(base), aodLineTransitionKey(revised))
    }

    @Test
    fun heartbeatRepublishIsLayoutEquivalent() {
        val base = LyricSnapshot(
            trackGeneration = 7L,
            original = "lead",
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            positionMs = 1500L,
            sampledAtElapsedMs = 100L,
            words = listOf(LyricWord("lead", "", 1000L, 5000L, true)),
            secondLine = LyricSecondLine(text = "bg", lineStartMs = 2000L, lineEndMs = 6000L)
        ).toAodCanvasContent()
        val heartbeat = base.copy(positionMs = 3000L, sampledAtElapsedMs = 2100L, speed = 1f)

        assertTrue(layoutEquivalent(base, heartbeat))
        assertFalse(layoutEquivalent(base, heartbeat.copy(original = "changed")))
        assertFalse(
            layoutEquivalent(
                base,
                heartbeat.copy(
                    words = listOf(
                        AodCanvasWord("lead", "", 1000L, 4500L, true, 0, 4)
                    )
                )
            )
        )
        assertFalse(layoutEquivalent(base, heartbeat.copy(lineEndMs = 5500L)))
        assertFalse(
            layoutEquivalent(
                base,
                heartbeat.copy(secondLine = heartbeat.secondLine?.copy(text = "bg revised"))
            )
        )
        assertFalse(layoutEquivalent(base, heartbeat.copy(secondLine = null)))
    }

    @Test
    fun lineTransitionKeyFiresOnStructuralChanges() {
        val solo = LyricSnapshot(
            trackGeneration = 7L,
            original = "lead",
            lineStartMs = 1000L,
            lineEndMs = 5000L
        ).toAodCanvasContent()
        val duet = solo.copy(
            secondLine = AodCanvasSecondLine(text = "bg", lineStartMs = 2000L, lineEndMs = 6000L)
        )
        val replaced = solo.copy(
            secondLine = AodCanvasSecondLine(text = "bg2", lineStartMs = 3000L, lineEndMs = 7000L)
        )
        val nextTrack = solo.copy(trackGeneration = 8L)
        val nextLine = solo.copy(lineStartMs = 5000L, lineEndMs = 9000L)

        assertEquals(false, aodLineTransitionKey(solo) == aodLineTransitionKey(duet))
        assertEquals(false, aodLineTransitionKey(duet) == aodLineTransitionKey(solo))
        assertEquals(false, aodLineTransitionKey(duet) == aodLineTransitionKey(replaced))
        assertEquals(false, aodLineTransitionKey(solo) == aodLineTransitionKey(nextTrack))
        assertEquals(false, aodLineTransitionKey(solo) == aodLineTransitionKey(nextLine))
    }

    @Test
    fun soloSceneNeverDefers() {
        assertEquals(emptySet<Int>(), deferredDuetBlockIndices(listOf(1000L), 500L))
        assertEquals(emptySet<Int>(), deferredDuetBlockIndices(emptyList(), 500L))
    }

    @Test
    fun futureSectionDefersUntilItsWindowStarts() {
        assertEquals(setOf(1), deferredDuetBlockIndices(listOf(1000L, 4000L), 2000L))
        assertEquals(emptySet<Int>(), deferredDuetBlockIndices(listOf(1000L, 4000L), 4000L))
        assertEquals(emptySet<Int>(), deferredDuetBlockIndices(listOf(1000L, 4000L), 5000L))
    }

    @Test
    fun sharedDuetScaleFitsTheCombinedStack() {
        // Both stacks together fit the area: nobody shrinks.
        assertEquals(
            1f,
            resolveOverflowShrinkScale(400f + 300f, 1000f),
            0.0001f
        )
        // Combined overflow: one shared scale for both sections.
        assertEquals(
            0.8f,
            resolveOverflowShrinkScale(500f + 750f, 1000f),
            0.0001f
        )
        assertEquals(
            MIN_OVERFLOW_SHRINK_SCALE,
            resolveOverflowShrinkScale(4000f, 500f),
            0.0001f
        )
    }

    @Test
    fun sharedDuetScaleNeverFloorsAboveExactFit() {
        // Fits: full size.
        assertEquals(1f, resolveSharedDuetScale(700f, 1000f), 0.0001f)
        // Exact ratio, not the solo 0.5 floor — a floored overflow clips
        // a section's bottom rows mid-draw.
        assertEquals(0.45f, resolveSharedDuetScale(2000f, 900f), 0.0001f)
        // Deep absolute floor.
        assertEquals(
            MIN_SHARED_DUET_SCALE,
            resolveSharedDuetScale(10000f, 1000f),
            0.0001f
        )
        // Unusable inputs keep full size.
        assertEquals(1f, resolveSharedDuetScale(0f, 1000f), 0.0001f)
        assertEquals(1f, resolveSharedDuetScale(700f, 0f), 0.0001f)
        assertEquals(1f, resolveSharedDuetScale(Float.NaN, 1000f), 0.0001f)
    }

    @Test
    fun majorityRangeOwnsResegmentedWords() {
        val ranges = listOf(0..4, 5..10)
        assertEquals(0, majorityRangeIndex(ranges, 0..4))
        assertEquals(1, majorityRangeIndex(ranges, 5..10))
        assertEquals(0, majorityRangeIndex(ranges, 0..7))
        assertEquals(1, majorityRangeIndex(ranges, 3..10))
        assertEquals(0, majorityRangeIndex(ranges, 0..9))
    }

    @Test
    fun frozenWrapNeedsExactTextCoverage() {
        val text = "hello world"
        val full = FrozenLineWrap(text.length, listOf(0..4, 5..10))
        assertEquals(listOf(0..4, 5..10), validFrozenWrap(full, text))
        assertEquals(listOf(0..4, 5..10), validFrozenWrap(full, "hella warld"))
        assertNull(validFrozenWrap(full, "hello worlds"))
        assertNull(validFrozenWrap(FrozenLineWrap(text.length, listOf(0..4)), text))
        assertNull(validFrozenWrap(FrozenLineWrap(text.length, listOf(0..5, 5..10)), text))
        assertNull(validFrozenWrap(FrozenLineWrap(text.length, listOf(0..4, 6..20)), text))
        assertNull(validFrozenWrap(FrozenLineWrap(text.length, emptyList()), text))
        assertNull(validFrozenWrap(null, text))
    }

    @Test
    fun frozenRangesDeriveFromLaidOutLines() {
        assertEquals(
            FrozenLineWrap(11, listOf(0..4, 5..10)),
            frozenRangesFrom(listOf(0 to 5, 5 to 11), "hello world")
        )
        assertNull(frozenRangesFrom(listOf(0 to 5, null to null), "hello world"))
        assertNull(frozenRangesFrom(listOf(0 to 0, 0 to 11), "hello world"))
        assertNull(frozenRangesFrom(emptyList(), "hello world"))
        assertNull(frozenRangesFrom(listOf(0 to 5), ""))
    }

    @Test
    fun fittingStackKeepsFullSize() {
        assertEquals(1f, resolveOverflowShrinkScale(800f, 1000f), 0.0001f)
        assertEquals(1f, resolveOverflowShrinkScale(1000f, 1000f), 0.0001f)
    }

    @Test
    fun overflowingStackShrinksToExactFit() {
        assertEquals(0.75f, resolveOverflowShrinkScale(1000f, 750f), 0.0001f)
    }

    @Test
    fun shrinkNeverDropsBelowReadableFloor() {
        assertEquals(
            MIN_OVERFLOW_SHRINK_SCALE,
            resolveOverflowShrinkScale(4000f, 1000f),
            0.0001f
        )
    }

    @Test
    fun unusableGeometryKeepsFullSize() {
        assertEquals(1f, resolveOverflowShrinkScale(0f, 1000f), 0.0001f)
        assertEquals(1f, resolveOverflowShrinkScale(1200f, 0f), 0.0001f)
        assertEquals(1f, resolveOverflowShrinkScale(Float.NaN, 1000f), 0.0001f)
        assertEquals(1f, resolveOverflowShrinkScale(1200f, Float.NaN), 0.0001f)
    }

    @Test
    fun duetJoinKeepsSurvivorSlotAndAppendsNewcomer() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        assertEquals(listOf(first, second), assignDuetSlots(listOf(first, second), listOf(first)))
    }

    @Test
    fun duetReplacementTakesVacatedSlotInsteadOfAppending() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        val third = DuetSectionId(7L, 6000L, 9000L)
        assertEquals(
            listOf(third, second),
            assignDuetSlots(listOf(second, third), listOf(first, second))
        )
    }

    @Test
    fun duetRoleFlipKeepsBothSlots() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        assertEquals(
            listOf(first, second),
            assignDuetSlots(listOf(second, first), listOf(first, second))
        )
    }

    @Test
    fun duetFullSwapKeepsCurrentOrder() {
        val third = DuetSectionId(7L, 6000L, 9000L)
        val fourth = DuetSectionId(7L, 8000L, 12_000L)
        assertEquals(
            listOf(third, fourth),
            assignDuetSlots(
                listOf(third, fourth),
                listOf(DuetSectionId(7L, 1000L, 5000L), DuetSectionId(7L, 3000L, 7000L))
            )
        )
    }

    @Test
    fun duetSoloPassesThrough() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        assertEquals(listOf(first), assignDuetSlots(listOf(first), emptyList()))
        assertEquals(emptyList<DuetSectionId>(), assignDuetSlots(emptyList(), listOf(first)))
    }

    @Test
    fun duetEndRecentersTheRemainingSolo() {
        assertTrue(shouldRecenterAfterDuet(wasDuet = true, sectionCount = 1))
        assertFalse(shouldRecenterAfterDuet(wasDuet = true, sectionCount = 2))
        assertFalse(shouldRecenterAfterDuet(wasDuet = false, sectionCount = 1))
    }

    @Test
    fun duetSurvivorKeepsTopWhileNewcomerStacksBelow() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        val tops = placeDuetSectionTops(
            listOf(first, second),
            mapOf(first to 200f, second to 100f),
            areaCenter = 500f,
            lastTops = mapOf(first to 400f),
            lastBlockCenter = 500f
        )

        assertEquals(400f, tops.getValue(first), 0.0001f)
        assertEquals(600f, tops.getValue(second), 0.0001f)
    }

    @Test
    fun duetNewcomerTakesExpiredTopSlotAboveSurvivor() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        val third = DuetSectionId(7L, 6000L, 9000L)
        val tops = placeDuetSectionTops(
            listOf(third, second),
            mapOf(third to 150f, second to 100f),
            areaCenter = 500f,
            lastTops = mapOf(second to 600f),
            lastBlockCenter = 500f
        )

        assertEquals(600f, tops.getValue(second), 0.0001f)
        assertEquals(450f, tops.getValue(third), 0.0001f)
    }

    @Test
    fun duetRoleFlipKeepsBothSectionsStill() {
        val first = DuetSectionId(7L, 1000L, 5000L)
        val second = DuetSectionId(7L, 3000L, 7000L)
        val tops = placeDuetSectionTops(
            listOf(second, first),
            mapOf(first to 200f, second to 100f),
            areaCenter = 500f,
            lastTops = mapOf(first to 400f, second to 600f),
            lastBlockCenter = 500f
        )

        assertEquals(400f, tops.getValue(first), 0.0001f)
        assertEquals(600f, tops.getValue(second), 0.0001f)
    }

    @Test
    fun duetFullSwapCentersOnLastBlockCenter() {
        val third = DuetSectionId(7L, 6000L, 9000L)
        val fourth = DuetSectionId(7L, 8000L, 12_000L)
        val tops = placeDuetSectionTops(
            listOf(third, fourth),
            mapOf(third to 150f, fourth to 100f),
            areaCenter = 500f,
            lastTops = emptyMap(),
            lastBlockCenter = 520f
        )

        assertEquals(395f, tops.getValue(third), 0.0001f)
        assertEquals(545f, tops.getValue(fourth), 0.0001f)
    }

    @Test
    fun duetChainSoloStaysPut() {
        val second = DuetSectionId(7L, 3000L, 7000L)
        val tops = placeDuetSectionTops(
            listOf(second),
            mapOf(second to 100f),
            areaCenter = 500f,
            lastTops = mapOf(second to 600f),
            lastBlockCenter = 500f
        )

        assertEquals(600f, tops.getValue(second), 0.0001f)
    }

    @Test
    fun fittingBlockNeverClamps() {
        assertEquals(0f, resolveBlockClampShift(400f, 800f, 0f, 1000f), 0.0001f)
    }

    @Test
    fun topOverhangRepinsToAreaTop() {
        assertEquals(200f, resolveBlockClampShift(200f, 900f, 400f, 1200f), 0.0001f)
    }

    @Test
    fun fittingBottomOverhangShiftsUp() {
        assertEquals(-100f, resolveBlockClampShift(900f, 1300f, 400f, 1200f), 0.0001f)
    }

    @Test
    fun oversizedBlockPinsToTopAsFailSafe() {
        assertEquals(200f, resolveBlockClampShift(200f, 1500f, 400f, 1200f), 0.0001f)
    }

    @Test
    fun overfullBlockStartingInsidePinsTop() {
        assertEquals(-100f, resolveBlockClampShift(500f, 1900f, 400f, 1200f), 0.0001f)
    }

    @Test
    fun unusableClampGeometryHoldsStill() {
        assertEquals(0f, resolveBlockClampShift(Float.NaN, 800f, 0f, 1000f), 0.0001f)
        assertEquals(0f, resolveBlockClampShift(400f, 800f, 0f, 0f), 0.0001f)
    }

    @Test
    fun secondaryCapAppliesOnlyToAnchoredLandscapeSections() {
        val legacy = AodLyricCanvasView.MAX_SECONDARY_LINES
        assertEquals(1, duetSecondaryLineCap(anchored = true, sideStep = true))
        assertEquals(legacy, duetSecondaryLineCap(anchored = false, sideStep = true))
        assertEquals(legacy, duetSecondaryLineCap(anchored = true, sideStep = false))
        assertEquals(legacy, duetSecondaryLineCap(anchored = false, sideStep = false))
    }

    @Test
    fun minimalLineCountGreedyPacksUnits() {
        assertEquals(1, minimalLineCount(listOf(100f, 100f), 250f))
        assertEquals(2, minimalLineCount(listOf(100f, 100f, 100f), 250f))
        assertEquals(2, minimalLineCount(listOf(150f, 150f), 200f))
        assertEquals(3, minimalLineCount(listOf(150f, 150f, 150f), 200f))
        assertEquals(1, minimalLineCount(emptyList(), 200f))
        assertEquals(2, minimalLineCount(listOf(300f, 50f), 200f))
    }

    @Test
    fun frozenWrapMinimalityRejectsBloatWraps() {
        val ranges = listOf(0..4, 5..9, 10..14)
        // Each frozen line is 100 wide: two fit per line at 250, so a
        // 3-line freeze from a smaller frame is non-minimal.
        assertEquals(
            false,
            frozenWrapIsMinimal(ranges, measureLine = { 100f }, gap = 0f, available = 250f)
        )
        assertEquals(
            true,
            frozenWrapIsMinimal(ranges, measureLine = { 100f }, gap = 0f, available = 150f)
        )
        assertEquals(
            true,
            frozenWrapIsMinimal(listOf(0..9), measureLine = { 100f }, gap = 0f, available = 250f)
        )
        // A frozen wrap where any line exceeds available width must be rejected
        // so it can re-wrap properly instead of clipping past viewport margins.
        assertEquals(
            false,
            frozenWrapIsMinimal(ranges, measureLine = { 100f }, gap = 0f, available = 80f)
        )
        val overflowPair = listOf(0..3, 4..8)
        assertEquals(
            false,
            frozenWrapIsMinimal(
                overflowPair,
                measureLine = { if (it.first == 0) 369f else 477f },
                gap = 8f,
                available = 404f
            )
        )
    }

    @Test
    fun unwrapTriggerKeysOnShrinkWithScaleTolerance() {
        // Device case: wrapped at 0.81, single line fits at full size.
        assertTrue(shouldUnwrapShrunkSection(0.813f, 1.0f))
        // Floor case: wrapped at the floor, single line still better.
        assertTrue(shouldUnwrapShrunkSection(0.5f, 0.48f))
        // Wide line: single line far tinier than the wrap stays wrapped.
        assertFalse(shouldUnwrapShrunkSection(0.9f, 0.4f))
        // Equal scales unwrap (one-line form preferred).
        assertTrue(shouldUnwrapShrunkSection(0.8f, 0.8f))
        // Just outside tolerance stays wrapped.
        assertFalse(shouldUnwrapShrunkSection(1.0f, 0.8f))
        // Unusable inputs never unwrap.
        assertFalse(shouldUnwrapShrunkSection(Float.NaN, 1.0f))
        assertFalse(shouldUnwrapShrunkSection(0.8f, Float.NaN))
    }

    @Test
    fun visualWidthFitBoundsWithoutNaN() {
        assertEquals(1f, resolveVisualWidthFitScale(50f, 100f), 0.0001f)
        assertEquals(0.5f, resolveVisualWidthFitScale(200f, 100f), 0.0001f)
        // Unusable inputs contribute 1f: no NaN or zero transform.
        assertEquals(1f, resolveVisualWidthFitScale(0f, 100f), 0.0001f)
        assertEquals(1f, resolveVisualWidthFitScale(Float.NaN, 100f), 0.0001f)
        assertEquals(1f, resolveVisualWidthFitScale(200f, 0f), 0.0001f)
    }

    @Test
    fun transitionPassesSplitSurvivorFromDeparturesAndArrivals() {
        val a = DuetSectionId(7L, 1000L, 5000L)
        val b = DuetSectionId(7L, 3000L, 7000L)
        val c = DuetSectionId(7L, 6000L, 9000L)

        // Join: solo A -> duet A+B. A continues (no crossfade against
        // itself), B arrives.
        val join = resolveDuetTransitionPasses(listOf(a), listOf(a, b))
        assertEquals(setOf(0), join.continuingEnterBlocks)
        assertEquals(emptySet<Int>(), join.departingExitBlocks)
        assertEquals(setOf(1), join.arrivingEnterBlocks)

        // Chain: A+B -> C+B. B continues, A departs, C arrives.
        val chain = resolveDuetTransitionPasses(listOf(a, b), listOf(c, b))
        assertEquals(setOf(1), chain.continuingEnterBlocks)
        assertEquals(setOf(0), chain.departingExitBlocks)
        assertEquals(setOf(0), chain.arrivingEnterBlocks)

        // Line change: solo A -> solo B. Full crossfade, no survivor.
        val swap = resolveDuetTransitionPasses(listOf(a), listOf(b))
        assertEquals(emptySet<Int>(), swap.continuingEnterBlocks)
        assertEquals(setOf(0), swap.departingExitBlocks)
        assertEquals(setOf(0), swap.arrivingEnterBlocks)
    }

    @Test
    fun presentationChangedContinuerCrossfadesInsteadOfSurvivorOnce() {
        val a = DuetSectionId(7L, 1000L, 5000L)
        val b = DuetSectionId(7L, 3000L, 7000L)
        // Join where the continuing section re-presents (2 wrapped lines ->
        // 1 unwrapped): it must crossfade, not draw survivor-once.
        val rePresented = resolveDuetTransitionPasses(
            listOf(a), listOf(a, b),
            exitLineCounts = listOf(2), enterLineCounts = listOf(1, 2)
        )
        assertEquals(emptySet<Int>(), rePresented.continuingEnterBlocks)
        assertEquals(setOf(0), rePresented.departingExitBlocks)
        assertEquals(setOf(0, 1), rePresented.arrivingEnterBlocks)
        // Same presentation keeps the survivor-once fast path.
        val stable = resolveDuetTransitionPasses(
            listOf(a), listOf(a, b),
            exitLineCounts = listOf(2), enterLineCounts = listOf(2, 2)
        )
        assertEquals(setOf(0), stable.continuingEnterBlocks)
        assertEquals(emptySet<Int>(), stable.departingExitBlocks)
        assertEquals(setOf(1), stable.arrivingEnterBlocks)
    }

    @Test
    fun shrinkPivotPreservesAlignmentEdge() {
        assertEquals(100f, resolveDuetSectionPivotX(AodLyricCanvasView.Alignment.START, 100f, 700f), 0.0001f)
        assertEquals(700f, resolveDuetSectionPivotX(AodLyricCanvasView.Alignment.END, 100f, 700f), 0.0001f)
        assertEquals(400f, resolveDuetSectionPivotX(AodLyricCanvasView.Alignment.CENTER, 100f, 700f), 0.0001f)
    }

    @Test
    fun mutedExitContentKeepsTimingButDropsEffects() {
        val content = LyricSnapshot(
            original = "lead",
            positionMs = 1500L,
            words = listOf(LyricWord("lead", "", 1000L, 2000L, true))
        ).toAodCanvasContent().withMutedEffects()

        assertEquals("Off", content.glowMode)
        assertEquals("Gradient", content.animationMode)
        assertEquals("lead", content.original)
        assertEquals(1500L, content.positionMs)
        assertEquals(1, content.words.size)
    }

    @Test
    fun wrapModeBaseSizeIgnoresLineLength() {
        assertEquals(
            baseTextSizeForMode("oh", "Wrap"),
            baseTextSizeForMode("although my heart is bleeding you still do not feel a thing", "Wrap"),
            0.0001f
        )
    }

    @Test
    fun clipModeBaseSizeKeepsLengthBuckets() {
        assertTrue(
            baseTextSizeForMode("oh", "Clip") >
                baseTextSizeForMode("although my heart is bleeding you still do not feel a thing", "Clip")
        )
    }

    @Test
    fun blankAndDegeneratePassesNeverCommitLayoutState() {
        assertTrue(shouldCommitLayoutState(true, true, true))
        assertFalse(shouldCommitLayoutState(false, true, true))
        assertFalse(shouldCommitLayoutState(true, false, true))
        assertFalse(shouldCommitLayoutState(true, true, false))
        assertFalse(shouldCommitLayoutState(false, false, false))
    }
}
