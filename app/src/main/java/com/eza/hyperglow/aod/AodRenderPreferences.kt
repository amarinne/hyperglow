package com.eza.hyperglow.aod

import android.content.Context
import android.content.SharedPreferences
import com.eza.hyperglow.aod.AodRenderConfig.Companion.DEFAULTS
import com.eza.hyperglow.customization.MAX_AOD_BRIGHTNESS
import com.eza.hyperglow.customization.MIN_AOD_BRIGHTNESS

internal const val DEFAULT_AOD_BRIGHTNESS = MAX_AOD_BRIGHTNESS

data class AodRenderConfig(
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val seamlessTransitionEnabled: Boolean = true,
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val overflowMode: String = "Wrap",
    val metadataVisible: String = "hide",
    val metadataAnchor: String = "top",
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val adaptiveSectioning: Boolean = true,
    val keepAwake: Boolean = true,
    val keepAwakeUnsynced: Boolean = false,
    val keepAwakeDurationMs: Long = -1L,
    val experimentalPositionFollowing: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val suppressStockAodContent: Boolean = false,
    val aodRotateWithDevice: Boolean = false,
    val aodRotationMode: String = AOD_ROTATION_MODE_PORTRAIT,
    val aodCanvasAnchor: Float = 0.5f,
    val aodRotationSettleMs: Long = 1_000L,
    val aodCanvasAnchorLandscape: Float = 0.5f,
    val aodLandscapeTextScale: Float = 1f,
    val aodCanvasPaddingPortraitXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingPortraitYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val pauseLingerMs: Long = 5_000L,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false,
    val suppressLockscreenEditorLongPress: Boolean = false,
    val songChangeInfoEnabled: Boolean = true,
    val hideLauncherIcon: Boolean = false,
    val hideFromRecents: Boolean = false,
    val duetEnabled: Boolean = true,
    /** Applies the configured raw brightness only during the eligible AOD lyric guard. */
    val aodBrightnessOverride: Boolean = false,
    /** Xiaomi's raw doze brightness scale, bounded to the HTML/API range 10..255. */
    val aodBrightnessLevel: Int = DEFAULT_AOD_BRIGHTNESS
) {
    companion object {
        /**
         * Single source of the shipped defaults. Readers and codecs must reference these instead
         * of restating literals, so a new field cannot drift between the model, the preference
         * file, and the backup format.
         */
        val DEFAULTS = AodRenderConfig()
    }
}

internal fun normalizeAodAlignment(value: String?): String = when (value) {
    "auto" -> "auto"
    "start" -> "start"
    "center" -> "center"
    "end" -> "end"
    else -> "auto"
}

internal fun normalizeAodSecondary(value: String?): String = when (value) {
    "Transliteration" -> "Transliteration"
    "Translation" -> "Translation"
    "Both" -> "Both"
    else -> "Main only"
}

internal fun normalizeAodOverflow(value: String?): String = when (value) {
    "Clip" -> "Clip"
    else -> "Wrap"
}

internal fun normalizeAodMetadataVisible(value: String?): String =
    if (value == "show") "show" else "hide"

internal fun normalizeAodMetadataAnchor(value: String?): String =
    if (value == "bottom") "bottom" else "top"

internal fun normalizeAodWeight(value: String?): String = when (value) {
    "Regular" -> "Regular"
    "Bold" -> "Bold"
    else -> "Medium"
}

internal fun normalizeAodTextSize(value: String?): String = when (value) {
    "small" -> "small"
    "large" -> "large"
    "xlarge" -> "xlarge"
    "custom" -> "custom"
    else -> "normal"
}

internal fun normalizeAodFontFamily(value: String?): String = when (value) {
    "noto" -> "noto"
    "spotify" -> "spotify"
    "apple" -> "apple"
    else -> "spotify"
}

internal fun normalizeAodAnimation(value: String?): String =
    if (value == "Minimal") "Minimal" else "Gradient"

internal fun normalizeAodGlow(value: String?): String = when (value) {
    "On", "Word only", "Subtle line" -> "On"
    else -> "Off"
}

internal fun normalizeAodBurnInPattern(value: String?): String = when (value) {
    "static_top" -> "static_top"
    "static_bottom" -> "static_bottom"
    "vertical_swap" -> "vertical_swap"
    "four_corner" -> "four_corner"
    "six_zone" -> "six_zone"
    else -> "static_bottom"
}

internal fun normalizeAodCanvasAnchor(value: Float): Float =
    if (value.isFinite() && value in 0f..1f) value else DEFAULT_CANVAS_ANCHOR

