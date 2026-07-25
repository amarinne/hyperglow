package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

internal object AodWakeBroker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    // Latest verified host remains usable after Xiaomi tears down the visible AOD plugin instance.
    // One strong reference is intentional: it is the recovery seam that can recreate sleeping AOD.
    private var host: Any? = null
    private var fireAodStateMethod: Method? = null
    private var powerManager: PowerManager? = null
    private var lastRequestElapsedMs = Long.MIN_VALUE
    private var unavailableLogged = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val triggersClass = runCatching { classLoader.loadClass(DOZE_TRIGGERS_CLASS) }.getOrNull()
            ?: return
        val hostField = triggersClass.getDeclaredField("mHost").apply { isAccessible = true }
        val contextField = triggersClass.getDeclaredField("mContext").apply { isAccessible = true }
        val fireAodState = classLoader.loadClass(DOZE_HOST_CLASS).getDeclaredMethod(
            "fireAodState",
            Boolean::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        if (!hookedClassLoaders.add(classLoader)) return
        for (constructor in triggersClass.declaredConstructors) {
            constructor.isAccessible = true
            module.hook(constructor).intercept(
                DozeTriggersConstructorHooker(hostField, contextField, fireAodState)
            )
        }
        HookLogger.i(
            TAG,
            "AOD wake broker hook installed constructors=${triggersClass.declaredConstructors.size}"
        )
    }

    fun requestWake(signal: Long): Boolean = enqueueWake(signal, "lyrics")

    fun requestPickupWake(): Boolean = enqueueWake(
        signal = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        source = "pickup"
    )

    private fun enqueueWake(signal: Long, source: String): Boolean {
        if (signal == 0L || !XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_WAKE_BROKER
            )
        ) return false
        val wakeHost = host
        val method = fireAodStateMethod
        val wakePowerManager = powerManager
        if (wakeHost == null || method == null || wakePowerManager == null ||
            wakePowerManager.isInteractive
        ) {
            if (!unavailableLogged &&
                (wakeHost == null || method == null || wakePowerManager == null)
            ) {
                unavailableLogged = true
                HookLogger.w(TAG, "AOD wake host unavailable source=$source")
            }
            return false
        }
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (lastRequestElapsedMs != Long.MIN_VALUE &&
                now - lastRequestElapsedMs < MIN_REQUEST_INTERVAL_MS
            ) return@post
            val wakeHost = host
            val method = fireAodStateMethod
            val wakePowerManager = powerManager
            if (wakeHost == null || method == null || wakePowerManager == null) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    HookLogger.w(TAG, "AOD wake host unavailable source=$source")
                }
                return@post
            }
            if (wakePowerManager.isInteractive) return@post
            try {
                method.invoke(wakeHost, true, WAKE_REASON)
                lastRequestElapsedMs = now
                HookLogger.i(
                    TAG,
                    "AOD wake dispatched signal=$signal source=$source reason=$WAKE_REASON"
                )
            } catch (error: Exception) {
                (error as? java.lang.reflect.InvocationTargetException)
                    ?.cause
                    ?.let { if (it is Error) throw it }
                HookLogger.w(TAG, "AOD wake dispatch failed", error)
            }
        }
        return true
    }

    private class DozeTriggersConstructorHooker(
        private val hostField: java.lang.reflect.Field,
        private val contextField: java.lang.reflect.Field,
        private val fireAodState: Method
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                val owner = chain.thisObject ?: return result
                val host = hostField.get(owner) ?: return result
                val context = contextField.get(owner) as? android.content.Context ?: return result
                val powerManager = context.getSystemService(PowerManager::class.java) ?: return result
                AodWakeBroker.host = host
                fireAodStateMethod = fireAodState
                AodWakeBroker.powerManager = powerManager
                unavailableLogged = false
                HookLogger.i(TAG, "AOD wake host captured class=${host.javaClass.name}")
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD wake host capture failed", error)
            }
            return result
        }
    }

    private const val DOZE_TRIGGERS_CLASS = "com.miui.aod.doze.DozeTriggers"
    private const val DOZE_HOST_CLASS = "com.miui.aod.DozeHost"
    private const val WAKE_REASON = "reason_keycode_goto"
    private const val MIN_REQUEST_INTERVAL_MS = 750L
    private const val TAG = "AodWakeBroker"
}
