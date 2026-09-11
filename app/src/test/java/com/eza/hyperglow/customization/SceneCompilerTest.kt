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
    fun lockscreenOverlapChoicesNormalizeToSafePlacement() {
        for (legacy in listOf("behind_system", "hide_optional", "unknown")) {
            val document = SceneCompiler.safeDefaultDocument()
            val compiled = SceneCompiler.compile(document.copy(profiles = document.profiles +
                (SceneCompiler.SURFACE_LOCKSCREEN to SceneCompiler.safeLockscreenProfile().copy(
                    collisionPolicy = legacy))))
            assertEquals("avoid", compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).collisionPolicy)
        }
    }

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
    fun versionOneDocumentsMigrateImplicitAodCanvasHeightToNewDefault() {
        // v1 documents predate the canvas-height setting: the AOD 0.42 is the
        // old implicit default, so it moves to the advised default. The
        // lockscreen keeps its own default, and explicit non-default
        // selections are preserved.
        val v1 = SceneCompiler.safeDefaultDocument().copy(
            version = 1,
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(maxHeightFraction = 0.42f),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(maxHeightFraction = 0.42f)
            )
        )
        val migrated = CustomizationRepository.canonicalizeDocument(v1)!!

        assertEquals(2, migrated.version)
        assertEquals(
            0.75f,
            migrated.profiles.getValue(SceneCompiler.SURFACE_AOD).maxHeightFraction
        )
        assertEquals(
            0.42f,
            migrated.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).maxHeightFraction
        )

        // Explicit selections survive the migration untouched.
        val explicit = CustomizationRepository.canonicalizeDocument(
            v1.copy(
                profiles = linkedMapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(maxHeightFraction = 0.25f)
                )
            )
        )!!
        assertEquals(0.25f, explicit.profiles.getValue(SceneCompiler.SURFACE_AOD).maxHeightFraction)

        // Version 2 documents carry explicit values: even 0.42 is a choice.
        val explicitV2 = CustomizationRepository.canonicalizeDocument(
            SceneCompiler.safeDefaultDocument().copy(
                version = 2,
                profiles = linkedMapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(maxHeightFraction = 0.42f)
                )
            )
        )!!
        assertEquals(0.42f, explicitV2.profiles.getValue(SceneCompiler.SURFACE_AOD).maxHeightFraction)
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
        assertEquals(0.75f, aod.maxHeightFraction)
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
                        maxHeightFraction = 0.95f,
                        widgets = widgets,
                        transition = TransitionPreset(durationMs = 5_000)
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(0.9f, compiled.maxHeightFraction)
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
                        textSize = "custom",
                        textSizeCustom = 900,
                        rubyVisible = false
                    )
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
            .profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(200, validated.metadataSizePercent)
        assertEquals(MAX_LYRIC_TEXT_SIZE_PERCENT, validated.textSizeCustom)
        assertFalse(validated.rubyVisible)
    }

    @Test
    fun lyricTextSizeAllowsTheEditorThreeHundredPercentUpperBound() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        textSize = "custom",
                        textSizeCustom = 900
                    )
                )
            )
        )

        assertEquals(
            MAX_LYRIC_TEXT_SIZE_PERCENT,
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).textSizeCustom
        )
    }

    @Test
    fun lyricLineLimitAndSecondaryBrightnessCompileWithSafeFallback() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lyricLineLimit = 0,
                        secondaryTextBright = false
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(0, compiled.lyricLineLimit)
        assertFalse(compiled.secondaryTextBright)
        assertEquals(
            DEFAULT_LYRIC_LINE_LIMIT,
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(lyricLineLimit = 99)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD).lyricLineLimit
        )
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
            "Left to right (whole block)",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "Left to right (whole block)")
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
                            .copy(lineSyncFillMode = "Left to right")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
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
        assertEquals(0.9f, validated.maxHeightFraction)
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
    fun appearancePaletteAndCardValuesCompileAndRoundTripWithSafeBounds() {
        val source = CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                    backgroundStyle = "card",
                    cardColor = "#151519",
                    cardAlpha = 1.5f,
                    palette = mapOf(
                        "primaryText" to "lavender",
                        "sungText" to "#62D891",
                        "metadataText" to "#9998A4",
                        "surfaceScrim" to "not-a-color"
                    )
                )
            )
        )

        val compiled = SceneCompiler.compile(source)
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals("#151519", lockscreen.cardColor)
        assertEquals(1f, lockscreen.cardAlpha)
        assertEquals("lavender", lockscreen.palette["primaryText"])
        assertEquals("#62D891", lockscreen.palette["sungText"])
        assertEquals("#9998A4", lockscreen.palette["metadataText"])
        assertFalse(lockscreen.palette.containsKey("surfaceScrim"))

        val roundTrip = SceneCompiler.decodeDocument(
            SceneCompiler.json.encodeToString(source)
        )!!
        assertEquals("#151519", roundTrip.profiles
            .getValue(SceneCompiler.SURFACE_LOCKSCREEN).cardColor)
        assertEquals("#9998A4", roundTrip.profiles
            .getValue(SceneCompiler.SURFACE_LOCKSCREEN).palette["metadataText"])

        val invalid = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        cardColor = "javascript:alert(1)",
                        cardAlpha = Float.NaN,
                        palette = mapOf("primaryText" to "#12345")
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals(DEFAULT_CARD_COLOR, invalid.cardColor)
        assertEquals(DEFAULT_CARD_ALPHA, invalid.cardAlpha)
        assertTrue(invalid.palette.isEmpty())
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