internal fun normalizeAodRotationSettleMs(value: Long): Long = when (value) {
    0L, 500L, 1_000L, 2_000L, 5_000L, 10_000L -> value
    else -> DEFAULT_ROTATION_SETTLE_MS
}

internal fun normalizeAodRotationMode(value: String?): String = when (value) {
    AOD_ROTATION_MODE_LANDSCAPE -> AOD_ROTATION_MODE_LANDSCAPE
    AOD_ROTATION_MODE_LANDSCAPE_REVERSE -> AOD_ROTATION_MODE_LANDSCAPE_REVERSE
    AOD_ROTATION_MODE_AUTO -> AOD_ROTATION_MODE_AUTO
    else -> AOD_ROTATION_MODE_PORTRAIT
}

internal fun normalizeAodLandscapeTextScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.5f, 2f) else DEFAULT_LANDSCAPE_TEXT_SCALE

internal fun normalizeAodCanvasPaddingDp(value: Int): Int = value.coerceIn(0, 64)

/**
 * Per-axis canvas padding in percent of the logical frame (0-20%). Percent
 * keeps insets proportional on the long and short axes, where an absolute dp
 * value would eat ~2.3x more of the short axis than the long one.
 */
internal fun normalizeAodCanvasPaddingPercent(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, MAX_CANVAS_PADDING_PERCENT)
    else DEFAULT_CANVAS_PADDING_PERCENT

/** Legacy single dp padding migrates to both axes (400dp reference short side). */
internal fun legacyPaddingDpToPercent(value: Int): Float =
    normalizeAodCanvasPaddingPercent(value / 4f)

internal const val MAX_CANVAS_PADDING_PERCENT = 20f
internal const val DEFAULT_CANVAS_PADDING_PERCENT = 2f

private const val DEFAULT_CANVAS_ANCHOR = 0.5f
private const val DEFAULT_ROTATION_SETTLE_MS = 1_000L
private const val DEFAULT_LANDSCAPE_TEXT_SCALE = 1f
internal const val AOD_ROTATION_MODE_PORTRAIT = "portrait"
internal const val AOD_ROTATION_MODE_LANDSCAPE = "landscape"
internal const val AOD_ROTATION_MODE_LANDSCAPE_REVERSE = "landscape_reverse"
internal const val AOD_ROTATION_MODE_AUTO = "auto"

internal fun normalizeAodBurnInInterval(value: Long): Long = when {
    value < 45_000L -> 30_000L
    value < 90_000L -> 60_000L
    value < 210_000L -> 120_000L
    else -> 300_000L
}

internal fun normalizeKeepAwakeDurationMs(value: Long): Long = when (value) {
    300_000L, 600_000L, 1_800_000L, 3_600_000L, 7_200_000L -> value
    else -> -1L
}

internal fun normalizePauseLingerMs(value: Long): Long = when (value) {
    -1L, 0L, 5_000L, 10_000L, 30_000L -> value
    else -> 5_000L
}

object AodRenderPreferences {
    const val PREFS = "aod_render"
    const val AOD_ENABLED = "aod_enabled"
    const val LOCKSCREEN_ENABLED = "lockscreen_enabled"
    const val SEAMLESS_TRANSITION_ENABLED = "seamless_transition_enabled"
    const val SONG_CHANGE_INFO_ENABLED = "song_change_info_enabled"
    const val ALIGNMENT = "alignment"
    const val SECONDARY = "secondary"
    const val OVERFLOW = "overflow"
    const val METADATA_VISIBLE = "metadata_visible"
    const val METADATA_ANCHOR = "metadata_anchor"
    const val WEIGHT = "weight"
    const val TEXT_SIZE = "text_size"
    const val TEXT_SIZE_CUSTOM = "text_size_custom"
    const val FONT_FAMILY = "font_family"
    const val ANIMATION = "animation"
    const val GLOW = "glow"
    const val ADAPTIVE_SECTIONING = "adaptive_sectioning"
    const val KEEP_AWAKE = "keep_awake"
    const val KEEP_AWAKE_UNSYNCED = "keep_awake_unsynced"
    const val KEEP_AWAKE_DURATION_MS = "keep_awake_duration_ms"
    const val EXPERIMENTAL_POSITION_FOLLOWING = "experimental_position_following"
    const val BURN_IN_PATTERN = "burn_in_pattern"
    const val BURN_IN_INTERVAL_MS = "burn_in_interval_ms"
    const val SUPPRESS_STOCK_AOD_CONTENT = "suppress_stock_aod_content"
    const val AOD_ROTATE_WITH_DEVICE = "aod_rotate_with_device"
    const val AOD_ROTATION_MODE = "aod_rotation_mode"
    const val AOD_CANVAS_ANCHOR = "aod_canvas_anchor"
    const val AOD_ROTATION_SETTLE_MS = "aod_rotation_settle_ms"
    const val AOD_CANVAS_ANCHOR_LANDSCAPE = "aod_canvas_anchor_landscape"
    const val AOD_LANDSCAPE_TEXT_SCALE = "aod_landscape_text_scale"
    const val AOD_CANVAS_PADDING_DP = "aod_canvas_padding_dp"
    const val AOD_CANVAS_PADDING_X_PERCENT = "aod_canvas_padding_x_percent"
    const val AOD_CANVAS_PADDING_Y_PERCENT = "aod_canvas_padding_y_percent"
    const val AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT = "aod_canvas_padding_portrait_x_percent"
    const val AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT = "aod_canvas_padding_portrait_y_percent"
    const val AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT = "aod_canvas_padding_landscape_x_percent"
    const val AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT = "aod_canvas_padding_landscape_y_percent"
    const val PAUSE_LINGER_MS = "pause_linger_ms"
    const val LOCKSCREEN_KEEP_AWAKE = "lockscreen_keep_awake"
    const val RAISE_TO_AOD = "raise_to_aod"
    const val SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS = "suppress_lockscreen_editor_long_press"
    const val HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val HIDE_FROM_RECENTS = "hide_from_recents"
    const val DUET_ENABLED = "duet_enabled"
    const val OVERRIDE_BRIGHTNESS = "override_brightness"
    const val AOD_BRIGHTNESS = "aod_brightness"

