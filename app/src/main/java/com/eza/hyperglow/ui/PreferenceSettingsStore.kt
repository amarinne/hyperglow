package com.eza.hyperglow.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.eza.hyperglow.DiagnosticLoggingRuntime
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.DiagnosticTraceFile
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.projection.currentProcessUserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Disk and bridge implementation of [SettingsSession.Store].
 *
 * Every method runs on [Dispatchers.IO]; JSON encoding, preference commits, and the Binder
 * configuration publish never touch the UI thread, including when [SettingsSession.flushNow] is
 * awaited from a main-scope coroutine. Implementations catch their own I/O errors and report them
 * as `false` because the session treats a `false` return as the fail-closed signal.
 */
internal class PreferenceSettingsStore(
    private val context: Context
) : SettingsSession.Store {

    /** Last component/task side-effect state applied after a successful config write. */
    private var lastAppliedHideLauncherIcon: Boolean? = null
    private var lastAppliedHideFromRecents: Boolean? = null

    override suspend fun persistDocument(document: CustomizationDocument): Boolean =
        withContext(Dispatchers.IO) {
            val saved = runCatching {
                CustomizationRepository.saveDocument(context, document)
            }.getOrDefault(false)
            // The legacy mirror is part of the document write contract: a half-applied pair must
            // read as a failed flush so the session retries both writes together.
            saved && runCatching { applyLegacyMirror(document) }.getOrDefault(false)
        }

    override suspend fun persistConfig(config: AodRenderConfig): Boolean =
        withContext(Dispatchers.IO) {
            val committed = runCatching {
                val editor = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
                ConfigBackupCodec.booleanFields.forEach { editor.putBoolean(it.key, it.read(config)) }
                ConfigBackupCodec.intFields.forEach { editor.putInt(it.key, it.read(config)) }
                ConfigBackupCodec.longFields.forEach { editor.putLong(it.key, it.read(config)) }
                ConfigBackupCodec.stringFields.forEach { editor.putString(it.key, it.read(config)) }
                editor.commit()
            }.getOrDefault(false)
            // Component/task state follows the persisted snapshot, never optimistic memory, so
            // a failed or rolled-back flush cannot leave it diverged from disk.
            if (committed) applyLauncherSideEffects(config)
            committed
        }

    private fun applyLauncherSideEffects(config: AodRenderConfig) {
        if (lastAppliedHideLauncherIcon != config.hideLauncherIcon) {
            applyHideLauncherIcon(context, config.hideLauncherIcon)
            lastAppliedHideLauncherIcon = config.hideLauncherIcon
        }
        if (lastAppliedHideFromRecents != config.hideFromRecents) {
            applyExcludeFromRecents(context, config.hideFromRecents)
            lastAppliedHideFromRecents = config.hideFromRecents
        }
    }

    override suspend fun persistDiagnostic(enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val saved = runCatching {
                DiagnosticLoggingPreferences.write(context, enabled)
            }.getOrDefault(false)
            if (saved) {
                // Parity with the previous synchronous path: process side effects follow a
                // successful write only.
                val effective = DiagnosticLoggingPreferences.read(context)
                DiagnosticLoggingRuntime.setEnabled(effective)
                DiagnosticTraceFile.setDirectory(context.applicationContext.filesDir.takeIf {
                    effective
                })
            }
            saved
        }

    override suspend fun publish(
        config: AodRenderConfig,
        document: CustomizationDocument,
        diagnosticLogging: Boolean
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                AodStateBridge.publishConfiguration(
                    RuntimeCustomization.compile(
                        document = document,
                        diagnosticLogging = diagnosticLogging,
                        pauseLingerMs = config.pauseLingerMs,
                        lockscreenKeepAwake = config.lockscreenKeepAwake,
                        raiseToAod = config.raiseToAod,
                        suppressLockscreenEditorLongPress =
                            config.suppressLockscreenEditorLongPress
                    ),
                    currentProcessUserId()
                )
            }
        }
    }

    /**
     * Mirrors the compiled document's surface values into the legacy preference file exactly as
     * the pre-session synchronous path did, so readers of those keys stay compatible. Returns
     * whether the mirror commit landed.
     */
    private fun applyLegacyMirror(document: CustomizationDocument): Boolean {
        val aod = document.profiles[SceneCompiler.SURFACE_AOD]
            ?: SceneCompiler.safeAodProfile()
        val lockscreen = document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]
            ?: SceneCompiler.safeLockscreenProfile()
        return context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
            .putBoolean(AodRenderPreferences.AOD_ENABLED, aod.enabled)
            .putBoolean(AodRenderPreferences.LOCKSCREEN_ENABLED, lockscreen.enabled)
            .putString(AodRenderPreferences.ALIGNMENT, aod.alignment)
            .putString(AodRenderPreferences.SECONDARY, aod.secondaryMode)
            .putString(AodRenderPreferences.OVERFLOW, aod.overflow)
            .putString(
                AodRenderPreferences.METADATA_VISIBLE,
                if (aod.metadataVisible) "show" else "hide"
            )
            .putString(AodRenderPreferences.METADATA_ANCHOR, aod.metadataAnchor)
            .putString(AodRenderPreferences.WEIGHT, aod.weight)
            .putString(AodRenderPreferences.TEXT_SIZE, aod.textSize)
            .putInt(AodRenderPreferences.TEXT_SIZE_CUSTOM, aod.textSizeCustom)
            .putString(AodRenderPreferences.FONT_FAMILY, aod.fontFamily)
            .putString(AodRenderPreferences.ANIMATION, aod.animation)
            .putString(AodRenderPreferences.GLOW, aod.glow)
            .putBoolean(AodRenderPreferences.ADAPTIVE_SECTIONING, aod.adaptiveSectioning)
            .commit()
    }
}

internal fun applyHideLauncherIcon(context: Context, hidden: Boolean) {
    // Only the alias flips; MainActivity stays enabled so LSPosed keeps its launch entry.
    runCatching {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, LAUNCHER_ALIAS_CLASS),
            launcherAliasEnabledState(hidden),
            PackageManager.DONT_KILL_APP
        )
    }
}

internal fun applyExcludeFromRecents(context: Context, exclude: Boolean) {
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
    runCatching {
        activityManager.appTasks?.forEach { task -> task.setExcludeFromRecents(exclude) }
    }
}
