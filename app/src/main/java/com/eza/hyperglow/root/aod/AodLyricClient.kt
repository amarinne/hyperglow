package com.eza.hyperglow.root.aod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import com.eza.hyperglow.aod.AodStateWireBundleCodec
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.aod.IAodLyricBridge
import com.eza.hyperglow.aod.IAodLyricCallback
import com.eza.hyperglow.aod.XiaomiCapabilityBundleCodec
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.capability.missingProbeNames
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec.WirePayload
import com.eza.hyperglow.root.projection.LyricProjectionClient

private const val RETRY_DELAY_BASE_MS = 1_000L
private const val RETRY_DELAY_CAP_MS = 30_000L

/**
 * Whether an arriving state message may replace one that has not been delivered yet.
 *
 * The mailbox holds a single latest message, which is right for two snapshots: the newer one
 * supersedes the older. A keepalive is not a substitute for a snapshot. It only renews the snapshot
 * the consumer already holds and carries the revision of a snapshot it assumes arrived, so letting
 * it overwrite an undelivered snapshot left the projection one revision behind, rejecting every
 * keepalive that followed, and expiring five seconds later — which withdraws the AOD lifetime
 * guard, replays Xiaomi's hide, and drops the lyrics to the bottom of the screen mid-song.
 */
internal fun shouldReplacePendingState(
    pending: AodStateWireMessage?,
    incoming: AodStateWireMessage
): Boolean = pending == null ||
    incoming !is AodStateWireMessage.KeepAlive ||
    pending is AodStateWireMessage.KeepAlive

internal class GenerationBoundLatest<T> {
    private var generation = -1L
    private var value: T? = null

    fun offer(generation: Long, currentGeneration: Long, value: T): Boolean {
        if (generation != currentGeneration) return false
        this.generation = generation
        this.value = value
        return true
    }

    fun peek(currentGeneration: Long): T? = value.takeIf { generation == currentGeneration }

    fun take(currentGeneration: Long): T? {
        val result = value.takeIf { generation == currentGeneration }
        clear()
        return result
    }

    fun clear() {
        generation = -1L
        value = null
    }
}

