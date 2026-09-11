package com.eza.hyperglow.customization

import android.content.Context
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import kotlinx.serialization.encodeToString

object CustomizationRepository {
    private const val PREFS = "surface_customization"
    private const val KEY_DOCUMENT = "document_json"
    private const val KEY_PREVIOUS_DOCUMENT = "previous_document_json"

    // Projection ticks run at 10 Hz while a song is playing. Keep the compiled scene in memory
    // and only decode/canonicalize when the persisted inputs change; parsing and compiling the
    // JSON document on every tick otherwise burns CPU and allocates a full profile graph.
    private var compiledCacheInitialized = false
    private var cachedCurrentRaw: String? = null
    private var cachedPreviousRaw: String? = null
    private var cachedLegacyConfig: AodRenderConfig? = null
    private var cachedCompiled: CompiledCustomization? = null

    /**
     * Resolves the stored customization through the single recovery ladder
     * ([recoverDocument]: current → previous → legacy preferences → safe default) and persists the
     * recovered canonical form only when it differs from the stored raw JSON, so the next read
     * takes the no-write fast path. A failed commit reads as a failed load and the safe default
     * is returned instead.
     */
    @Synchronized
    fun loadDocument(context: Context): CustomizationDocument {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedRaw = prefs.getString(KEY_DOCUMENT, null)
        val recovered = recoverDocument(
            currentRaw = storedRaw,
            previousRaw = prefs.getString(KEY_PREVIOUS_DOCUMENT, null),
            legacy = AodRenderPreferences.read(context)
        )
        val encoded = SceneCompiler.json.encodeToString(recovered)
        val saved = storedRaw == encoded ||
            prefs.edit().putString(KEY_DOCUMENT, encoded).commit()
        return if (saved) recovered else SceneCompiler.safeDefaultDocument()
    }

    @Synchronized
    fun loadCompiled(context: Context): CompiledCustomization {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentRaw = prefs.getString(KEY_DOCUMENT, null)
        val previousRaw = prefs.getString(KEY_PREVIOUS_DOCUMENT, null)
        // Legacy values matter only while no canonical document exists. AodRenderPreferences is
        // itself cached, so this comparison is inexpensive and still notices first-run edits.
        val legacy = if (currentRaw == null && previousRaw == null) {
            AodRenderPreferences.read(context)
        } else {
            null
        }
        if (compiledCacheInitialized &&
            currentRaw == cachedCurrentRaw &&
            previousRaw == cachedPreviousRaw &&
            legacy == cachedLegacyConfig
        ) {
            return requireNotNull(cachedCompiled)
        }
        val compiled = SceneCompiler.compile(loadDocument(context))
        compiledCacheInitialized = true
        cachedCurrentRaw = prefs.getString(KEY_DOCUMENT, null)
        cachedPreviousRaw = prefs.getString(KEY_PREVIOUS_DOCUMENT, null)
        cachedLegacyConfig = legacy
        cachedCompiled = compiled
        return compiled
    }

