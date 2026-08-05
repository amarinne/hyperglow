package com.eza.hyperglow

import android.util.Log

object AppLog {
    private const val TAG = "HyperGlow"
    val traceEnabled: Boolean
        get() = DiagnosticLoggingRuntime.enabled

    fun i(area: String, message: String) {
        if (!traceEnabled) return
        Log.i(TAG, "[$area] $message")
    }
    fun bootstrap(area: String, stage: String) {
        if (!BuildConfig.TRACE_LOGGING_AVAILABLE) return
        runCatching { Log.i(TAG, "[$area] bootstrap=$stage") }
    }
    fun w(area: String, message: String, error: Throwable? = null) =
        runCatching { Log.w(TAG, "[$area] $message", error) }
    fun e(area: String, message: String, error: Throwable? = null) =
        Log.e(TAG, "[$area] $message", error)
}
