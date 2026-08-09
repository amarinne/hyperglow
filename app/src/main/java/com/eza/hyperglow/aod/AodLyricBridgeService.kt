package com.eza.hyperglow.aod

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import com.eza.hyperglow.AppLog

/** No uid resolved for the SystemUI package. Never matches a real caller. */
internal const val UNRESOLVED_SYSTEM_UI_UID = -1

/**
 * SystemUI is not always uid 1000. HyperOS 3 on the Xiaomi 17 family runs
 * `com.android.systemui` under an ordinary app uid, and a hardcoded system-uid check rejects every
 * call from a hook that is working perfectly: the module binds, registers, and reports
 * capabilities, while the app stores nothing and reports `no_systemui_report`. Matching the
 * package's resolved uid still yields 1000 wherever SystemUI shares `android.uid.system`, so this
 * is the same gate on older builds and a correct one on the new ones.
 */
internal fun isSystemUiBridgeCaller(callingUid: Int, systemUiUid: Int): Boolean =
    systemUiUid != UNRESOLVED_SYSTEM_UI_UID && callingUid == systemUiUid

class AodLyricBridgeService : Service() {
    private val binder = object : IAodLyricBridge.Stub() {
        override fun registerCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) {
                AodStateBridge.register(callback)
                AppLog.bootstrap(TAG, "systemui_callback_accepted")
            }
        }

        override fun unregisterCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) AodStateBridge.unregister(callback)
        }

        override fun reportCapabilities(report: Bundle?) {
            if (report != null && isSystemUiCaller()) {
                XiaomiCapabilityStore.save(this@AodLyricBridgeService, report)
                AppLog.bootstrap(TAG, "systemui_capability_report_accepted")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isSystemUiCaller(): Boolean {
        val uid = Binder.getCallingUid()
        val systemUiUid = resolveSystemUiUid()
        val allowed = isSystemUiBridgeCaller(uid, systemUiUid)
        if (!allowed) {
            // Both uids, because "rejected" on its own cannot distinguish an impostor from this
            // gate mismatching the platform.
            AppLog.bootstrap(TAG, "systemui_caller_rejected uid=$uid systemui_uid=$systemUiUid")
            AppLog.w(TAG, "Rejected caller uid=$uid systemui_uid=$systemUiUid")
        }
        return allowed
    }

    private fun resolveSystemUiUid(): Int = try {
        packageManager.getPackageUid(SYSTEM_UI_PACKAGE, 0)
    } catch (error: PackageManager.NameNotFoundException) {
        AppLog.w(TAG, "SystemUI package not installed", error)
        UNRESOLVED_SYSTEM_UI_UID
    }

    companion object {
        private const val TAG = "AodLyricBridgeService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
