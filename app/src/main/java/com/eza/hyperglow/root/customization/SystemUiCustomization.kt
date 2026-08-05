package com.eza.hyperglow.root.customization

import android.os.Bundle
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.WidgetSpec
import com.eza.hyperglow.customization.normalizeCardAlpha
import com.eza.hyperglow.customization.normalizeCardColor
import com.eza.hyperglow.customization.normalizeLyricLineLimit
import com.eza.hyperglow.aod.normalizePauseLingerMs
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.surface.SurfacePolicyResolver
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal enum class WidgetRendererId {
    LYRICS,
    METADATA,
    MEDIA_PROGRESS,
    ARTWORK_ACCENT,
    STATUS_TEXT,
    SPACER,
    DIVIDER
}

internal object WidgetRendererRegistry {
    fun renderer(type: String): WidgetRendererId? = when (type) {
        "lyrics" -> WidgetRendererId.LYRICS
        "metadata" -> WidgetRendererId.METADATA
        "media_progress" -> WidgetRendererId.MEDIA_PROGRESS
        else -> null
    }
}

internal object SystemUiCustomizationValidator {
    fun validate(configuration: CompiledCustomization): CompiledCustomization? {
        if (configuration.version != com.eza.hyperglow.customization.CURRENT_CUSTOMIZATION_VERSION) {
            return null
        }
        val safe = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val rawAod = configuration.profiles[SceneCompiler.SURFACE_AOD]
            ?: safe.profiles.getValue(SceneCompiler.SURFACE_AOD)
        val rawLockscreen = configuration.profiles[SceneCompiler.SURFACE_LOCKSCREEN]
            ?: safe.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val linkedLockscreen = if (configuration.linkSurfaces) {
            rawAod.copy(
                surface = SceneCompiler.SURFACE_LOCKSCREEN,
                enabled = rawLockscreen.enabled,
                collisionPolicy = rawLockscreen.collisionPolicy,
                widgets = rawLockscreen.widgets,
                metadataVisible = rawLockscreen.metadataVisible,
                backgroundStyle = rawLockscreen.backgroundStyle
            )
        } else {
            rawLockscreen
        }
        val profiles = linkedMapOf(
            SceneCompiler.SURFACE_LOCKSCREEN to validateProfile(
                SceneCompiler.SURFACE_LOCKSCREEN,
                linkedLockscreen
            ),
            SceneCompiler.SURFACE_AOD to validateProfile(SceneCompiler.SURFACE_AOD, rawAod)
        )
        return SceneCompiler.finalizeCompiled(
            configuration.copy(
                version = com.eza.hyperglow.customization.CURRENT_CUSTOMIZATION_VERSION,
                revision = 0L,
                hash = "",
                sourceId = normalizeIdentifier(configuration.sourceId),
                pauseLingerMs = normalizePauseLingerMs(configuration.pauseLingerMs),
                profiles = profiles
            )
        )
    }

    private fun validateProfile(
        surface: String,
        profile: CompiledSurfaceProfile
    ): CompiledSurfaceProfile {
        val aod = surface == SceneCompiler.SURFACE_AOD
        val policy = SurfacePolicyResolver.resolve(
            if (aod) LyricSurfaceKind.AOD else LyricSurfaceKind.LOCKSCREEN
        )
        val maxWidgets = policy.maxWidgets
        val widgets = profile.widgets.asSequence()
            .filter { WidgetRendererRegistry.renderer(it.type) != null }
            .filter { !aod || it.type != "media_progress" }
            .take(maxWidgets)
            .map { it.copy(style = normalizeIdentifier(it.style)) }
            .toMutableList()
        if (widgets.none { it.type == "lyrics" }) {
            if (widgets.size >= maxWidgets) widgets.removeLast()
            widgets.add(0, WidgetSpec("lyrics"))
        }
        return profile.copy(
            surface = surface,
            anchor = profile.anchor.takeIf { it in ANCHORS } ?: "below_stock_clock",
            widthFraction = profile.widthFraction.coerceIn(0.4f, 1f),
            maxHeightFraction = profile.maxHeightFraction.coerceIn(
                0.15f,
                policy.maximumHeightFraction
            ),
            verticalBias = profile.verticalBias.coerceIn(0f, 1f),
            collisionPolicy = profile.collisionPolicy.takeIf { it in COLLISION_POLICIES }
                ?: "avoid",
            widgets = widgets,
            transition = profile.transition.copy(
                id = profile.transition.id.takeIf { it in TRANSITIONS } ?: "continuity",
                durationMs = profile.transition.durationMs.coerceIn(
                    policy.minimumAnimationDurationMs,
                    policy.maximumAnimationDurationMs
                ),
                easing = profile.transition.easing.takeIf { it in EASINGS }
                    ?: "fast_out_slow_in"
            ),
            alignment = profile.alignment.takeIf { it in ALIGNMENTS } ?: "auto",
            secondaryMode = profile.secondaryMode.takeIf { it in SECONDARY_MODES } ?: "Main only",
            lyricLineLimit = normalizeLyricLineLimit(profile.lyricLineLimit),
            metadataVisible = profile.metadataVisible && widgets.any { it.type == "metadata" },
            metadataAnchor = if (profile.metadataAnchor == "bottom") "bottom" else "top",
            metadataSizePercent = profile.metadataSizePercent.coerceIn(50, 200),
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
            backgroundStyle = if (!aod && profile.backgroundStyle == "card") "card" else "none",
            cardAlpha = normalizeCardAlpha(profile.cardAlpha),
            cardColor = normalizeCardColor(profile.cardColor),
            palette = profile.palette.asSequence()
                .filter { it.key in SEMANTIC_COLORS && it.value in PALETTE_VALUES }
                .take(SEMANTIC_COLORS.size)
                .associate { it.key to it.value }
        )
    }

