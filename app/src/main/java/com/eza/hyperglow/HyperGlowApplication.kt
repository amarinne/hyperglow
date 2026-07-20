package com.eza.hyperglow

import android.app.Application
import com.eza.hyperglow.aod.AodProjectionEngine

class HyperGlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLoggingRuntime.setEnabled(DiagnosticLoggingPreferences.read(this))
        AodProjectionEngine.start(this)
    }
}
