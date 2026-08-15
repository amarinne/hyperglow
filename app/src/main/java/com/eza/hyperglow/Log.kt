package com.eza.hyperglow

import android.util.Log

object AppLog {
    private const val TAG = "HyperGlow"
    val traceEnabled: Boolean
        get() = DiagnosticLoggingRuntime.enabled

    fun i(area: String, message: String) {
        if (!traceEnabled) return
        Log.i(TAG, "[$area] $message")
        DiagnosticTraceFile.append("I", area, message)
    }
    fun bootstrap(area: String, stage: String) {
        if (!BuildConfig.TRACE_LOGGING_AVAILABLE) return
        runCatching { Log.i(TAG, "[$area] bootstrap=$stage") }
    }
    fun w(area: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$area] $message", error)
        DiagnosticTraceFile.append("W", area, withError(message, error))
    }
    fun e(area: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "[$area] $message", error)
        DiagnosticTraceFile.append("E", area, withError(message, error))
    }

    private fun withError(message: String, error: Throwable?): String =
        if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}"
}