    /**
     * Per-orientation padding reads the orientation key first, then the
     * retired shared-axis key (so v123 slider tuning carries into both
     * orientations), then the legacy dp value.
     */
    private fun migratedPaddingPercent(
        prefs: SharedPreferences,
        key: String,
        legacyAxisKey: String,
        default: Float
    ): Float = normalizeAodCanvasPaddingPercent(
        if (prefs.contains(key)) {
            prefs.safeFloat(key, default)
        } else if (prefs.contains(legacyAxisKey)) {
            prefs.safeFloat(legacyAxisKey, default)
        } else {
            legacyPaddingDpToPercent(prefs.safeInt(AOD_CANVAS_PADDING_DP, 8))
        }
    )

    // SharedPreferences throws ClassCastException when an older/imported value has the wrong
    // primitive type. Treat malformed entries as missing so a bad setting cannot crash startup.
    private fun SharedPreferences.safeBoolean(key: String, default: Boolean): Boolean =
        runCatching { getBoolean(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeLong(key: String, default: Long): Long =
        runCatching { getLong(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeFloat(key: String, default: Float): Float =
        runCatching { getFloat(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeString(key: String, default: String?): String? =
        runCatching { getString(key, default) }.getOrDefault(default)

    private var preferences: SharedPreferences? = null
    private var cachedConfig: AodRenderConfig? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        synchronized(this) { cachedConfig = null }
    }

    @Synchronized
    fun read(context: Context): AodRenderConfig {
        val prefs = preferences ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            preferences = it
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        return cachedConfig ?: AodRenderConfig(
            prefs.safeBoolean(AOD_ENABLED, DEFAULTS.aodEnabled),
            prefs.safeBoolean(LOCKSCREEN_ENABLED, DEFAULTS.lockscreenEnabled),
            prefs.safeBoolean(
                SEAMLESS_TRANSITION_ENABLED,
                DEFAULTS.seamlessTransitionEnabled
            ),
            normalizeAodAlignment(prefs.safeString(ALIGNMENT, DEFAULTS.alignment)),
            normalizeAodSecondary(prefs.safeString(SECONDARY, DEFAULTS.secondaryMode)),
            normalizeAodOverflow(prefs.safeString(OVERFLOW, DEFAULTS.overflowMode)),
            normalizeAodMetadataVisible(prefs.safeString(METADATA_VISIBLE, DEFAULTS.metadataVisible)),
            normalizeAodMetadataAnchor(prefs.safeString(METADATA_ANCHOR, DEFAULTS.metadataAnchor)),
            normalizeAodWeight(prefs.safeString(WEIGHT, DEFAULTS.weight)),
            normalizeAodTextSize(prefs.safeString(TEXT_SIZE, DEFAULTS.textSize)),
            prefs.safeInt(
                TEXT_SIZE_CUSTOM,
                DEFAULTS.textSizeCustom
            ).coerceIn(50, com.eza.hyperglow.customization.MAX_LYRIC_TEXT_SIZE_PERCENT),
            normalizeAodFontFamily(prefs.safeString(FONT_FAMILY, DEFAULTS.fontFamily)),
            normalizeAodAnimation(prefs.safeString(ANIMATION, DEFAULTS.animation)),
            normalizeAodGlow(prefs.safeString(GLOW, DEFAULTS.glow)),
            prefs.safeBoolean(ADAPTIVE_SECTIONING, DEFAULTS.adaptiveSectioning),
            prefs.safeBoolean(KEEP_AWAKE, DEFAULTS.keepAwake),
            prefs.safeBoolean(KEEP_AWAKE_UNSYNCED, DEFAULTS.keepAwakeUnsynced),
            normalizeKeepAwakeDurationMs(
                prefs.safeLong(KEEP_AWAKE_DURATION_MS, DEFAULTS.keepAwakeDurationMs)
            ),
            prefs.safeBoolean(
                EXPERIMENTAL_POSITION_FOLLOWING,
                DEFAULTS.experimentalPositionFollowing
            ),
            normalizeAodBurnInPattern(prefs.safeString(BURN_IN_PATTERN, DEFAULTS.burnInPattern)),
            normalizeAodBurnInInterval(
                prefs.safeLong(BURN_IN_INTERVAL_MS, DEFAULTS.burnInIntervalMs)
            ),
            prefs.safeBoolean(SUPPRESS_STOCK_AOD_CONTENT, DEFAULTS.suppressStockAodContent),
            prefs.safeBoolean(AOD_ROTATE_WITH_DEVICE, DEFAULTS.aodRotateWithDevice),
            normalizeAodRotationMode(
                prefs.safeString(AOD_ROTATION_MODE, null)
                    ?: if (prefs.safeBoolean(AOD_ROTATE_WITH_DEVICE, false)) {
                        AOD_ROTATION_MODE_AUTO
                    } else {
                        DEFAULTS.aodRotationMode
                    }
            ),
            normalizeAodCanvasAnchor(
                prefs.safeFloat(AOD_CANVAS_ANCHOR, DEFAULTS.aodCanvasAnchor)
            ),
            normalizeAodRotationSettleMs(
                prefs.safeLong(AOD_ROTATION_SETTLE_MS, DEFAULTS.aodRotationSettleMs)
            ),
            normalizeAodCanvasAnchor(
                prefs.safeFloat(AOD_CANVAS_ANCHOR_LANDSCAPE, DEFAULTS.aodCanvasAnchorLandscape)
            ),
            normalizeAodLandscapeTextScale(
                prefs.safeFloat(AOD_LANDSCAPE_TEXT_SCALE, DEFAULTS.aodLandscapeTextScale)
            ),
            migratedPaddingPercent(
                prefs,
                AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT,
                AOD_CANVAS_PADDING_X_PERCENT,
                DEFAULTS.aodCanvasPaddingPortraitXPercent
            ),
            migratedPaddingPercent(
                prefs,
                AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT,
                AOD_CANVAS_PADDING_Y_PERCENT,
                DEFAULTS.aodCanvasPaddingPortraitYPercent
            ),
            migratedPaddingPercent(
                prefs,
                AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT,
                AOD_CANVAS_PADDING_X_PERCENT,
                DEFAULTS.aodCanvasPaddingLandscapeXPercent
            ),
            migratedPaddingPercent(
                prefs,
                AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT,
                AOD_CANVAS_PADDING_Y_PERCENT,
                DEFAULTS.aodCanvasPaddingLandscapeYPercent
            ),
            normalizePauseLingerMs(prefs.safeLong(PAUSE_LINGER_MS, DEFAULTS.pauseLingerMs)),
            prefs.safeBoolean(LOCKSCREEN_KEEP_AWAKE, DEFAULTS.lockscreenKeepAwake),
            prefs.safeBoolean(RAISE_TO_AOD, DEFAULTS.raiseToAod),
            prefs.safeBoolean(
                SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS,
                DEFAULTS.suppressLockscreenEditorLongPress
            ),
            prefs.safeBoolean(SONG_CHANGE_INFO_ENABLED, DEFAULTS.songChangeInfoEnabled),
            prefs.safeBoolean(HIDE_LAUNCHER_ICON, DEFAULTS.hideLauncherIcon),
            prefs.safeBoolean(HIDE_FROM_RECENTS, DEFAULTS.hideFromRecents),
            prefs.safeBoolean(DUET_ENABLED, DEFAULTS.duetEnabled),
            prefs.safeBoolean(OVERRIDE_BRIGHTNESS, DEFAULTS.aodBrightnessOverride),
            prefs.safeInt(AOD_BRIGHTNESS, DEFAULTS.aodBrightnessLevel).coerceIn(
                MIN_AOD_BRIGHTNESS,
                MAX_AOD_BRIGHTNESS
            )
        ).also { cachedConfig = it }
    }

}
