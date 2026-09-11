package com.eza.hyperglow.customization

import kotlinx.serialization.Serializable

@Serializable
data class CustomizationDocument(
    val version: Int = CURRENT_CUSTOMIZATION_VERSION,
    val id: String = "default_continuity",
    val name: String = "Seamless Default",
    val linkSurfaces: Boolean = false,
    val profiles: Map<String, SurfaceProfile> = emptyMap()
)

@Serializable
data class SurfaceProfile(
    val enabled: Boolean = true,
    val anchor: String = "below_stock_clock",
    val widthFraction: Float = 0.88f,
    val maxHeightFraction: Float = 0.46f,
    val verticalBias: Float = 0.5f,
    val collisionPolicy: String = "avoid",
    val widgets: List<WidgetSpec> = listOf(WidgetSpec("lyrics")),
    val transition: TransitionPreset = TransitionPreset(),
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = DEFAULT_LYRIC_LINE_LIMIT,
    val metadataVisible: Boolean = false,
    val metadataAnchor: String = "top",
    val metadataSizePercent: Int = 100,
    val rubyVisible: Boolean = true,
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val lineSyncFillMode: String = "Left to right (main only)",
    val overflow: String = "Wrap",
    val adaptiveSectioning: Boolean = true,
    val duetEnabled: Boolean = true,
    val palette: Map<String, String> = emptyMap(),
    val backgroundStyle: String = "auto",
    val cardColor: String = DEFAULT_CARD_COLOR,
    val cardAlpha: Float = DEFAULT_CARD_ALPHA
)

@Serializable
data class WidgetSpec(
    val type: String,
    val style: String = "primary",
    val optional: Boolean = false,
    val visible: Boolean = true
)

@Serializable
data class TransitionPreset(
    val id: String = "continuity",
    val durationMs: Int = 320,
    val easing: String = "fast_out_slow_in"
)

@Serializable
data class CompiledCustomization(
    val version: Int,
    val revision: Long,
    val hash: String,
    val sourceId: String,
    val linkSurfaces: Boolean,
    val profiles: Map<String, CompiledSurfaceProfile>,
    val pauseLingerMs: Long = 5_000L,
    val diagnosticLogging: Boolean = false,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false,
    val suppressLockscreenEditorLongPress: Boolean = false,
    val aodBrightnessOverride: Boolean = false,
    val aodBrightnessLevel: Int = 255
)

@Serializable
data class CompiledSurfaceProfile(
    val surface: String,
    val enabled: Boolean,
    val anchor: String,
    val widthFraction: Float,
    val maxHeightFraction: Float,
    val verticalBias: Float,
    val collisionPolicy: String,
    val widgets: List<WidgetSpec>,
    val transition: TransitionPreset,
    val alignment: String,
    val secondaryMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val weight: String,
    val textSize: String,
    val textSizeCustom: Int,
    val fontFamily: String,
    val animation: String,
    val glow: String,
    val lineSyncFillMode: String,
    val overflow: String,
    val adaptiveSectioning: Boolean,
    val duetEnabled: Boolean = true,
    val palette: Map<String, String>,
    val backgroundStyle: String = "none",
    val metadataSizePercent: Int = 100,
    val rubyVisible: Boolean = true,
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = DEFAULT_LYRIC_LINE_LIMIT,
    val cardColor: String = DEFAULT_CARD_COLOR,
    val cardAlpha: Float = DEFAULT_CARD_ALPHA
)

/** Canvas height before the setting existed: an implicit default, not a choice. */
const val LEGACY_AOD_MAX_HEIGHT_FRACTION = 0.42f

/** Advised AOD canvas height default: portrait duets fit near full size. */
const val DEFAULT_AOD_MAX_HEIGHT_FRACTION = 0.75f

const val CURRENT_CUSTOMIZATION_VERSION = 2
const val DEFAULT_LYRIC_LINE_LIMIT = 3
const val NO_LYRIC_LINE_LIMIT = 0
/** Upper bound exposed by the appearance editor for custom lyric text size. */
const val MAX_LYRIC_TEXT_SIZE_PERCENT = 300
const val MIN_AOD_BRIGHTNESS = 10
const val MAX_AOD_BRIGHTNESS = 255

/** Existing lockscreen scrim: charcoal at 217/255 opacity. */
const val DEFAULT_CARD_COLOR = "charcoal"
const val DEFAULT_CARD_ALPHA = 0.8509804f

/** Values accepted by the declarative appearance configuration. */
internal val PALETTE_VALUES = setOf(
    "default",
    "clock",
    "wallpaper",
    "white",
    "dimmed",
    "lavender",
    "mint"
)

/** Card presets stay deliberately small; custom colors use a validated RGB token. */
internal val CARD_COLOR_VALUES = setOf("black", "charcoal", "deep_purple")

internal fun normalizeHexColor(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.matches(Regex("#[0-9a-fA-F]{6}"))) return null
    return trimmed.uppercase()
}

internal fun normalizePaletteValue(value: String): String? =
    value.trim().lowercase().let { token ->
        token.takeIf { it in PALETTE_VALUES } ?: normalizeHexColor(token)
    }

internal fun normalizeCardColor(value: String): String =
    value.trim().lowercase().let { token ->
        when {
            token in CARD_COLOR_VALUES -> token
            token == "deep purple" -> "deep_purple"
            else -> normalizeHexColor(token) ?: DEFAULT_CARD_COLOR
        }
    }

internal fun normalizeCardAlpha(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: DEFAULT_CARD_ALPHA

internal fun normalizeLyricLineLimit(value: Int): Int = when (value) {
    NO_LYRIC_LINE_LIMIT,
    in 1..5 -> value
    else -> DEFAULT_LYRIC_LINE_LIMIT
}
