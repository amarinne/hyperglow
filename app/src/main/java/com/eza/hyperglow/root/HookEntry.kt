package com.eza.hyperglow.root

import android.app.Application
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.root.aod.AodSurfaceHook
import com.eza.hyperglow.root.aod.AodLifetimeHook
import com.eza.hyperglow.root.aod.AodPositionHook
import com.eza.hyperglow.root.aod.AodDisplayStateHook
import com.eza.hyperglow.root.aod.AodWakeBroker
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.lockscreen.LockscreenSurfaceHook
import com.eza.hyperglow.root.lockscreen.LockscreenEditorGestureHook
import com.eza.hyperglow.root.lockscreen.RaiseToAodHook
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.transition.LinkageTransitionHook
import com.eza.hyperglow.root.transition.SystemUiClockMorphHook
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookLogger.module = this
        HookLogger.bootstrap(
            TAG,
            "module_loaded version=${BuildConfig.VERSION_CODE} " +
                "minApi=$LIBXPOSED_MIN_API targetApi=$LIBXPOSED_TARGET_API " +
                "process=${processClass(param.processName)}"
        )
        HookLogger.i(TAG, "Module loaded")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) return
        val processName = runCatching { Application.getProcessName() }.getOrDefault("")
        HookLogger.bootstrap(TAG, "systemui_package_loaded process=${processClass(processName)}")
        if (processName.contains(':')) {
            HookLogger.bootstrap(TAG, "systemui_secondary_process_skipped")
            return
        }

        XiaomiCapabilityResolver.observeDefaultLoader(param.defaultClassLoader)
        XiaomiCapabilityResolver.observeAodLoader(param.defaultClassLoader)
        val capabilityReport = XiaomiCapabilityResolver.snapshot()
        val presentProbes = capabilityReport.rawProbes.values.count { it }
        // Default-loader probes only. The AOD dex is not loaded yet, so this is an early lower
        // bound, not the effective profile; `capability_report_sent` carries the settled counts.
        HookLogger.bootstrap(
            TAG,
            "systemui_capability_probes_default_loader probes=$presentProbes/" +
                "${capabilityReport.rawProbes.size} profile=${capabilityReport.profileState.wireValue}"
        )
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
            SystemUiClockMorphHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "SystemUI clock morph geometry hook unavailable", error)
        }
        try {
            RaiseToAodHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Raise-to-AOD hook unavailable", error)
        }
        try {
            LockscreenEditorGestureHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Lockscreen editor gesture hook unavailable", error)
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
            AodDisplayStateHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Default-loader AOD display-state hook unavailable", error)
        }
        try {
            AodWakeBroker.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "Default-loader AOD wake broker unavailable", error)
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
            try {
                AodDisplayStateHook.install(module, loader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Dynamic-loader AOD display-state hook failed", error)
            }
            try {
                AodWakeBroker.install(module, loader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Dynamic-loader AOD wake broker failed", error)
            }
            return result
        }
    }

    companion object {
        private const val TAG = "HookEntry"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LIBXPOSED_MIN_API = 101
        private const val LIBXPOSED_TARGET_API = 102

        private fun processClass(processName: String): String = when {
            processName.isBlank() -> "unknown"
            processName.contains(':') -> "secondary"
            processName == SYSTEM_UI_PACKAGE -> "primary"
            else -> "unexpected"
        }
    }
}
