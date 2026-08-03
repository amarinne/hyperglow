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
        AodProjectionEngine.start(this)
    }
}
