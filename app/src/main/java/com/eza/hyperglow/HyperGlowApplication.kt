package com.eza.hyperglow

import android.app.Application
import com.eza.hyperglow.aod.AodProjectionEngine
import com.eza.hyperglow.diagnostics.DiagnosticCaptureManager
import com.eza.hyperglow.diagnostics.DiagnosticDraftStore

class HyperGlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticCaptureManager.expireIfNeeded(this)
        DiagnosticDraftStore.load(this)
        DiagnosticLoggingRuntime.setEnabled(DiagnosticLoggingPreferences.read(this))
        DiagnosticTraceFile.setDirectory(filesDir.takeIf { DiagnosticLoggingRuntime.enabled })
        // A trace with no lines is otherwise indistinguishable from a trace that never opened, and
        // an idle app process writes nothing for as long as playback stays away.
        AppLog.i(
            "Diagnostics",
            "trace ready versionCode=${BuildConfig.VERSION_CODE} pid=${android.os.Process.myPid()}"
        )
        AodProjectionEngine.start(this)
    }
}