    @Synchronized
    fun saveDocument(context: Context, document: CustomizationDocument): Boolean {
        val normalized = canonicalizeDocument(document) ?: return false
        val encoded = SceneCompiler.json.encodeToString(normalized)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_DOCUMENT, null)?.takeIf {
            decodeCurrentDocument(it) != null
        }
        val editor = prefs.edit()
            .putString(KEY_DOCUMENT, encoded)
        if (previous != null) editor.putString(KEY_PREVIOUS_DOCUMENT, previous)
        return editor.commit()
    }

    @Synchronized
    fun importDocument(context: Context, raw: String): Boolean {
        val document = SceneCompiler.decodeDocument(raw) ?: return false
        return saveDocument(context, document)
    }

    @Synchronized
    fun exportDocument(context: Context): String =
        SceneCompiler.json.encodeToString(loadDocument(context))

    @Synchronized
    fun reset(context: Context): Boolean =
        saveDocument(context, SceneCompiler.safeDefaultDocument())

    internal fun documentFromLegacy(config: AodRenderConfig): CustomizationDocument {
        val common = SurfaceProfile(
            enabled = true,
            anchor = "below_stock_clock",
            widthFraction = 0.88f,
            maxHeightFraction = 0.42f,
            widgets = buildList {
                add(WidgetSpec("lyrics"))
                if (config.metadataVisible != "hide") add(WidgetSpec("metadata", optional = true))
            },
            alignment = config.alignment,
            secondaryMode = config.secondaryMode,
            metadataVisible = config.metadataVisible != "hide",
            metadataAnchor = config.metadataAnchor,
            weight = config.weight,
            textSize = config.textSize,
            textSizeCustom = config.textSizeCustom,
            fontFamily = config.fontFamily,
            animation = config.animation,
            glow = config.glow,
            overflow = config.overflowMode,
            adaptiveSectioning = config.adaptiveSectioning
        )
        return CustomizationDocument(
            id = "migrated_aod_render",
            name = "Migrated AOD layout",
            linkSurfaces = false,
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to common.copy(
                    enabled = config.lockscreenEnabled,
                    maxHeightFraction = 0.46f
                ),
                SceneCompiler.SURFACE_AOD to common.copy(
                    enabled = config.aodEnabled,
                    maxHeightFraction = DEFAULT_AOD_MAX_HEIGHT_FRACTION
                )
            )
        )
    }

    internal fun canonicalizeDocument(document: CustomizationDocument): CustomizationDocument? {
        // Version gate: unknown past/future documents are rejected whole; known documents are
        // canonicalized to the current version by the rebuild below. Version 2 is the one
        // stepped migration: v1 documents predate the canvas-height setting, so their AOD
        // 0.42 is the old implicit default rather than a choice and moves to the new
        // default. Version 2+ documents carry explicit selections and are preserved.
        if (document.version < 0 || document.version > CURRENT_CUSTOMIZATION_VERSION) return null
        val migrated = if (document.version < 2) {
            document.copy(
                version = CURRENT_CUSTOMIZATION_VERSION,
                profiles = document.profiles.mapValues { (surface, profile) ->
                    if (surface == SceneCompiler.SURFACE_AOD &&
                        profile.maxHeightFraction == LEGACY_AOD_MAX_HEIGHT_FRACTION
                    ) {
                        profile.copy(maxHeightFraction = DEFAULT_AOD_MAX_HEIGHT_FRACTION)
                    } else {
                        profile
                    }
                }
            )
        } else {
            document
        }
        val compiled = SceneCompiler.compile(migrated)
        if (compiled.hash.isBlank()) return null
        return CustomizationDocument(
            version = CURRENT_CUSTOMIZATION_VERSION,
            id = compiled.sourceId,
            name = document.name.trim().take(100).ifBlank { "Customization" },
            linkSurfaces = compiled.linkSurfaces,
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to compiled.profiles
                    .getValue(SceneCompiler.SURFACE_LOCKSCREEN)
                    .toSurfaceProfile(),
                SceneCompiler.SURFACE_AOD to compiled.profiles
                    .getValue(SceneCompiler.SURFACE_AOD)
                    .toSurfaceProfile()
            )
        )
    }

    internal fun recoverDocument(
        currentRaw: String?,
        previousRaw: String?,
        legacy: AodRenderConfig
    ): CustomizationDocument =
        currentRaw?.let(::decodeCurrentDocument)
            ?: previousRaw?.let(::decodeCurrentDocument)
            ?: canonicalizeDocument(documentFromLegacy(legacy))
            ?: SceneCompiler.safeDefaultDocument()

    private fun decodeCurrentDocument(raw: String): CustomizationDocument? =
        SceneCompiler.decodeDocument(raw)?.let(::canonicalizeDocument)

    private fun CompiledSurfaceProfile.toSurfaceProfile(): SurfaceProfile = SurfaceProfile(
        enabled = enabled,
        anchor = anchor,
        widthFraction = widthFraction,
        maxHeightFraction = maxHeightFraction,
        verticalBias = verticalBias,
        collisionPolicy = collisionPolicy,
        widgets = widgets,
        transition = transition,
        alignment = alignment,
        secondaryMode = secondaryMode,
        secondaryTextBright = secondaryTextBright,
        lyricLineLimit = lyricLineLimit,
        metadataVisible = metadataVisible,
        metadataAnchor = metadataAnchor,
        metadataSizePercent = metadataSizePercent,
        rubyVisible = rubyVisible,
        weight = weight,
        textSize = textSize,
        textSizeCustom = textSizeCustom,
        fontFamily = fontFamily,
        animation = animation,
        glow = glow,
        lineSyncFillMode = lineSyncFillMode,
        overflow = overflow,
        adaptiveSectioning = adaptiveSectioning,
        duetEnabled = duetEnabled,
        palette = palette,
        backgroundStyle = backgroundStyle,
        cardColor = cardColor,
        cardAlpha = cardAlpha
    )
}
