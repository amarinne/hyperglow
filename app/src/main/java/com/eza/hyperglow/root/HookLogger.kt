package com.eza.hyperglow.root

import android.util.Log
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

    fun w(area: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$area] $message", error)
        module?.log(Log.WARN, TAG, "[$area] $message", error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "[$area] $message", error)
        module?.log(Log.ERROR, TAG, "[$area] $message", error)
    }
}
