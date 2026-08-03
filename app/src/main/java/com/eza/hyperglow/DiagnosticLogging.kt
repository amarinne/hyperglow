package com.eza.hyperglow

import android.content.Context
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.projection.currentProcessUserId

internal fun diagnosticLoggingEnabled(available: Boolean, requested: Boolean): Boolean =
    available && requested

internal object DiagnosticLoggingRuntime {
    @Volatile
    private var requested = false

    val enabled: Boolean
        get() = diagnosticLoggingEnabled(BuildConfig.TRACE_LOGGING_AVAILABLE, requested)

    fun setEnabled(enabled: Boolean) {
        requested = enabled
    }
}

internal object DiagnosticLoggingPreferences {
    private const val PREFS = "diagnostics"
    private const val KEY_DIAGNOSTIC_LOGGING = "diagnostic_logging"

    fun read(context: Context): Boolean = diagnosticLoggingEnabled(
        available = BuildConfig.TRACE_LOGGING_AVAILABLE,
        requested = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIAGNOSTIC_LOGGING, false)
    )

    fun write(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(
                KEY_DIAGNOSTIC_LOGGING,
                diagnosticLoggingEnabled(BuildConfig.TRACE_LOGGING_AVAILABLE, enabled)
            )
            .commit()
}

internal fun setDiagnosticLogging(context: Context, enabled: Boolean): Boolean {
    if (!DiagnosticLoggingPreferences.write(context, enabled)) return false
    val effective = DiagnosticLoggingPreferences.read(context)
    DiagnosticLoggingRuntime.setEnabled(effective)
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId()
    )
    return true
}

internal object RuntimeCustomization {
    fun loadCompiled(context: Context): CompiledCustomization {
        val preferences = AodRenderPreferences.read(context)
        return withDiagnosticLogging(
            CustomizationRepository.loadCompiled(context),
            DiagnosticLoggingPreferences.read(context),
            pauseLingerMs = preferences.pauseLingerMs,
            lockscreenKeepAwake = preferences.lockscreenKeepAwake,
            raiseToAod = preferences.raiseToAod,
            suppressLockscreenEditorLongPress = preferences.suppressLockscreenEditorLongPress
        )
    }

    fun compile(
        document: CustomizationDocument,
        diagnosticLogging: Boolean,
        pauseLingerMs: Long = 5_000L,
        lockscreenKeepAwake: Boolean = false,
        raiseToAod: Boolean = false,
        suppressLockscreenEditorLongPress: Boolean = false
    ): CompiledCustomization = withDiagnosticLogging(
        SceneCompiler.compile(document),
        diagnosticLogging,
        pauseLingerMs = pauseLingerMs,
        lockscreenKeepAwake = lockscreenKeepAwake,
        raiseToAod = raiseToAod,
        suppressLockscreenEditorLongPress = suppressLockscreenEditorLongPress
    )

    internal fun withDiagnosticLogging(
        configuration: CompiledCustomization,
        diagnosticLogging: Boolean,
        available: Boolean = BuildConfig.TRACE_LOGGING_AVAILABLE,
        pauseLingerMs: Long = configuration.pauseLingerMs,
        lockscreenKeepAwake: Boolean = configuration.lockscreenKeepAwake,
        raiseToAod: Boolean = configuration.raiseToAod,
        suppressLockscreenEditorLongPress: Boolean =
            configuration.suppressLockscreenEditorLongPress
    ): CompiledCustomization = requireNotNull(
        SceneCompiler.finalizeCompiled(
            configuration.copy(
                revision = 0L,
                hash = "",
                diagnosticLogging = diagnosticLoggingEnabled(
                    available,
                    diagnosticLogging
                ),
                pauseLingerMs = com.eza.hyperglow.aod.normalizePauseLingerMs(pauseLingerMs),
                lockscreenKeepAwake = lockscreenKeepAwake,
                raiseToAod = raiseToAod,
                suppressLockscreenEditorLongPress = suppressLockscreenEditorLongPress
            )
        )
    )
}
