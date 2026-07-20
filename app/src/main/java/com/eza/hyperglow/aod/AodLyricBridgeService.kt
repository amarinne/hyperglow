package com.eza.hyperglow.aod

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.Process
import com.eza.hyperglow.AppLog

class AodLyricBridgeService : Service() {
    private val binder = object : IAodLyricBridge.Stub() {
        override fun registerCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) AodStateBridge.register(callback)
        }

        override fun unregisterCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) AodStateBridge.unregister(callback)
        }

        override fun reportCapabilities(report: Bundle?) {
            if (report != null && isSystemUiCaller()) XiaomiCapabilityStore.save(this@AodLyricBridgeService, report)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isSystemUiCaller(): Boolean {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        val allowed = uid == Process.SYSTEM_UID && packages.contains(SYSTEM_UI_PACKAGE)
        if (!allowed) AppLog.w(TAG, "Rejected caller uid=$uid")
        return allowed
    }

    companion object {
        private const val TAG = "AodLyricBridgeService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
