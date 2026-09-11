package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderConfig.Companion.DEFAULTS
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.aod.normalizeAodCanvasAnchor
import com.eza.hyperglow.aod.legacyPaddingDpToPercent
import com.eza.hyperglow.aod.normalizeAodCanvasPaddingPercent
import com.eza.hyperglow.aod.normalizeAodLandscapeTextScale
import com.eza.hyperglow.aod.normalizeAodRotationMode
import com.eza.hyperglow.aod.normalizeAodRotationSettleMs
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Why typed: the fork's backup guessed every value's type from the JSON primitive and tested
 * intOrNull before longOrNull, so every Long preference whose value fits in an Int came back as an
 * Int and the next [AodRenderPreferences.read] threw ClassCastException on the startup path. Here
 * each key has exactly one declared type; a value of any other shape is dropped to that key's
 * default, and unknown keys are ignored, never written.
 */
internal sealed interface ConfigBackupDecodeResult {
    data class Success(
        val preferences: AodRenderConfig,
        val customizationDocument: CustomizationDocument?
    ) : ConfigBackupDecodeResult

    /** The payload is refused whole; a rejected import never partially applies state. */
    data class Rejected(val reason: ConfigBackupRejection) : ConfigBackupDecodeResult
}

internal enum class ConfigBackupRejection {
    OVERSIZE,
    BAD_FORMAT,
    BAD_VERSION,
    MALFORMED
}

internal data class BackupBooleanField(val key: String, val read: (AodRenderConfig) -> Boolean)

internal data class BackupFloatField(val key: String, val read: (AodRenderConfig) -> Float)

internal data class BackupIntField(val key: String, val read: (AodRenderConfig) -> Int)

internal data class BackupLongField(val key: String, val read: (AodRenderConfig) -> Long)

internal data class BackupStringField(val key: String, val read: (AodRenderConfig) -> String)

internal object ConfigBackupCodec {
    const val FORMAT = "hyperglow-config-backup"
    const val VERSION = 1
    const val MAX_BYTES = 512 * 1024

    internal val booleanFields = listOf(
        BackupBooleanField(AodRenderPreferences.AOD_ENABLED) { it.aodEnabled },
        BackupBooleanField(AodRenderPreferences.LOCKSCREEN_ENABLED) { it.lockscreenEnabled },
        BackupBooleanField(AodRenderPreferences.SEAMLESS_TRANSITION_ENABLED) {
            it.seamlessTransitionEnabled
        },
        BackupBooleanField(AodRenderPreferences.ADAPTIVE_SECTIONING) { it.adaptiveSectioning },
        BackupBooleanField(AodRenderPreferences.KEEP_AWAKE) { it.keepAwake },
        BackupBooleanField(AodRenderPreferences.KEEP_AWAKE_UNSYNCED) { it.keepAwakeUnsynced },
        BackupBooleanField(AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING) {
            it.experimentalPositionFollowing
        },
        BackupBooleanField(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE) { it.lockscreenKeepAwake },
        BackupBooleanField(AodRenderPreferences.RAISE_TO_AOD) { it.raiseToAod },
        BackupBooleanField(AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS) {
            it.suppressLockscreenEditorLongPress
        },
        BackupBooleanField(AodRenderPreferences.SONG_CHANGE_INFO_ENABLED) {
            it.songChangeInfoEnabled
        },
        BackupBooleanField(AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT) {
            it.suppressStockAodContent
        },
        BackupBooleanField(AodRenderPreferences.DUET_ENABLED) {
            it.duetEnabled
        },
        BackupBooleanField(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE) {
            it.aodRotateWithDevice
        },
        BackupBooleanField(AodRenderPreferences.HIDE_LAUNCHER_ICON) { it.hideLauncherIcon },
        BackupBooleanField(AodRenderPreferences.HIDE_FROM_RECENTS) { it.hideFromRecents },
        BackupBooleanField(AodRenderPreferences.OVERRIDE_BRIGHTNESS) { it.aodBrightnessOverride }
    )

    internal val intFields = listOf(
        BackupIntField(AodRenderPreferences.TEXT_SIZE_CUSTOM) { it.textSizeCustom },
        BackupIntField(AodRenderPreferences.AOD_BRIGHTNESS) { it.aodBrightnessLevel }
    )

