package com.eza.hyperglow

import android.content.Context
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler

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

internal object RuntimeCustomization {
    fun loadCompiled(context: Context): CompiledCustomization = withDiagnosticLogging(
        CustomizationRepository.loadCompiled(context),
        DiagnosticLoggingPreferences.read(context),
        lockscreenKeepAwake = AodRenderPreferences.read(context).lockscreenKeepAwake,
        raiseToAod = AodRenderPreferences.read(context).raiseToAod
    )

    fun compile(
        document: CustomizationDocument,
        diagnosticLogging: Boolean,
        lockscreenKeepAwake: Boolean = false,
        raiseToAod: Boolean = false
    ): CompiledCustomization = withDiagnosticLogging(
        SceneCompiler.compile(document),
        diagnosticLogging,
        lockscreenKeepAwake = lockscreenKeepAwake,
        raiseToAod = raiseToAod
    )

    internal fun withDiagnosticLogging(
        configuration: CompiledCustomization,
        diagnosticLogging: Boolean,
        available: Boolean = BuildConfig.TRACE_LOGGING_AVAILABLE,
        lockscreenKeepAwake: Boolean = configuration.lockscreenKeepAwake,
        raiseToAod: Boolean = configuration.raiseToAod
    ): CompiledCustomization = requireNotNull(
        SceneCompiler.finalizeCompiled(
            configuration.copy(
                revision = 0L,
                hash = "",
                diagnosticLogging = diagnosticLoggingEnabled(
                    available,
                    diagnosticLogging
                ),
                lockscreenKeepAwake = lockscreenKeepAwake,
                raiseToAod = raiseToAod
            )
        )
    )
}
