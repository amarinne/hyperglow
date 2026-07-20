package com.eza.hyperglow.root

import android.app.Application
import com.eza.hyperglow.root.aod.AodSurfaceHook
import com.eza.hyperglow.root.aod.AodLifetimeHook
import com.eza.hyperglow.root.aod.AodPositionHook
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.lockscreen.LockscreenSurfaceHook
import com.eza.hyperglow.root.lockscreen.RaiseToAodHook
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.transition.LinkageTransitionHook
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookLogger.module = this
        HookLogger.i(TAG, "Module loaded")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) return
        val processName = runCatching { Application.getProcessName() }.getOrDefault("")
        if (processName.contains(':')) return

        XiaomiCapabilityResolver.observeDefaultLoader(param.defaultClassLoader)
        XiaomiCapabilityResolver.observeAodLoader(param.defaultClassLoader)
        try {
            SystemUiLifecycleHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "SystemUI lifecycle hooks unavailable", error)
        }

        try {
            LockscreenSurfaceHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Lockscreen hooks unavailable", error)
        }
        try {
            LinkageTransitionHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Linkage hook unavailable", error)
        }
        try {
            RaiseToAodHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Raise-to-AOD hook unavailable", error)
        }

        try {
            AodSurfaceHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Default-loader AOD hook unavailable", error)
        }
        try {
            AodLifetimeHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Default-loader AOD lifetime hook unavailable", error)
        }
        try {
            AodPositionHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Default-loader AOD position hook unavailable", error)
        }
        try {
            val loaderClass = Class.forName("dalvik.system.BaseDexClassLoader")
            for (constructor in loaderClass.declaredConstructors) {
                deoptimize(constructor)
                hook(constructor).intercept(ClassLoaderHooker(this))
            }
            HookLogger.i(TAG, "Dynamic class-loader hooks installed")
        } catch (error: Exception) {
            HookLogger.e(TAG, "Class-loader hook failed", error)
        }
    }

    private class ClassLoaderHooker(private val module: XposedModule) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val loader = chain.thisObject as? ClassLoader ?: return result
            XiaomiCapabilityResolver.observeAodLoader(loader)
            SystemUiLyricProjectionRuntime.projection.reportCapabilities()
            try {
                AodSurfaceHook.install(module, loader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Dynamic-loader AOD hook failed", error)
            }
            try {
                AodLifetimeHook.install(module, loader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Dynamic-loader AOD lifetime hook failed", error)
            }
            try {
                AodPositionHook.install(module, loader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Dynamic-loader AOD position hook failed", error)
            }
            return result
        }
    }

    companion object {
        private const val TAG = "HookEntry"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
