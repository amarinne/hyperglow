package com.eza.hyperglow.ui

import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationPreviewTest {
    @Test
    fun previewPlacementUsesNotificationAndFodReserves() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true)

        val notification = resolvePreviewPlacement(
            profile,
            "Lockscreen · notifications",
            1_000f,
            500f
        )
        val notificationEnvironment = previewEnvironment(
            "Lockscreen · notifications",
            1_000f,
            500f
        )
        assertTrue(
            notification.contentRect?.bottom ?: Float.POSITIVE_INFINITY <=
                notificationEnvironment.notificationTop!!
        )

        val fod = resolvePreviewPlacement(profile, "FOD safe region", 1_000f, 500f)
        val fodEnvironment = previewEnvironment("FOD safe region", 1_000f, 500f)
        assertTrue(
            fod.contentRect?.bottom ?: Float.POSITIVE_INFINITY <= fodEnvironment.bottomReserveTop
        )
    }

    @Test
    fun palettePresetAndMetadataToggleStayDeclarative() {
        val dimmed = palettePreset("dimmed")
        assertTrue(dimmed.isNotEmpty())
        assertEquals("dimmed", palettePresetName(dimmed))
        assertEquals("default", palettePresetName(emptyMap()))

        val hidden = withMetadataVisible(SurfaceProfile(), false)
        assertFalse(hidden.metadataVisible)
        assertFalse(hidden.widgets.any { it.type == "metadata" })

        val visible = withMetadataVisible(hidden, true)
        assertTrue(visible.metadataVisible)
        assertTrue(visible.widgets.any { it.type == "metadata" })
    }
}
