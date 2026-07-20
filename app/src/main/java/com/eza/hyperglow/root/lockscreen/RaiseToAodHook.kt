package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.HookLogger
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
        supported = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.RAISE_TO_AOD),
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
        module.deoptimize(wakeUp)
        module.hook(wakeUp).intercept(WakeUpHooker)
        installed = true
        HookLogger.i(TAG, "Pickup wake remap hook installed")
    }

    private object WakeUpHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val details = chain.args.getOrNull(1) as? String
            if (!RaiseToAodController.shouldSuppress(details)) return chain.proceed()
            HookLogger.i(TAG, "Suppressed full pickup wake; retaining stock AOD")
            return null
        }
    }

    private const val POWER_MANAGER = "android.os.PowerManager"
    private const val TAG = "RaiseToAodHook"
}

internal fun shouldSuppressPickupWake(
    enabled: Boolean,
    supported: Boolean,
    details: String?
): Boolean = enabled && supported && details == PICKUP_WAKE_DETAILS

private const val PICKUP_WAKE_DETAILS = "com.android.systemui:PICK_UP"
