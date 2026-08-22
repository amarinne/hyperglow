package com.eza.hyperglow

import android.app.Application
import android.content.Intent
import com.eza.hyperglow.aod.AodLyricBridgeService
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
        // Promote the bridge service to a foreground service: SystemUI only ever binds it, and a
        // bind neither triggers onStartCommand nor promotes the process against OEM background
        // freezing (MIUI Greeze stops AOD/lockscreen lyric updates with no visible failure).
        // Background startForegroundService is commonly denied outside a start window; this is a
        // best-effort attempt, retried from MainActivity.onCreate and the service's own onBind.
        runCatching {
            startForegroundService(Intent(this, AodLyricBridgeService::class.java))
        }.onFailure { error ->
            AppLog.w("HyperGlowApplication", "startForegroundService denied", error)
        }
    }
}
