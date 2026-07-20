package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationEditorStateTest {
    @Test
    fun linkedEditDerivesBothProfilesButPreservesEnableToggles() {
        val state = CustomizationEditorState(
            SceneCompiler.safeDefaultDocument()
        ).setLinkSurfaces(true)
            .updateSelected { it.copy(anchor = "screen_center", widthFraction = 0.7f) }

        val lock = state.document.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = state.document.profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("screen_center", lock.anchor)
        assertEquals("screen_center", aod.anchor)
        assertEquals(0.7f, lock.widthFraction)
        assertFalse(lock.enabled)
        assertTrue(aod.enabled)
    }

    @Test
    fun unlinkedEditChangesOnlySelectedSurface() {
        val state = CustomizationEditorState(SceneCompiler.safeDefaultDocument())
            .setLinkSurfaces(false)
            .selectSurface(SceneCompiler.SURFACE_AOD)
            .updateSelected { it.copy(anchor = "screen_bottom_safe") }

        assertEquals(
            "screen_bottom_safe",
            state.document.profiles.getValue(SceneCompiler.SURFACE_AOD).anchor
        )
        assertEquals(
            "below_stock_clock",
            state.document.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).anchor
        )
    }

    @Test
    fun resetRestoresKnownSafeProfile() {
        val reset = CustomizationEditorState(
            CustomizationDocument(profiles = emptyMap())
        ).reset()

        assertFalse(reset.document.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).enabled)
        assertTrue(reset.document.profiles.getValue(SceneCompiler.SURFACE_AOD).enabled)
        assertFalse(reset.document.linkSurfaces)
    }
}
