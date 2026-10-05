package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.normalizeAodTextSize
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import org.junit.Assert.*
import org.junit.Test

class ResponseCreditLayoutTest {
    @Test fun creditUsesSmallFixedTypeEvenWithLargeLyricSettings() {
        assertEquals("credit", normalizeAodTextSize("credit"))
        assertEquals(16f, originalTextSizeSp("credit", "Wrap", "credit", 500, 2f), 0f)
        assertTrue(originalTextSizeSp("lyric", "Wrap", "normal", 100, 1f) > 16f)
    }

    @Test fun creditTypographySurvivesSurfaceProfileOverrides() {
        val profile = SceneCompiler.compile(CustomizationDocument(profiles = mapOf(
            SceneCompiler.SURFACE_AOD to SurfaceProfile(textSize = "custom", textSizeCustom = 500,
                overflow = "Clip", lyricLineLimit = 1)
        ))).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val content = LyricSnapshot(original = "provider\nuploader\nmaker", textSizeMode = "credit")
            .toAodCanvasContent(profile)
        assertEquals("credit", content.textSizeMode)
        assertEquals("Wrap", content.overflowMode)
        assertEquals("Minimal", content.animationMode)
        assertFalse(content.metadataVisible)
    }

    @Test fun explicitCreditRowsAreNeverFlattenedOrLimitedToThreeLines() {
        val text = "Written by: Writer\nLyrics from Spicy Lyrics\nuploaded by Uploader\nmade by Maker"
        val rows = responseCreditLineRanges(text) { it.length }.map { text.substring(it) }
        assertEquals(listOf("Written by: Writer", "Lyrics from Spicy Lyrics", "uploaded by Uploader", "made by Maker"), rows)
    }

    @Test fun longNamesWrapWithinTheirOwnRowAtWordBoundaries() {
        val text = "uploaded by Long Contributor Name\nmade by Maker"
        val rows = responseCreditLineRanges(text) { 18 }.map { text.substring(it) }
        assertEquals(listOf("uploaded by Long", "Contributor Name", "made by Maker"), rows)
    }

    @Test fun narrowWrapDoesNotSplitSurrogatePairs() {
        val text = "😀😀\nMaker"
        val rows = responseCreditLineRanges(text) { 1 }.map { text.substring(it) }
        assertEquals(listOf("😀", "😀", "M", "a", "k", "e", "r"), rows)
    }
}
