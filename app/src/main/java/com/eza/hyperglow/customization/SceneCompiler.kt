package com.eza.hyperglow.customization

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object SceneCompiler {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun compile(document: CustomizationDocument): CompiledCustomization {
        val source = if (document.version == CURRENT_CUSTOMIZATION_VERSION) {
            document
        } else {
            safeDefaultDocument()
        }
        val lockscreenSource = source.profiles[SURFACE_LOCKSCREEN] ?: safeLockscreenProfile()
        val aodSource = source.profiles[SURFACE_AOD] ?: safeAodProfile()
        val linkedBase = aodSource.takeIf { source.linkSurfaces }
        val lockscreen = compileProfile(
            SURFACE_LOCKSCREEN,
            linkedBase?.copy(
                enabled = lockscreenSource.enabled,
                collisionPolicy = lockscreenSource.collisionPolicy,
                widgets = lockscreenSource.widgets,
                metadataVisible = lockscreenSource.metadataVisible,
                backgroundStyle = lockscreenSource.backgroundStyle
            ) ?: lockscreenSource
        )
        val aod = compileProfile(SURFACE_AOD, aodSource)
        val base = CompiledCustomization(
            version = CURRENT_CUSTOMIZATION_VERSION,
            revision = 0L,
            hash = "",
            sourceId = normalizeId(source.id),
            linkSurfaces = source.linkSurfaces,
            profiles = linkedMapOf(SURFACE_LOCKSCREEN to lockscreen, SURFACE_AOD to aod)
        )
        return finalizeCompiled(base) ?: compileSafeDefault()
    }

    fun decodeDocument(raw: String): CustomizationDocument? {
        if (raw.toByteArray().size > MAX_CONFIG_BYTES) return null
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (containsUnsafeSchemaElement(element)) return null
        return runCatching { json.decodeFromString<CustomizationDocument>(raw) }.getOrNull()
    }

    internal fun finalizeCompiled(configuration: CompiledCustomization): CompiledCustomization? {
        val orderedProfiles = linkedMapOf<String, CompiledSurfaceProfile>()
        configuration.profiles[SURFACE_LOCKSCREEN]?.let { orderedProfiles[SURFACE_LOCKSCREEN] = it }
        configuration.profiles[SURFACE_AOD]?.let { orderedProfiles[SURFACE_AOD] = it }
        val base = configuration.copy(
            version = CURRENT_CUSTOMIZATION_VERSION,
            revision = 0L,
            hash = "",
            profiles = orderedProfiles
        )
        val encoded = json.encodeToString(base)
        if (encoded.toByteArray().size > MAX_CONFIG_BYTES) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
        return base.copy(
            revision = ByteBuffer.wrap(digest).long and Long.MAX_VALUE,
            hash = digest.joinToString("") { "%02x".format(it) }
        )
    }

    fun safeDefaultDocument(): CustomizationDocument = CustomizationDocument(
        profiles = linkedMapOf(
            SURFACE_LOCKSCREEN to safeLockscreenProfile(),
            SURFACE_AOD to safeAodProfile()
        )
    )

    fun safeLockscreenProfile(): SurfaceProfile = SurfaceProfile(enabled = false)

    fun safeAodProfile(): SurfaceProfile = SurfaceProfile(
        enabled = true,
        maxHeightFraction = 0.42f,
        widgets = listOf(WidgetSpec("lyrics"))
    )

    private fun compileSafeDefault(): CompiledCustomization {
        val safe = safeDefaultDocument()
        val lockscreen = compileProfile(SURFACE_LOCKSCREEN, safe.profiles.getValue(SURFACE_LOCKSCREEN))
        val aod = compileProfile(SURFACE_AOD, safe.profiles.getValue(SURFACE_AOD))
        val base = CompiledCustomization(
            CURRENT_CUSTOMIZATION_VERSION,
            1L,
            "safe",
            safe.id,
            safe.linkSurfaces,
            linkedMapOf(SURFACE_LOCKSCREEN to lockscreen, SURFACE_AOD to aod)
        )
        return finalizeCompiled(base) ?: error("Safe customization exceeds hard limit")
    }

    private fun compileProfile(surface: String, profile: SurfaceProfile): CompiledSurfaceProfile {
        val aod = surface == SURFACE_AOD
        val supportedWidgets = profile.widgets.asSequence()
            .filter { it.visible }
            .filter { it.type in KNOWN_WIDGETS }
            .filter { !aod || it.type in AOD_WIDGETS }
            .take(if (aod) MAX_AOD_WIDGETS else MAX_WIDGETS)
            .map { it.copy(style = normalizeId(it.style)) }
            .toMutableList()
        if (supportedWidgets.none { it.type == "lyrics" }) {
            if (supportedWidgets.size >= if (aod) MAX_AOD_WIDGETS else MAX_WIDGETS) {
                supportedWidgets.removeLast()
            }
            supportedWidgets.add(0, WidgetSpec("lyrics"))
        }
        val palette = profile.palette.asSequence()
            .filter { it.key in SEMANTIC_COLORS && it.value in PALETTE_VALUES }
            .take(SEMANTIC_COLORS.size)
            .associate { it.key to it.value }
        return CompiledSurfaceProfile(
            surface = surface,
            enabled = profile.enabled,
            anchor = profile.anchor.takeIf { it in ANCHORS } ?: "below_stock_clock",
            widthFraction = profile.widthFraction.coerceIn(0.4f, 1f),
            maxHeightFraction = profile.maxHeightFraction.coerceIn(
                0.15f,
                if (aod) 0.5f else 0.8f
            ),
            verticalBias = profile.verticalBias.coerceIn(0f, 1f),
            collisionPolicy = profile.collisionPolicy.takeIf { it in COLLISION_POLICIES } ?: "avoid",
            widgets = supportedWidgets,
            transition = profile.transition.copy(
                id = profile.transition.id.takeIf { it in TRANSITIONS } ?: "continuity",
                durationMs = profile.transition.durationMs.coerceIn(150, 600),
                easing = profile.transition.easing.takeIf { it in EASINGS } ?: "fast_out_slow_in"
            ),
            alignment = profile.alignment.takeIf { it in ALIGNMENTS } ?: "auto",
            secondaryMode = profile.secondaryMode.takeIf { it in SECONDARY_MODES } ?: "Main only",
            secondaryTextBright = profile.secondaryTextBright,
            lyricLineLimit = normalizeLyricLineLimit(profile.lyricLineLimit),
            metadataVisible = profile.metadataVisible &&
                supportedWidgets.any { it.type == "metadata" },
            metadataAnchor = if (profile.metadataAnchor == "bottom") "bottom" else "top",
            metadataSizePercent = profile.metadataSizePercent.coerceIn(50, 200),
            rubyVisible = profile.rubyVisible,
            weight = profile.weight.takeIf { it in WEIGHTS } ?: "Medium",
            textSize = profile.textSize.takeIf { it in TEXT_SIZES } ?: "normal",
            textSizeCustom = profile.textSizeCustom.coerceIn(50, 200),
            fontFamily = profile.fontFamily.takeIf { it in FONT_FAMILIES } ?: "spotify",
            animation = when {
                aod && profile.animation != "Minimal" -> "Gradient"
                profile.animation in ANIMATIONS -> profile.animation
                else -> "Gradient"
            },
            glow = if (profile.glow == "On") "On" else "Off",
            lineSyncFillMode = normalizeLineSyncFillMode(profile.lineSyncFillMode),
            overflow = if (profile.overflow == "Clip") "Clip" else "Wrap",
            adaptiveSectioning = profile.adaptiveSectioning,
            palette = palette,
            backgroundStyle = when {
                aod -> "none"
                profile.backgroundStyle == "none" -> "none"
                else -> "card"
            }
        )
    }

    private fun normalizeId(value: String): String = value
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(64)
        .ifBlank { "default" }

    private fun containsUnsafeSchemaElement(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            val normalizedKey = key.lowercase()
            FORBIDDEN_SCHEMA_KEYS.any(normalizedKey::contains) ||
                containsUnsafeSchemaElement(value)
        }
        is JsonArray -> element.any(::containsUnsafeSchemaElement)
        is JsonPrimitive -> element.contentOrNull?.let(::containsUnsafeSchemaValue) == true
    }

    private fun containsUnsafeSchemaValue(value: String): Boolean {
        val trimmed = value.trim()
        val lower = trimmed.lowercase()
        return value.indexOf('\u0000') >= 0 ||
            "://" in lower ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("/") ||
            "../" in lower ||
            "..\\" in lower ||
            "\\" in value ||
            COMMAND_PREFIXES.any { lower == it || lower.startsWith("$it ") }
    }

    const val MAX_CONFIG_BYTES = 64 * 1024
    const val TARGET_CONFIG_BYTES = 32 * 1024
    const val MAX_WIDGETS = 8
    const val MAX_AOD_WIDGETS = 4
    const val SURFACE_LOCKSCREEN = "lockscreen"
    const val SURFACE_AOD = "aod"

    val KNOWN_WIDGETS = setOf("lyrics", "metadata", "media_progress")
    private val AOD_WIDGETS = setOf("lyrics", "metadata")
    private val ANCHORS = setOf(
        "below_stock_clock",
        "screen_center",
        "screen_top_safe",
        "screen_bottom_safe",
        "custom_vertical_bias"
    )
    private val COLLISION_POLICIES = setOf(
        "avoid",
        "behind_system",
        "hide_optional",
        "hide_scene"
    )
    private val TRANSITIONS = setOf("continuity", "crossfade", "none")
    private val EASINGS = setOf("fast_out_slow_in", "linear", "ease_out")
    private val ALIGNMENTS = setOf("auto", "start", "center", "end")
    private val SECONDARY_MODES = setOf("Main only", "Transliteration", "Translation", "Both")
    private val WEIGHTS = setOf("Regular", "Medium", "Bold")
    private val TEXT_SIZES = setOf("small", "normal", "large", "xlarge", "custom")
    private val FONT_FAMILIES = setOf("noto", "spotify", "apple")
    private val ANIMATIONS = setOf("Minimal", "Gradient")
    private fun normalizeLineSyncFillMode(value: String): String = when (value) {
        "None",
        "Top to bottom",
        "Left to right (whole block)" -> value
        else -> "Left to right (main only)"
    }
    private val SEMANTIC_COLORS = setOf(
        "primaryText",
        "secondaryText",
        "metadataText",
        "sungText",
        "unsungText",
        "glow",
        "accent",
        "surfaceScrim"
    )
    private val PALETTE_VALUES = setOf("default", "clock", "wallpaper", "white", "dimmed")
    private val FORBIDDEN_SCHEMA_KEYS = setOf(
        "class",
        "resource",
        "method",
        "path",
        "url",
        "uri",
        "command",
        "shell",
        "script",
        "intent",
        "component"
    )
    private val COMMAND_PREFIXES = setOf(
        "sh",
        "bash",
        "zsh",
        "cmd",
        "su",
        "exec",
        "am",
        "pm",
        "rm",
        "curl",
        "wget",
        "adb"
    )
}