    private fun normalizeIdentifier(value: String): String = value
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(64)
        .ifBlank { "default" }

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
}

internal object CompiledCustomizationBundleCodec {
    internal data class WirePayload(
        val protocol: Int,
        val userId: Int,
        val revision: Long,
        val hash: String,
        val json: String,
        /**
         * App-side experimental-mode toggle, forwarded so the hook side can let
         * [com.eza.hyperglow.root.capability.XiaomiCapabilityResolver] derive
         * surface capabilities from symbol probes on EXPERIMENTAL_ELIGIBLE profiles.
         * Defaults to false for older producers (backwards compatible).
         */
        val experimentalMode: Boolean = false
    )

    fun toBundle(
        configuration: CompiledCustomization,
        userId: Int = 0,
        experimentalMode: Boolean = false
    ): Bundle = toBundle(toWirePayload(configuration, userId, experimentalMode))

    fun toWirePayload(
        configuration: CompiledCustomization,
        userId: Int = 0,
        experimentalMode: Boolean = false
    ): WirePayload = WirePayload(
        protocol = PROTOCOL_VERSION,
        userId = userId.coerceAtLeast(0),
        revision = configuration.revision,
        hash = configuration.hash,
        json = SceneCompiler.json.encodeToString(configuration),
        experimentalMode = experimentalMode
    )

    fun snapshotFromBundle(bundle: Bundle): WirePayload? {
        val payload = WirePayload(
            protocol = bundle.getInt(KEY_PROTOCOL, 0),
            userId = bundle.getInt(KEY_USER_ID, -1),
            revision = bundle.getLong(KEY_REVISION, -1L),
            hash = bundle.getString(KEY_HASH).orEmpty(),
            json = bundle.getString(KEY_JSON).orEmpty(),
            experimentalMode = bundle.getBoolean(KEY_EXPERIMENTAL_MODE, false)
        )
        return payload.takeIf(::isValidWirePayload)
    }

    fun fromBundle(bundle: Bundle, expectedUserId: Int? = null): CompiledCustomization? {
        val payload = snapshotFromBundle(bundle) ?: return null
        return fromWirePayload(payload, expectedUserId)
    }

    fun fromWirePayload(
        payload: WirePayload,
        expectedUserId: Int? = null
    ): CompiledCustomization? {
        if (!isValidWirePayload(payload)) return null
        if (expectedUserId != null && payload.userId != expectedUserId) return null
        val decoded = runCatching {
            SceneCompiler.json.decodeFromString<CompiledCustomization>(payload.json)
        }.getOrNull() ?: return null
        if (decoded.revision != payload.revision || decoded.hash != payload.hash) return null
        val validated = SystemUiCustomizationValidator.validate(decoded) ?: return null
        if (validated.revision != decoded.revision || validated.hash != decoded.hash) return null
        return validated
    }

    internal fun isValidWirePayload(payload: WirePayload): Boolean {
        if (payload.protocol != PROTOCOL_VERSION) return false
        if (payload.userId < 0 || payload.revision < 0L) return false
        if (payload.hash.length != SHA_256_HEX_LENGTH ||
            payload.hash.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) return false
        if (payload.json.isBlank() || payload.json.length > SceneCompiler.MAX_CONFIG_BYTES) {
            return false
        }
        return payload.json.toByteArray(Charsets.UTF_8).size <= SceneCompiler.MAX_CONFIG_BYTES
    }

    private fun toBundle(payload: WirePayload): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, payload.protocol)
        putInt(KEY_USER_ID, payload.userId)
        putLong(KEY_REVISION, payload.revision)
        putString(KEY_HASH, payload.hash)
        putString(KEY_JSON, payload.json)
        putBoolean(KEY_EXPERIMENTAL_MODE, payload.experimentalMode)
    }

    private const val PROTOCOL_VERSION = 3
    private const val SHA_256_HEX_LENGTH = 64
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_USER_ID = "userId"
    private const val KEY_REVISION = "revision"
    private const val KEY_HASH = "hash"
    private const val KEY_JSON = "json"
    private const val KEY_EXPERIMENTAL_MODE = "experimentalMode"
}
