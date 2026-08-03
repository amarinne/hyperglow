package com.eza.hyperglow.root

import android.util.Log
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.DiagnosticLoggingRuntime
import io.github.libxposed.api.XposedModule

object HookLogger {
    private const val TAG = "HyperGlow"
    var module: XposedModule? = null
    val traceEnabled: Boolean
        get() = DiagnosticLoggingRuntime.enabled

    fun i(area: String, message: String) {
        if (!traceEnabled) return
        Log.i(TAG, "[$area] $message")
        module?.log(Log.INFO, TAG, "[$area] $message")
    }

    /**
     * Finite boot-path evidence. It must not depend on bridge-delivered diagnostic configuration,
     * since this path is also used to diagnose a bridge which never connects.
     */
    fun bootstrap(area: String, stage: String) {
        if (!BuildConfig.TRACE_LOGGING_AVAILABLE) return
        val message = "[$area] bootstrap=$stage"
        runCatching {
            Log.i(TAG, message)
            module?.log(Log.INFO, TAG, message)
        }
    }

    fun w(area: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$area] $message", error)
        module?.log(Log.WARN, TAG, "[$area] $message", error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "[$area] $message", error)
        module?.log(Log.ERROR, TAG, "[$area] $message", error)
    }
}
