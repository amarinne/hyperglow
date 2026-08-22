package com.eza.hyperglow.aod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.ui.MainActivity

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

/** Whether the promoted service's notification could currently be displayed at all. */
internal enum class BridgeNotificationPresentation { POSTED, SUPPRESSED }

/**
 * The app never holds POST_NOTIFICATIONS: the manifest does not declare it, so the grant cannot
 * appear, and owner observation on vC96 confirmed the promotion survives with granted=false and
 * zero notification records. The value is still logged per start because it is the only readable
 * evidence that the service promoted and whether the disclosure could ever surface; HyperOS
 * suppresses app logcat.
 */
internal fun resolveBridgeNotificationPresentation(
    notificationsEnabled: Boolean
): BridgeNotificationPresentation =
    if (notificationsEnabled) {
        BridgeNotificationPresentation.POSTED
    } else {
        BridgeNotificationPresentation.SUPPRESSED
    }

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

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to a specialUse foreground service: SystemUI reaches this service through
        // bindService, which never triggers onStartCommand and never promotes. Without promotion,
        // MIUI Greeze freezes the app process while the screen is off and AOD/lockscreen lyrics
        // stop updating with no visible failure. Promotion is process survival only; it never reads
        // or writes wake, lifetime, capability, or presentation policy.
        val notification = buildNotification()
        val promotion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE exists from API 34 only; older platforms
                // reject the unknown type, so they promote untyped.
                startForeground(
                    BRIDGE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(BRIDGE_NOTIFICATION_ID, notification)
            }
        }

        if (promotion.isFailure) {
            // A caught-but-unpromoted start leaves the pending startForegroundService obligation
            // outstanding; the system kills the process about five seconds later with
            // ForegroundServiceDidNotStartInTimeException. stopSelf releases the obligation. There
            // is no automatic retry after an explicit stop: promotion waits for the next start
            // attempt from application/activity creation or the next SystemUI bind. The exception
            // identity is the evidence for which exemption path is missing.
            AppLog.w(
                TAG,
                "Foreground service promotion denied; stopping",
                promotion.exceptionOrNull()
            )
            stopSelf()
            return START_STICKY
        }

        val presentation = resolveBridgeNotificationPresentation(
            notificationsEnabled = notificationManager()?.areNotificationsEnabled() ?: false
        )
        // AppLog.i, not bootstrap: bootstrap reaches logcat only, and HyperOS suppresses
        // third-party app logcat, so the presentation decision was invisible on the owner device.
        // AppLog.i also appends to the diagnostic trace file, which the device gate can read.
        AppLog.i(TAG, "foreground_service_started presentation=$presentation")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        // SystemUI binding is the moment this process matters, so it is the third promotion
        // attempt after application creation and activity creation. Whether an inbound bind grants
        // a foreground-start window is platform-dependent; failure is caught, logged, and left to
        // the other attempts.
        runCatching {
            startForegroundService(Intent(this, AodLyricBridgeService::class.java))
        }.onFailure { error ->
            AppLog.w(TAG, "startForegroundService from bind denied", error)
        }
        return binder
    }

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

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun ensureNotificationChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(BRIDGE_NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            BRIDGE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_lyric_bridge),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_lyric_bridge_text)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Explicit MainActivity component: a hidden launcher icon (disabled launcher alias) makes
        // getLaunchIntentForPackage return null, which would dead-end the notification tap.
        // MainActivity itself stays enabled, so the explicit intent survives that.
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, BRIDGE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyric_notification)
            .setContentTitle(getString(R.string.notification_lyric_bridge_title))
            .setContentText(getString(R.string.notification_lyric_bridge_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "AodLyricBridgeService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val BRIDGE_NOTIFICATION_CHANNEL_ID = "lyric_bridge"
        private const val BRIDGE_NOTIFICATION_ID = 1
    }
}
