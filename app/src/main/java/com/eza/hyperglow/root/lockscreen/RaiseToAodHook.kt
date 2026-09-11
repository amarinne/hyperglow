package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.aod.AodWakeBroker
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

internal object RaiseToAodController {
    @Volatile
    private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun shouldSuppress(details: String?): Boolean = shouldSuppressPickupWake(
        enabled = enabled,
        wakeHookSupported = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.RAISE_TO_AOD),
        details = details
    )
}

internal object RaiseToAodHook {
    private var installed = false

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        val powerManager = classLoader.loadClass(POWER_MANAGER)
        val wakeUp = powerManager.getDeclaredMethod(
            "wakeUp",
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        HookRegistry.hook(module, FEATURE_ID, wakeUp, WakeUpHooker)
        installed = true
        HookLogger.i(TAG, "Pickup wake remap hook installed")
    }

    private object WakeUpHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val details = chain.args.getOrNull(1) as? String
            if (!RaiseToAodController.shouldSuppress(details)) return chain.proceed()
            if (AodWakeBroker.requestPickupWake()) {
                HookLogger.i(TAG, "Requested pickup AOD and suppressed full wake")
                return null
            } else {
                // If the wake broker cannot dispatch (for example while Xiaomi's AOD master
                // switch is disabled or the host has already been torn down), preserve the
                // platform pickup wake. Suppressing it without a successful AOD request leaves
                // the user with no visible wake at all.
                HookLogger.i(TAG, "Pickup AOD unavailable; allowing full wake")
                return chain.proceed()
            }
        }
    }

    private const val POWER_MANAGER = "android.os.PowerManager"
    private const val FEATURE_ID = "raise-to-aod"
    private const val TAG = "RaiseToAodHook"
}

internal fun shouldSuppressPickupWake(
    enabled: Boolean,
    wakeHookSupported: Boolean,
    details: String?
): Boolean = enabled && wakeHookSupported && details == PICKUP_WAKE_DETAILS

private const val PICKUP_WAKE_DETAILS = "com.android.systemui:PICK_UP"
