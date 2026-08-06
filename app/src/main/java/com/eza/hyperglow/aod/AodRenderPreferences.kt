package com.eza.hyperglow.aod

import android.content.Context
import android.content.SharedPreferences

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
    val pauseLingerMs: Long = 5_000L,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false,
    val suppressLockscreenEditorLongPress: Boolean = false,
    val songChangeInfoEnabled: Boolean = true
)

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
    const val PAUSE_LINGER_MS = "pause_linger_ms"
    const val LOCKSCREEN_KEEP_AWAKE = "lockscreen_keep_awake"
    const val RAISE_TO_AOD = "raise_to_aod"
    const val SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS = "suppress_lockscreen_editor_long_press"

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
            prefs.getBoolean(AOD_ENABLED, true),
            prefs.getBoolean(LOCKSCREEN_ENABLED, false),
            true,
            normalizeAodAlignment(prefs.getString(ALIGNMENT, "auto")),
            normalizeAodSecondary(prefs.getString(SECONDARY, "Main only")),
            normalizeAodOverflow(prefs.getString(OVERFLOW, "Wrap")),
            normalizeAodMetadataVisible(prefs.getString(METADATA_VISIBLE, "hide")),
            normalizeAodMetadataAnchor(prefs.getString(METADATA_ANCHOR, "top")),
            normalizeAodWeight(prefs.getString(WEIGHT, "Medium")),
            normalizeAodTextSize(prefs.getString(TEXT_SIZE, "normal")),
            prefs.getInt(TEXT_SIZE_CUSTOM, 100).coerceIn(50, 200),
            normalizeAodFontFamily(prefs.getString(FONT_FAMILY, "spotify")),
            normalizeAodAnimation(prefs.getString(ANIMATION, "Gradient")),
            normalizeAodGlow(prefs.getString(GLOW, "Off")),
            prefs.getBoolean(ADAPTIVE_SECTIONING, true),
            prefs.getBoolean(KEEP_AWAKE, true),
            prefs.getBoolean(KEEP_AWAKE_UNSYNCED, false),
            normalizeKeepAwakeDurationMs(prefs.getLong(KEEP_AWAKE_DURATION_MS, -1L)),
            prefs.getBoolean(EXPERIMENTAL_POSITION_FOLLOWING, false),
            normalizeAodBurnInPattern(prefs.getString(BURN_IN_PATTERN, "static_bottom")),
            normalizeAodBurnInInterval(prefs.getLong(BURN_IN_INTERVAL_MS, 60_000L)),
            normalizePauseLingerMs(prefs.getLong(PAUSE_LINGER_MS, 5_000L)),
            prefs.getBoolean(LOCKSCREEN_KEEP_AWAKE, false),
            prefs.getBoolean(RAISE_TO_AOD, false),
            prefs.getBoolean(SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS, false),
            prefs.getBoolean(SONG_CHANGE_INFO_ENABLED, true)
        ).also { cachedConfig = it }
    }
}
