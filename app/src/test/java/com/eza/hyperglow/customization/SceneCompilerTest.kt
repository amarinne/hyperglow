package com.eza.hyperglow.customization

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.root.customization.SystemUiCustomizationValidator
import com.eza.hyperglow.root.customization.WidgetRendererRegistry
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCompilerTest {
    @Test
    fun safeDefaultsUseLyricsOnlySpotifyMainLineSweep() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertFalse(lockscreen.enabled)
        assertTrue(aod.enabled)
        listOf(lockscreen, aod).forEach { profile ->
            assertEquals(listOf("lyrics"), profile.widgets.map { it.type })
            assertFalse(profile.metadataVisible)
            assertEquals("spotify", profile.fontFamily)
            assertEquals("Left to right (main only)", profile.lineSyncFillMode)
        }
    }

    @Test
    fun legacyPreferencesMigrateWithoutEnablingLockscreen() {
        val document = CustomizationRepository.documentFromLegacy(
            AodRenderConfig(
                lockscreenEnabled = false,
                alignment = "end",
                secondaryMode = "Both",
                metadataVisible = "hide",
                weight = "Bold",
                fontFamily = "spotify"
            )
        )
        val lockscreen = document.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = document.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertFalse(lockscreen.enabled)
        assertTrue(aod.enabled)
        assertEquals("end", aod.alignment)
        assertEquals("Both", aod.secondaryMode)
        assertFalse(aod.metadataVisible)
        assertEquals("Bold", aod.weight)
        assertEquals("spotify", aod.fontFamily)
    }

    @Test
    fun unknownWidgetsDropAndMissingLyricsFallsBackSafely() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        widgets = listOf(WidgetSpec("unknown"), WidgetSpec("artwork_accent"))
                    )
                )
            )
        )

        assertEquals(
            listOf("lyrics"),
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).widgets.map { it.type }
        )
    }

    @Test
    fun aodPolicyClampsComponentsHeightAndTransition() {
        val widgets = listOf(
            WidgetSpec("metadata"),
            WidgetSpec("status_text"),
            WidgetSpec("spacer"),
            WidgetSpec("divider"),
            WidgetSpec("lyrics"),
            WidgetSpec("media_progress")
        )
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        maxHeightFraction = 0.9f,
                        widgets = widgets,
                        transition = TransitionPreset(durationMs = 5_000)
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(0.5f, compiled.maxHeightFraction)
        assertTrue(compiled.widgets.size <= SceneCompiler.MAX_AOD_WIDGETS)
        assertFalse(compiled.widgets.any { it.type == "media_progress" })
        assertEquals(600, compiled.transition.durationMs)
    }

    @Test
    fun metadataSizeClampsAndFuriganaPreferenceSurvivesValidation() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataSizePercent = 900,
                        rubyVisible = false
                    )
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
            .profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(200, validated.metadataSizePercent)
        assertFalse(validated.rubyVisible)
    }

    @Test
    fun lineLevelSweepDirectionIsCompiledAndValidated() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right (main only)"
                    )
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!

        assertEquals(
            "Left to right (main only)",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "None",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "None")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "Left to right (main only)",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "Diagonal")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
    }

    @Test
    fun schemaRejectsOversizeAndExecutableReferencesButIgnoresUnknownFields() {
        assertNull(SceneCompiler.decodeDocument("x".repeat(SceneCompiler.MAX_CONFIG_BYTES + 1)))
        assertNull(SceneCompiler.decodeDocument("""{"version":1,"name":"file:///tmp/x"}"""))
        assertNull(
            SceneCompiler.decodeDocument(
                """{"version":1,"name":"https\u003a//example.invalid/profile"}"""
            )
        )
        assertNull(SceneCompiler.decodeDocument("""{"version":1,"className":"Injected"}"""))
        assertNotNull(
            SceneCompiler.decodeDocument(
                """{"version":1,"id":"safe","unknown":{"nested":true}}"""
            )
        )
    }

    @Test
    fun revisionHashIsStableAndChangesWithProfile() {
        val first = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val same = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val changed = SceneCompiler.compile(
            SceneCompiler.safeDefaultDocument().copy(linkSurfaces = true)
        )

        assertEquals(first.hash, same.hash)
        assertEquals(first.revision, same.revision)
        assertNotEquals(first.hash, changed.hash)
    }

    @Test
    fun linkedCompilerDerivesOneStyleButPreservesEnableFlags() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                linkSurfaces = true,
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        enabled = false,
                        alignment = "start"
                    ),
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        enabled = true,
                        alignment = "end"
                    )
                )
            )
        )

        assertEquals(
            "end",
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).alignment
        )
        assertFalse(compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).enabled)
        assertTrue(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).enabled)
        assertEquals(
            "card",
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).backgroundStyle
        )
        assertEquals("none", compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).backgroundStyle)
    }

    @Test
    fun linkedStylingStillPreservesSurfaceSpecificMetadataVisibility() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                linkSurfaces = true,
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        metadataVisible = false,
                        widgets = listOf(WidgetSpec("lyrics"))
                    ),
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataVisible = true,
                        widgets = listOf(WidgetSpec("lyrics"), WidgetSpec("metadata", optional = true))
                    )
                )
            )
        )

        assertFalse(compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).metadataVisible)
        assertTrue(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).metadataVisible)
    }

    @Test
    fun systemUiValidationPreservesLockscreenOnlyCardAndSafeCollisionPolicy() {
        val compiled = SceneCompiler.compile(
            SceneCompiler.safeDefaultDocument().copy(
                linkSurfaces = true,
                profiles = SceneCompiler.safeDefaultDocument().profiles +
                    (SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        collisionPolicy = "avoid",
                        backgroundStyle = "card"
                    ))
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
        val lockscreen = validated.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = validated.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals("avoid", lockscreen.collisionPolicy)
        assertEquals("card", lockscreen.backgroundStyle)
        assertEquals("none", aod.backgroundStyle)
    }

    @Test
    fun systemUiValidatorReappliesRegistryAndAodLimits() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).copy(
            widgets = listOf(WidgetSpec("unknown"), WidgetSpec("artwork_accent")),
            maxHeightFraction = 1f
        )
        val validated = SystemUiCustomizationValidator.validate(
            compiled.copy(profiles = compiled.profiles + (SceneCompiler.SURFACE_AOD to aod))
        )!!.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(listOf("lyrics"), validated.widgets.map { it.type })
        assertEquals(0.5f, validated.maxHeightFraction)
        assertNotNull(WidgetRendererRegistry.renderer("lyrics"))
        assertNull(WidgetRendererRegistry.renderer("arbitrary_class"))
    }

    @Test
    fun systemUiValidatorRejectsVersionAndChangesCanonicalDigestAfterTampering() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        assertNull(SystemUiCustomizationValidator.validate(compiled.copy(version = 99)))

        val tamperedAod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).copy(
            anchor = "screen_center",
            palette = mapOf("primaryText" to "dimmed")
        )
        val validated = SystemUiCustomizationValidator.validate(
            compiled.copy(profiles = compiled.profiles + (SceneCompiler.SURFACE_AOD to tamperedAod))
        )!!

        assertEquals(
            "screen_center",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).anchor
        )
        assertEquals(
            "dimmed",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).palette["primaryText"]
        )
        assertNotEquals(compiled.hash, validated.hash)
    }

    @Test
    fun repositoryRejectsFutureVersionAndRecoversPreviousDocument() {
        assertNull(
            CustomizationRepository.canonicalizeDocument(
                SceneCompiler.safeDefaultDocument().copy(version = CURRENT_CUSTOMIZATION_VERSION + 1)
            )
        )
        val previous = SceneCompiler.safeDefaultDocument().copy(name = "Previous")
        val previousRaw = SceneCompiler.json.encodeToString(previous)
        val recovered = CustomizationRepository.recoverDocument(
            currentRaw = "{broken",
            previousRaw = previousRaw,
            legacy = AodRenderConfig()
        )

        assertEquals("Previous", recovered.name)
    }

    @Test
    fun placementHidesOptionalWidgetsBeforePrimaryLyric() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true, maxHeightFraction = 1f)
        val lyric = WidgetSpec("lyrics")
        val metadata = WidgetSpec("metadata", optional = true)
        val resolved = PlacementEngine.resolve(
            profile,
            PlacementEnvironment(
                safeCanvas = PlacementRect(0f, 0f, 1000f, 500f),
                stockClockBottom = 100f,
                bottomReserveTop = 300f
            ),
            listOf(WidgetMeasurement(lyric, 160f), WidgetMeasurement(metadata, 80f)),
            minimumLyricHeight = 100f
        )

        assertNotNull(resolved.contentRect)
        assertEquals(listOf("lyrics"), resolved.visibleWidgets.map { it.type })
        assertEquals(listOf("metadata"), resolved.hiddenWidgets.map { it.type })
    }

    @Test
    fun placementShrinksPrimaryLyricToMinimumAfterOptionalWidgetsHide() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true, maxHeightFraction = 1f)
        val resolved = PlacementEngine.resolve(
            profile,
            PlacementEnvironment(
                safeCanvas = PlacementRect(0f, 0f, 1_000f, 500f),
                stockClockBottom = 100f,
                bottomReserveTop = 250f
            ),
            listOf(
                WidgetMeasurement(WidgetSpec("lyrics"), 240f),
                WidgetMeasurement(WidgetSpec("metadata", optional = true), 60f)
            ),
            minimumLyricHeight = 100f
        )

        assertEquals(150f, resolved.contentRect?.height)
        assertEquals(listOf("lyrics"), resolved.visibleWidgets.map { it.type })
    }
}
