package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.transition.LinkageTransitionCoordinator
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

internal object AodDisplayStateHook {
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    @Volatile
    private var installed = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val owner = runCatching { classLoader.loadClass(DOZE_SERVICE_CLASS) }.getOrNull()
            ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val method = owner.getDeclaredMethod(
            "setDozeScreenState",
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.deoptimize(method)
        module.hook(method).intercept(DisplayStateHooker)
        installed = true
        HookLogger.i(TAG, "AOD doze-state ownership hook installed")
    }

    fun isInstalled(): Boolean = installed

    private object DisplayStateHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val state = (chain.args.firstOrNull() as? Number)?.toInt()
            val result = chain.proceed()
            if (state != null) {
                LinkageTransitionCoordinator.onAodDisplayState(state)
            }
            return result
        }
    }

    private const val DOZE_SERVICE_CLASS = "com.miui.aod.doze.DozeService"
    private const val TAG = "AodDisplayStateHook"
}
