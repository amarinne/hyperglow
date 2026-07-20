package com.eza.hyperglow.root.aod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import com.eza.hyperglow.aod.AodStateWireBundleCodec
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.aod.IAodLyricBridge
import com.eza.hyperglow.aod.IAodLyricCallback
import com.eza.hyperglow.aod.XiaomiCapabilityBundleCodec
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec.WirePayload
import com.eza.hyperglow.root.projection.LyricProjectionClient

internal class GenerationBoundLatest<T> {
    private var generation = -1L
    private var value: T? = null

    fun offer(generation: Long, currentGeneration: Long, value: T): Boolean {
        if (generation != currentGeneration) return false
        this.generation = generation
        this.value = value
        return true
    }

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
                if (stopped || !pendingState.offer(
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
                val remote = IAodLyricBridge.Stub.asInterface(service) ?: run {
                    resetBindingAndRetry(generation, this)
                    return
                }
                bridge = remote
                try {
                    remote.registerCallback(registeredCallback)
                    HookLogger.i(TAG, "Bridge connected")
                    reportCapabilities()
                } catch (_: Exception) {
                    resetBindingAndRetry(generation, this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) =
                resetBindingAndRetry(generation, this)
            override fun onBindingDied(name: ComponentName?) =
                resetBindingAndRetry(generation, this)
            override fun onNullBinding(name: ComponentName?) =
                resetBindingAndRetry(generation, this)
        }

    override fun bind(hostContext: Context?, userId: Int) {
        if (hostContext == null) {
            HookLogger.w(TAG, "Bind skipped without host context")
            return
        }
        val userUid = userId.toLong() * PER_USER_RANGE
        if (userId < 0 || userUid > Int.MAX_VALUE) {
            HookLogger.w(TAG, "Bind skipped for invalid selected user")
            return
        }
        context = hostContext.applicationContext ?: hostContext
        bindingUser = UserHandle.getUserHandleForUid(userUid.toInt())
        stopped = false
        attemptBind()
    }

    private fun attemptBind() {
        if (stopped || bound) return
        val appContext = context ?: return
        val user = bindingUser ?: return
        val generation = ++bindingGeneration
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
        if (!bound) {
            callback = null
            connection = null
            scheduleRetry()
        }
    }

    private fun resetBindingAndRetry(generation: Long, serviceConnection: ServiceConnection) {
        mainHandler.post {
            if (stopped || generation != bindingGeneration || connection !== serviceConnection) return@post
            bindingGeneration++
            mainHandler.removeCallbacks(deliverConfiguration)
            mainHandler.removeCallbacks(deliverState)
            synchronized(this) {
                pendingConfiguration.clear()
                pendingState.clear()
            }
            bridge = null
            callback = null
            connection = null
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
        mainHandler.postDelayed(retry, RETRY_DELAY_MS)
    }

    override fun unbind() {
        stopped = true
        bindingGeneration++
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
                context = null
                bindingUser = null
                bound = false
            }
        }
    }

    override fun reportCapabilities() {
        val remote = bridge ?: return
        try {
            remote.reportCapabilities(
                XiaomiCapabilityBundleCodec.toBundle(XiaomiCapabilityResolver.snapshot())
            )
        } catch (error: Exception) {
            HookLogger.w(TAG, "Capability report failed", error)
        }
    }

    companion object {
        private const val TAG = "AodLyricClient"
        private const val APP_PACKAGE = "com.eza.hyperglow"
        private const val SERVICE_CLASS = "com.eza.hyperglow.aod.AodLyricBridgeService"
        private const val PER_USER_RANGE = 100_000L
        private const val RETRY_DELAY_MS = 1_000L
    }
}