internal class AodLyricClient(
    private val onConfiguration: (WirePayload) -> Unit,
    private val onState: (AodStateWireMessage) -> Unit,
    private val onDisconnected: () -> Unit
) : LyricProjectionClient {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var bindingUser: UserHandle? = null
    private var bridge: IAodLyricBridge? = null
    private var connection: ServiceConnection? = null
    private var callback: IAodLyricCallback? = null
    private var bound = false
    private var stopped = true
    private var bindingGeneration = 0L
    private var lastCapabilityReportGeneration = -1L
    private var bindAttemptCount = 0
    private var retryAttempt = 0
    private var bindUserId = -1
    private var lastNoBridgeReportElapsedMs = -1L
    private val connectWatchdog = Runnable { onConnectWatchdog() }
    private val pendingConfiguration = GenerationBoundLatest<WirePayload>()
    private val pendingState = GenerationBoundLatest<AodStateWireMessage>()
    private val retry = Runnable { attemptBind() }
    private val deliverConfiguration = Runnable {
        val configuration = synchronized(this) {
            if (stopped) {
                pendingConfiguration.clear()
                null
            } else {
                pendingConfiguration.take(bindingGeneration)
            }
        } ?: return@Runnable
        try {
            onConfiguration(configuration)
        } catch (error: Exception) {
            HookLogger.e(TAG, "Configuration apply failed", error)
        }
    }
    private val deliverState = Runnable {
        val state = synchronized(this) {
            if (stopped) {
                pendingState.clear()
                null
            } else {
                pendingState.take(bindingGeneration)
            }
        } ?: return@Runnable
        try {
            onState(state)
        } catch (error: Exception) {
            HookLogger.e(TAG, "State apply failed", error)
        }
    }

    private fun createCallback(generation: Long) = object : IAodLyricCallback.Stub() {
        override fun onConfiguration(configuration: Bundle?) {
            if (configuration == null) return
            synchronized(this@AodLyricClient) {
                if (stopped || generation != bindingGeneration) return
            }
            // This callback is one-way Binder. Never retain its Bundle past this method.
            val ownedPayload = try {
                CompiledCustomizationBundleCodec.snapshotFromBundle(configuration)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Rejected malformed configuration payload", error)
                return
            }
            if (ownedPayload == null) {
                HookLogger.w(TAG, "Rejected invalid configuration payload")
                return
            }
            synchronized(this@AodLyricClient) {
                if (stopped || !pendingConfiguration.offer(
                        generation = generation,
                        currentGeneration = bindingGeneration,
                        value = ownedPayload
                    )
                ) return
            }
            mainHandler.removeCallbacks(deliverConfiguration)
            mainHandler.post(deliverConfiguration)
        }

        override fun onState(state: Bundle?) {
            if (state == null) return
            synchronized(this@AodLyricClient) {
                if (stopped || generation != bindingGeneration) return
            }
            // Decode while Binder owns the Bundle; only the immutable message crosses callback return.
            val ownedMessage = try {
                AodStateWireBundleCodec.snapshotFromBundle(state)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Rejected malformed state payload", error)
                return
            }
            if (ownedMessage == null) {
                HookLogger.w(TAG, "Rejected invalid state payload")
                return
            }
            synchronized(this@AodLyricClient) {
                if (stopped) return
                val pending = pendingState.peek(bindingGeneration)
                if (!shouldReplacePendingState(pending, ownedMessage)) {
                    HookLogger.i(
                        TAG,
                        "Keepalive coalesced behind snapshot revision=${ownedMessage.revision}"
                    )
                    return
                }
                if (!pendingState.offer(
                        generation = generation,
                        currentGeneration = bindingGeneration,
                        value = ownedMessage
                    )
                ) return
            }
            mainHandler.removeCallbacks(deliverState)
            mainHandler.post(deliverState)
        }
    }

    private fun createConnection(generation: Long, registeredCallback: IAodLyricCallback) =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (stopped || generation != bindingGeneration || connection !== this) return
                mainHandler.removeCallbacks(connectWatchdog)
                val remote = IAodLyricBridge.Stub.asInterface(service) ?: run {
                    HookLogger.bootstrap(TAG, "bridge_service_null")
                    resetBindingAndRetry(generation, this)
                    return
                }
                bridge = remote
                HookLogger.bootstrap(TAG, "bridge_service_connected")
                try {
                    remote.registerCallback(registeredCallback)
                    HookLogger.bootstrap(TAG, "bridge_callback_registered")
                    HookLogger.i(TAG, "Bridge connected")
                    retryAttempt = 0
                    reportCapabilities()
                } catch (error: Exception) {
                    HookLogger.bootstrap(TAG, "bridge_callback_registration_failed")
                    HookLogger.w(TAG, "Bridge callback registration failed", error)
                    resetBindingAndRetry(generation, this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                HookLogger.bootstrap(TAG, "bridge_service_disconnected attempt=$bindAttemptCount")
                resetBindingAndRetry(generation, this)
            }
            override fun onBindingDied(name: ComponentName?) {
                HookLogger.bootstrap(TAG, "bridge_binding_died attempt=$bindAttemptCount")
                resetBindingAndRetry(generation, this)
            }
            override fun onNullBinding(name: ComponentName?) {
                HookLogger.bootstrap(TAG, "bridge_null_binding attempt=$bindAttemptCount")
                resetBindingAndRetry(generation, this)
            }
        }

    override fun bind(hostContext: Context?, userId: Int) {
        if (hostContext == null) {
            HookLogger.bootstrap(TAG, "bridge_bind_skipped_no_context")
            HookLogger.w(TAG, "Bind skipped without host context")
            return
        }
        val userUid = userId.toLong() * PER_USER_RANGE
        if (userId < 0 || userUid > Int.MAX_VALUE) {
            HookLogger.bootstrap(TAG, "bridge_bind_skipped_invalid_user")
            HookLogger.w(TAG, "Bind skipped for invalid selected user")
            return
        }
        context = hostContext.applicationContext ?: hostContext
        bindingUser = UserHandle.getUserHandleForUid(userUid.toInt())
        bindUserId = userId
        stopped = false
        HookLogger.bootstrap(TAG, "bridge_bind_requested user=${userId.coerceIn(-1, 99_999)}")
        attemptBind()
    }

    private fun attemptBind() {
        if (stopped || bound) return
        val appContext = context ?: return
        val user = bindingUser ?: return
        val generation = ++bindingGeneration
        bindAttemptCount++
        retryAttempt++
        val registeredCallback = createCallback(generation)
        val serviceConnection = createConnection(generation, registeredCallback)
        callback = registeredCallback
        connection = serviceConnection
        bound = try {
            appContext.bindServiceAsUser(
                Intent().setComponent(ComponentName(APP_PACKAGE, SERVICE_CLASS)),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
                user
            )
        } catch (error: Exception) {
            HookLogger.w(TAG, "Bind failed", error)
            false
        }
        if (shouldLogBindAttempt(retryAttempt)) {
            HookLogger.bootstrap(
                TAG,
                if (bound) {
                    "bridge_bind_accepted attempt=$bindAttemptCount"
                } else {
                    "bridge_bind_rejected attempt=$bindAttemptCount " +
                        "user=${bindUserId.coerceIn(-1, 99_999)}"
                }
            )
        }
        if (!bound) {
            callback = null
            connection = null
            scheduleRetry()
        } else {
            mainHandler.removeCallbacks(connectWatchdog)
            mainHandler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
        }
    }

    private fun onConnectWatchdog() {
        if (stopped || !bound || bridge != null) return
        HookLogger.bootstrap(
            TAG,
            "bridge_connect_timeout attempt=$bindAttemptCount " +
                "user=${bindUserId.coerceIn(-1, 99_999)}"
        )
    }

    private fun resetBindingAndRetry(generation: Long, serviceConnection: ServiceConnection) {
        mainHandler.post {
            if (stopped || generation != bindingGeneration || connection !== serviceConnection) return@post
            bindingGeneration++
            mainHandler.removeCallbacks(connectWatchdog)
            mainHandler.removeCallbacks(deliverConfiguration)
            mainHandler.removeCallbacks(deliverState)
            synchronized(this) {
                pendingConfiguration.clear()
                pendingState.clear()
            }
            bridge = null
            callback = null
            connection = null
            lastNoBridgeReportElapsedMs = -1L
            if (bound) context?.let {
                try {
                    it.unbindService(serviceConnection)
                } catch (_: Exception) {
                }
            }
            bound = false
            try {
                onDisconnected()
            } catch (_: Exception) {
            }
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (stopped) return
        mainHandler.removeCallbacks(retry)
        mainHandler.postDelayed(retry, retryDelayMs(retryAttempt))
    }

    override fun unbind() {
        stopped = true
        bindingGeneration++
        mainHandler.removeCallbacks(connectWatchdog)
        mainHandler.removeCallbacks(retry)
        mainHandler.removeCallbacks(deliverConfiguration)
        mainHandler.removeCallbacks(deliverState)
        synchronized(this) {
            pendingConfiguration.clear()
            pendingState.clear()
        }
        try {
            callback?.let {
                try {
                    bridge?.unregisterCallback(it)
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                val serviceConnection = connection
                if (bound && serviceConnection != null) {
                    context?.let {
                        try {
                            it.unbindService(serviceConnection)
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                bridge = null
                callback = null
                connection = null
                retryAttempt = 0
                bindAttemptCount = 0
                lastCapabilityReportGeneration = -1L
                lastNoBridgeReportElapsedMs = -1L
                context = null
                bindingUser = null
                bound = false
            }
        }
    }

    override fun reportCapabilities() {
        val remote = bridge
        if (remote == null) {
            val now = SystemClock.elapsedRealtime()
            if (lastNoBridgeReportElapsedMs < 0L ||
                now - lastNoBridgeReportElapsedMs >= NO_BRIDGE_REPORT_MARKER_INTERVAL_MS
            ) {
                lastNoBridgeReportElapsedMs = now
                HookLogger.bootstrap(TAG, "capability_report_skipped_no_bridge")
            }
            return
        }
        try {
            val snapshot = XiaomiCapabilityResolver.snapshot()
            remote.reportCapabilities(XiaomiCapabilityBundleCodec.toBundle(snapshot))
            if (lastCapabilityReportGeneration != bindingGeneration) {
                lastCapabilityReportGeneration = bindingGeneration
                // The probe summary emitted at package load samples the default class loader
                // only, before the AOD dex appears, so it under-reports. This one describes the
                // snapshot actually delivered to the app.
                val present = snapshot.rawProbes.values.count { it }
                HookLogger.bootstrap(
                    TAG,
                    "capability_report_sent probes=$present/${snapshot.rawProbes.size} " +
                        "profile=${snapshot.profileState.wireValue} " +
                        "missing=${missingProbeNames(snapshot.rawProbes)}"
                )
            }
        } catch (error: Exception) {
            HookLogger.bootstrap(TAG, "capability_report_failed")
            HookLogger.w(TAG, "Capability report failed", error)
        }
    }

    companion object {
        private const val TAG = "AodLyricClient"
        private const val APP_PACKAGE = "com.eza.hyperglow"
        private const val SERVICE_CLASS = "com.eza.hyperglow.aod.AodLyricBridgeService"
        private const val PER_USER_RANGE = 100_000L
        private const val NO_BRIDGE_REPORT_MARKER_INTERVAL_MS = 5_000L
        private const val CONNECT_WATCHDOG_MS = 5_000L
    }
}

internal fun retryDelayMs(attempt: Int): Long {
    if (attempt <= 0) return RETRY_DELAY_BASE_MS
    var delay = RETRY_DELAY_BASE_MS
    repeat((attempt - 1).coerceAtMost(5)) {
        delay = (delay * 2L).coerceAtMost(RETRY_DELAY_CAP_MS)
    }
    return delay
}

internal fun shouldLogBindAttempt(attempt: Int): Boolean = when {
    attempt <= 0 -> false
    attempt <= 3 -> true
    attempt == 5 || attempt == 10 || attempt == 20 -> true
    attempt >= 50 && attempt % 50 == 0 -> true
    else -> false
}
