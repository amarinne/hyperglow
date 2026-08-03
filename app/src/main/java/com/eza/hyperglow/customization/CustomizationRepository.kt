package com.eza.hyperglow.customization

import android.content.Context
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import kotlinx.serialization.encodeToString

object CustomizationRepository {
    private const val PREFS = "surface_customization"
    private const val KEY_DOCUMENT = "document_json"
    private const val KEY_PREVIOUS_DOCUMENT = "previous_document_json"
    private const val KEY_MIGRATION_VERSION = "migration_version"

    @Synchronized
    fun loadDocument(context: Context): CustomizationDocument {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DOCUMENT, null)
        val current = stored?.let(::decodeCurrentDocument)
        if (current != null) {
            if (prefs.getInt(KEY_MIGRATION_VERSION, 0) != CURRENT_CUSTOMIZATION_VERSION) {
                prefs.edit()
                    .putString(KEY_DOCUMENT, SceneCompiler.json.encodeToString(current))
                    .putInt(KEY_MIGRATION_VERSION, CURRENT_CUSTOMIZATION_VERSION)
                    .commit()
            }
            return current
        }
        val previousRaw = prefs.getString(KEY_PREVIOUS_DOCUMENT, null)
        val previous = previousRaw?.let(::decodeCurrentDocument)
        if (previous != null) {
            prefs.edit()
                .putString(KEY_DOCUMENT, SceneCompiler.json.encodeToString(previous))
                .putInt(KEY_MIGRATION_VERSION, CURRENT_CUSTOMIZATION_VERSION)
                .commit()
            return previous
        }
        val migrated = canonicalizeDocument(documentFromLegacy(AodRenderPreferences.read(context)))
            ?: SceneCompiler.safeDefaultDocument()
        val encoded = SceneCompiler.json.encodeToString(migrated)
        val saved = prefs.edit()
            .putString(KEY_DOCUMENT, encoded)
            .putInt(KEY_MIGRATION_VERSION, CURRENT_CUSTOMIZATION_VERSION)
            .commit()
        return if (saved) migrated else SceneCompiler.safeDefaultDocument()
    }

    @Synchronized
    fun loadCompiled(context: Context): CompiledCustomization =
        SceneCompiler.compile(loadDocument(context))

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
            .putInt(KEY_MIGRATION_VERSION, CURRENT_CUSTOMIZATION_VERSION)
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
                SceneCompiler.SURFACE_AOD to common.copy(enabled = config.aodEnabled)
            )
        )
    }

    internal fun migrateDocument(document: CustomizationDocument): CustomizationDocument? {
        if (document.version < 0 || document.version > CURRENT_CUSTOMIZATION_VERSION) return null
        var migrated = document
        while (migrated.version < CURRENT_CUSTOMIZATION_VERSION) {
            migrated = when (migrated.version) {
                0 -> migrated.copy(version = 1)
                else -> return null
            }
        }
        return migrated
    }

    internal fun canonicalizeDocument(document: CustomizationDocument): CustomizationDocument? {
        val migrated = migrateDocument(document) ?: return null
        val compiled = SceneCompiler.compile(migrated)
        if (compiled.hash.isBlank()) return null
        return CustomizationDocument(
            version = CURRENT_CUSTOMIZATION_VERSION,
            id = compiled.sourceId,
            name = migrated.name.trim().take(100).ifBlank { "Customization" },
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
        palette = palette,
        backgroundStyle = backgroundStyle
    )
}
