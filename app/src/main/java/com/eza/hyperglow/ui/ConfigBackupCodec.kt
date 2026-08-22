package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
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
        BackupBooleanField(AodRenderPreferences.HIDE_LAUNCHER_ICON) { it.hideLauncherIcon },
        BackupBooleanField(AodRenderPreferences.HIDE_FROM_RECENTS) { it.hideFromRecents }
    )

    internal val intFields = listOf(
        BackupIntField(AodRenderPreferences.TEXT_SIZE_CUSTOM) { it.textSizeCustom }
    )

    internal val longFields = listOf(
        BackupLongField(AodRenderPreferences.KEEP_AWAKE_DURATION_MS) { it.keepAwakeDurationMs },
        BackupLongField(AodRenderPreferences.BURN_IN_INTERVAL_MS) { it.burnInIntervalMs },
        BackupLongField(AodRenderPreferences.PAUSE_LINGER_MS) { it.pauseLingerMs }
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
        BackupStringField(AodRenderPreferences.BURN_IN_PATTERN) { it.burnInPattern }
    )

    fun encode(preferences: AodRenderConfig, document: CustomizationDocument?): String {
        val root = buildJsonObject {
            put(FORMAT_KEY, FORMAT)
            put(VERSION_KEY, VERSION)
            put(PREFERENCES_KEY, buildJsonObject {
                booleanFields.forEach { put(it.key, it.read(preferences)) }
                intFields.forEach { put(it.key, it.read(preferences)) }
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
            aodEnabled = stored.boolean(AodRenderPreferences.AOD_ENABLED) ?: true,
            lockscreenEnabled = stored.boolean(AodRenderPreferences.LOCKSCREEN_ENABLED) ?: false,
            alignment = stored.string(AodRenderPreferences.ALIGNMENT) ?: "auto",
            secondaryMode = stored.string(AodRenderPreferences.SECONDARY) ?: "Main only",
            overflowMode = stored.string(AodRenderPreferences.OVERFLOW) ?: "Wrap",
            metadataVisible = stored.string(AodRenderPreferences.METADATA_VISIBLE) ?: "hide",
            metadataAnchor = stored.string(AodRenderPreferences.METADATA_ANCHOR) ?: "top",
            weight = stored.string(AodRenderPreferences.WEIGHT) ?: "Medium",
            textSize = stored.string(AodRenderPreferences.TEXT_SIZE) ?: "normal",
            textSizeCustom =
                (stored.int(AodRenderPreferences.TEXT_SIZE_CUSTOM) ?: 100).coerceIn(50, 200),
            fontFamily = stored.string(AodRenderPreferences.FONT_FAMILY) ?: "spotify",
            animation = stored.string(AodRenderPreferences.ANIMATION) ?: "Gradient",
            glow = stored.string(AodRenderPreferences.GLOW) ?: "Off",
            adaptiveSectioning = stored.boolean(AodRenderPreferences.ADAPTIVE_SECTIONING) ?: true,
            keepAwake = stored.boolean(AodRenderPreferences.KEEP_AWAKE) ?: true,
            keepAwakeUnsynced = stored.boolean(AodRenderPreferences.KEEP_AWAKE_UNSYNCED) ?: false,
            keepAwakeDurationMs =
                stored.long(AodRenderPreferences.KEEP_AWAKE_DURATION_MS) ?: -1L,
            experimentalPositionFollowing =
                stored.boolean(AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING) ?: false,
            burnInPattern = stored.string(AodRenderPreferences.BURN_IN_PATTERN)
                ?: "static_bottom",
            burnInIntervalMs = stored.long(AodRenderPreferences.BURN_IN_INTERVAL_MS) ?: 60_000L,
            pauseLingerMs = stored.long(AodRenderPreferences.PAUSE_LINGER_MS) ?: 5_000L,
            lockscreenKeepAwake = stored.boolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE)
                ?: false,
            raiseToAod = stored.boolean(AodRenderPreferences.RAISE_TO_AOD) ?: false,
            suppressLockscreenEditorLongPress =
                stored.boolean(AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS)
                    ?: false,
            songChangeInfoEnabled = stored.boolean(AodRenderPreferences.SONG_CHANGE_INFO_ENABLED)
                ?: true,
            hideLauncherIcon = stored.boolean(AodRenderPreferences.HIDE_LAUNCHER_ICON) ?: false,
            hideFromRecents = stored.boolean(AodRenderPreferences.HIDE_FROM_RECENTS) ?: false
        )

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    /** String keys accept only JSON strings; the fork collapsed numeric strings into numbers. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "version"
    private const val PREFERENCES_KEY = "renderPreferences"
    private const val CUSTOMIZATION_KEY = "customization"
}
