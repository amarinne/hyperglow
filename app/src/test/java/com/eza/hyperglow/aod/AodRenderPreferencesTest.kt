package com.eza.hyperglow.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AodRenderPreferencesTest {
    @Test
    fun storedAutoValuesMigrateToConcreteDefaults() {
        assertEquals("Main only", normalizeAodSecondary("auto"))
        assertEquals("Wrap", normalizeAodOverflow("Scroll with lyric"))
        assertEquals("hide", normalizeAodMetadataVisible("auto"))
        assertEquals("top", normalizeAodMetadataAnchor("fixed"))
        assertEquals("Medium", normalizeAodWeight("auto"))
        assertEquals("normal", normalizeAodTextSize("auto"))
        assertEquals("spotify", normalizeAodFontFamily("auto"))
        assertEquals("noto", normalizeAodFontFamily("noto"))
        assertEquals("show", normalizeAodMetadataVisible("show"))
        assertEquals("Off", normalizeAodGlow("auto"))
        assertEquals("On", normalizeAodGlow("Word only"))
        assertEquals("On", normalizeAodGlow("Subtle line"))
    }

    @Test
    fun legacyAnimationNamesMigrateToGradient() {
        assertEquals("Gradient", normalizeAodAnimation("Spotlight word"))
        assertEquals("Gradient", normalizeAodAnimation("Karaoke fill"))
        assertEquals("Minimal", normalizeAodAnimation("Minimal"))
    }

    @Test
    fun burnInControlsNormalizeToBoundedPatternsAndIntervals() {
        assertEquals("static_bottom", normalizeAodBurnInPattern("unknown"))
        assertEquals("static_top", normalizeAodBurnInPattern("static_top"))
        assertEquals("static_bottom", normalizeAodBurnInPattern("static_bottom"))
        assertEquals("six_zone", normalizeAodBurnInPattern("six_zone"))
        assertEquals("four_corner", normalizeAodBurnInPattern("four_corner"))
        assertEquals("vertical_swap", normalizeAodBurnInPattern("vertical_swap"))
        assertEquals(30_000L, normalizeAodBurnInInterval(1L))
        assertEquals(60_000L, normalizeAodBurnInInterval(60_000L))
        assertEquals(120_000L, normalizeAodBurnInInterval(150_000L))
        assertEquals(300_000L, normalizeAodBurnInInterval(Long.MAX_VALUE))
    }

    @Test
    fun configDefaultsAreConcreteExceptAlignment() {
        val config = AodRenderConfig()

        assertEquals(true, config.aodEnabled)
        assertEquals(false, config.lockscreenEnabled)
        assertEquals(true, config.seamlessTransitionEnabled)
        assertEquals("auto", config.alignment)
        assertEquals("Main only", config.secondaryMode)
        assertEquals("Wrap", config.overflowMode)
        assertEquals("hide", config.metadataVisible)
        assertEquals("top", config.metadataAnchor)
        assertEquals("Medium", config.weight)
        assertEquals("normal", config.textSize)
        assertEquals("spotify", config.fontFamily)
        assertEquals("Gradient", config.animation)
        assertEquals("Off", config.glow)
        assertEquals(true, config.adaptiveSectioning)
        assertEquals(false, config.experimentalPositionFollowing)
        assertEquals("static_bottom", config.burnInPattern)
        assertEquals(60_000L, config.burnInIntervalMs)
        assertFalse(config.lockscreenKeepAwake)
        assertFalse(config.raiseToAod)
    }
}
