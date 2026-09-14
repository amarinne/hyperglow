package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
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
        val method = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(DOZE_SERVICE_CLASS, "setDozeScreenState", "int")
        ) ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        HookRegistry.hook(module, FEATURE_ID, method, DisplayStateHooker)
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
    private const val FEATURE_ID = "aod-display"
    private const val TAG = "AodDisplayStateHook"
}