    internal val floatFields = listOf(
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_ANCHOR) { it.aodCanvasAnchor },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_ANCHOR_LANDSCAPE) {
            it.aodCanvasAnchorLandscape
        },
        BackupFloatField(AodRenderPreferences.AOD_LANDSCAPE_TEXT_SCALE) {
            it.aodLandscapeTextScale
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT) {
            it.aodCanvasPaddingPortraitXPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT) {
            it.aodCanvasPaddingPortraitYPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT) {
            it.aodCanvasPaddingLandscapeXPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT) {
            it.aodCanvasPaddingLandscapeYPercent
        }
    )

    internal val longFields = listOf(
        BackupLongField(AodRenderPreferences.KEEP_AWAKE_DURATION_MS) { it.keepAwakeDurationMs },
        BackupLongField(AodRenderPreferences.BURN_IN_INTERVAL_MS) { it.burnInIntervalMs },
        BackupLongField(AodRenderPreferences.PAUSE_LINGER_MS) { it.pauseLingerMs },
        BackupLongField(AodRenderPreferences.AOD_ROTATION_SETTLE_MS) { it.aodRotationSettleMs }
    )

    internal val stringFields = listOf(
        BackupStringField(AodRenderPreferences.ALIGNMENT) { it.alignment },
        BackupStringField(AodRenderPreferences.SECONDARY) { it.secondaryMode },
        BackupStringField(AodRenderPreferences.OVERFLOW) { it.overflowMode },
        BackupStringField(AodRenderPreferences.METADATA_VISIBLE) { it.metadataVisible },
        BackupStringField(AodRenderPreferences.METADATA_ANCHOR) { it.metadataAnchor },
        BackupStringField(AodRenderPreferences.WEIGHT) { it.weight },
        BackupStringField(AodRenderPreferences.TEXT_SIZE) { it.textSize },
        BackupStringField(AodRenderPreferences.FONT_FAMILY) { it.fontFamily },
        BackupStringField(AodRenderPreferences.ANIMATION) { it.animation },
        BackupStringField(AodRenderPreferences.GLOW) { it.glow },
        BackupStringField(AodRenderPreferences.BURN_IN_PATTERN) { it.burnInPattern },
        BackupStringField(AodRenderPreferences.AOD_ROTATION_MODE) { it.aodRotationMode }
    )

    fun encode(preferences: AodRenderConfig, document: CustomizationDocument?): String {
        val root = buildJsonObject {
            put(FORMAT_KEY, FORMAT)
            put(VERSION_KEY, VERSION)
            put(PREFERENCES_KEY, buildJsonObject {
                booleanFields.forEach { put(it.key, it.read(preferences)) }
                intFields.forEach { put(it.key, it.read(preferences)) }
                floatFields.forEach { put(it.key, it.read(preferences)) }
                longFields.forEach { put(it.key, it.read(preferences)) }
                stringFields.forEach { put(it.key, it.read(preferences)) }
            })
            document?.let { put(CUSTOMIZATION_KEY, SceneCompiler.json.encodeToJsonElement(it)) }
        }
        return root.toString()
    }

    fun decode(payload: ByteArray): ConfigBackupDecodeResult {
        // Refuse size before spending a parse on the payload.
        if (payload.size > MAX_BYTES) return ConfigBackupDecodeResult.Rejected(
            ConfigBackupRejection.OVERSIZE
        )
        val envelope = runCatching {
            SceneCompiler.json.parseToJsonElement(payload.decodeToString())
        }.getOrNull() as? JsonObject ?: return ConfigBackupDecodeResult.Rejected(
            ConfigBackupRejection.MALFORMED
        )

        val format = (envelope[FORMAT_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) {
            return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_FORMAT)
        }
        if ((envelope[VERSION_KEY] as? JsonPrimitive)?.intOrNull != VERSION) {
            return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION)
        }

        val stored = envelope[PREFERENCES_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val preferences = decodePreferences(stored)

        val document = when (val rawCustomization = envelope[CUSTOMIZATION_KEY]) {
            null -> null
            is JsonObject -> SceneCompiler.decodeDocument(rawCustomization.toString())
                ?: return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED)
            else -> return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED)
        }

        return ConfigBackupDecodeResult.Success(preferences, document)
    }

    private fun decodePreferences(stored: JsonObject): AodRenderConfig = AodRenderConfig(
            aodEnabled = stored.boolean(AodRenderPreferences.AOD_ENABLED) ?: DEFAULTS.aodEnabled,
            lockscreenEnabled = stored.boolean(AodRenderPreferences.LOCKSCREEN_ENABLED)
                ?: DEFAULTS.lockscreenEnabled,
            seamlessTransitionEnabled = stored.boolean(
                AodRenderPreferences.SEAMLESS_TRANSITION_ENABLED
            ) ?: DEFAULTS.seamlessTransitionEnabled,
            alignment = stored.string(AodRenderPreferences.ALIGNMENT) ?: DEFAULTS.alignment,
            secondaryMode = stored.string(AodRenderPreferences.SECONDARY) ?: DEFAULTS.secondaryMode,
            overflowMode = stored.string(AodRenderPreferences.OVERFLOW) ?: DEFAULTS.overflowMode,
            metadataVisible = stored.string(AodRenderPreferences.METADATA_VISIBLE)
                ?: DEFAULTS.metadataVisible,
            metadataAnchor = stored.string(AodRenderPreferences.METADATA_ANCHOR)
                ?: DEFAULTS.metadataAnchor,
            weight = stored.string(AodRenderPreferences.WEIGHT) ?: DEFAULTS.weight,
            textSize = stored.string(AodRenderPreferences.TEXT_SIZE) ?: DEFAULTS.textSize,
            textSizeCustom = (stored.int(AodRenderPreferences.TEXT_SIZE_CUSTOM)
                ?: DEFAULTS.textSizeCustom).coerceIn(50, com.eza.hyperglow.customization.MAX_LYRIC_TEXT_SIZE_PERCENT),
            fontFamily = stored.string(AodRenderPreferences.FONT_FAMILY) ?: DEFAULTS.fontFamily,
            animation = stored.string(AodRenderPreferences.ANIMATION) ?: DEFAULTS.animation,
            glow = stored.string(AodRenderPreferences.GLOW) ?: DEFAULTS.glow,
            adaptiveSectioning = stored.boolean(AodRenderPreferences.ADAPTIVE_SECTIONING)
                ?: DEFAULTS.adaptiveSectioning,
            keepAwake = stored.boolean(AodRenderPreferences.KEEP_AWAKE) ?: DEFAULTS.keepAwake,
            keepAwakeUnsynced = stored.boolean(AodRenderPreferences.KEEP_AWAKE_UNSYNCED)
                ?: DEFAULTS.keepAwakeUnsynced,
            keepAwakeDurationMs = stored.long(AodRenderPreferences.KEEP_AWAKE_DURATION_MS)
                ?: DEFAULTS.keepAwakeDurationMs,
            experimentalPositionFollowing = stored.boolean(
                AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING
            ) ?: DEFAULTS.experimentalPositionFollowing,
            burnInPattern = stored.string(AodRenderPreferences.BURN_IN_PATTERN)
                ?: DEFAULTS.burnInPattern,
            burnInIntervalMs = stored.long(AodRenderPreferences.BURN_IN_INTERVAL_MS)
                ?: DEFAULTS.burnInIntervalMs,
            pauseLingerMs = stored.long(AodRenderPreferences.PAUSE_LINGER_MS)
                ?: DEFAULTS.pauseLingerMs,
            aodRotationSettleMs = stored.long(AodRenderPreferences.AOD_ROTATION_SETTLE_MS)
                ?.let(::normalizeAodRotationSettleMs) ?: DEFAULTS.aodRotationSettleMs,
            lockscreenKeepAwake = stored.boolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE)
                ?: DEFAULTS.lockscreenKeepAwake,
            raiseToAod = stored.boolean(AodRenderPreferences.RAISE_TO_AOD) ?: DEFAULTS.raiseToAod,
            suppressLockscreenEditorLongPress = stored.boolean(
                AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS
            ) ?: DEFAULTS.suppressLockscreenEditorLongPress,
            songChangeInfoEnabled = stored.boolean(AodRenderPreferences.SONG_CHANGE_INFO_ENABLED)
                ?: DEFAULTS.songChangeInfoEnabled,
            suppressStockAodContent = stored.boolean(
                AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT
            ) ?: DEFAULTS.suppressStockAodContent,
            duetEnabled = stored.boolean(AodRenderPreferences.DUET_ENABLED)
                ?: DEFAULTS.duetEnabled,
            aodBrightnessOverride = stored.boolean(AodRenderPreferences.OVERRIDE_BRIGHTNESS)
                ?: DEFAULTS.aodBrightnessOverride,
            aodBrightnessLevel = (stored.int(AodRenderPreferences.AOD_BRIGHTNESS)
                ?: DEFAULTS.aodBrightnessLevel).coerceIn(
                com.eza.hyperglow.customization.MIN_AOD_BRIGHTNESS,
                com.eza.hyperglow.customization.MAX_AOD_BRIGHTNESS
            ),
            aodRotateWithDevice = stored.boolean(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE)
                ?: DEFAULTS.aodRotateWithDevice,
            aodRotationMode = stored.string(AodRenderPreferences.AOD_ROTATION_MODE)
                ?.let(::normalizeAodRotationMode)
                ?: if (stored.boolean(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE) == true) {
                    AOD_ROTATION_MODE_AUTO
                } else {
                    DEFAULTS.aodRotationMode
                },
            aodCanvasAnchor = stored.float(AodRenderPreferences.AOD_CANVAS_ANCHOR)
                ?.let(::normalizeAodCanvasAnchor) ?: DEFAULTS.aodCanvasAnchor,
            aodCanvasAnchorLandscape = stored.float(
                AodRenderPreferences.AOD_CANVAS_ANCHOR_LANDSCAPE
            )?.let(::normalizeAodCanvasAnchor) ?: DEFAULTS.aodCanvasAnchorLandscape,
            aodLandscapeTextScale = stored.float(AodRenderPreferences.AOD_LANDSCAPE_TEXT_SCALE)
                ?.let(::normalizeAodLandscapeTextScale) ?: DEFAULTS.aodLandscapeTextScale,
            aodCanvasPaddingPortraitXPercent = stored.float(
                AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT
            )?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.float(AodRenderPreferences.AOD_CANVAS_PADDING_X_PERCENT)
                    ?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.int(AodRenderPreferences.AOD_CANVAS_PADDING_DP)
                    ?.let(::legacyPaddingDpToPercent)
                ?: DEFAULTS.aodCanvasPaddingPortraitXPercent,
            aodCanvasPaddingPortraitYPercent = stored.float(
                AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT
            )?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.float(AodRenderPreferences.AOD_CANVAS_PADDING_Y_PERCENT)
                    ?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.int(AodRenderPreferences.AOD_CANVAS_PADDING_DP)
                    ?.let(::legacyPaddingDpToPercent)
                ?: DEFAULTS.aodCanvasPaddingPortraitYPercent,
            aodCanvasPaddingLandscapeXPercent = stored.float(
                AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT
            )?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.float(AodRenderPreferences.AOD_CANVAS_PADDING_X_PERCENT)
                    ?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.int(AodRenderPreferences.AOD_CANVAS_PADDING_DP)
                    ?.let(::legacyPaddingDpToPercent)
                ?: DEFAULTS.aodCanvasPaddingLandscapeXPercent,
            aodCanvasPaddingLandscapeYPercent = stored.float(
                AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT
            )?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.float(AodRenderPreferences.AOD_CANVAS_PADDING_Y_PERCENT)
                    ?.let(::normalizeAodCanvasPaddingPercent)
                ?: stored.int(AodRenderPreferences.AOD_CANVAS_PADDING_DP)
                    ?.let(::legacyPaddingDpToPercent)
                ?: DEFAULTS.aodCanvasPaddingLandscapeYPercent,
            hideLauncherIcon = stored.boolean(AodRenderPreferences.HIDE_LAUNCHER_ICON)
                ?: DEFAULTS.hideLauncherIcon,
            hideFromRecents = stored.boolean(AodRenderPreferences.HIDE_FROM_RECENTS)
                ?: DEFAULTS.hideFromRecents
        )

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.floatOrNull

    /** String keys accept only JSON strings; the fork collapsed numeric strings into numbers. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "version"
    private const val PREFERENCES_KEY = "renderPreferences"
    private const val CUSTOMIZATION_KEY = "customization"
}
