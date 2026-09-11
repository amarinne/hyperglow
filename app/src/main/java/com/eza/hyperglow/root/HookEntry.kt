package com.eza.hyperglow.root

import android.app.Application
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.root.aod.AodBrightnessController
import com.eza.hyperglow.root.aod.AodBrightnessHook
import com.eza.hyperglow.root.aod.AodDisplayStateHook
import com.eza.hyperglow.root.aod.AodLifetimeController
import com.eza.hyperglow.root.aod.AodLifetimeHook
import com.eza.hyperglow.root.aod.AodOrientationMonitor
import com.eza.hyperglow.root.aod.AodPositionHook
import com.eza.hyperglow.root.aod.AodSurfaceHook
import com.eza.hyperglow.root.aod.AodWakeBroker
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.capability.missingProbeNames
import com.eza.hyperglow.root.lockscreen.LockscreenSurfaceHook
import com.eza.hyperglow.root.lockscreen.LockscreenEditorGestureHook
import com.eza.hyperglow.root.lockscreen.RaiseToAodHook
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.transition.LinkageTransitionHook
import com.eza.hyperglow.root.transition.SystemUiClockMorphHook
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
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
        reportDefaultLoaderProbes()
        installDefaultLoaderHooks(this, param.defaultClassLoader)
        installAodHooks(this, param.defaultClassLoader)
        installClassLoaderHook(this)
    }

    /**
     * Rejects reload while a lyric session owns AOD lifetime/power; otherwise retires
     * this generation's hooks and pending callbacks so the new generation starts clean.
     * Only primitives cross the reload boundary via [HotReloadingParam.setSavedInstanceState].
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (AodLifetimeController.isLyricActive()) {
            HookLogger.w(TAG, "Hot reload rejected: lyric session active")
            return false
        }
        AodLifetimeController.cancelPendingForReload()
        AodBrightnessController.cancelPendingForReload()
        AodOrientationMonitor.stop()
        val retired = HookRegistry.retireGeneration()
        param.setSavedInstanceState(BuildConfig.VERSION_CODE)
        HookLogger.bootstrap(
            TAG,
            "hot_reload_accepted retired=$retired generation=${HookRegistry.currentGeneration()}"
        )
        return true
    }

    /**
     * Package callbacks are not replayed after reload, so the new generation unhooks
     * previous handles and reinstalls from the live host application. A SystemUI
     * restart remains the supported path until manual reload passes repeatedly.
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        HookLogger.module = this
        val unhooked = HookRegistry.unhookPrevious(param.oldHookHandles)
        val savedVersion = param.savedInstanceState as? Int
        val application = currentSystemUiApplication()
        if (application == null) {
            HookLogger.e(
                TAG,
                "Hot reload reinstall unavailable: host application not found, " +
                    "unhooked=$unhooked restart SystemUI"
            )
            return
        }
        val classLoader = application.classLoader
        XiaomiCapabilityResolver.observeDefaultLoader(classLoader)
        XiaomiCapabilityResolver.observeAodLoader(classLoader)
        reportDefaultLoaderProbes()
        installDefaultLoaderHooks(this, classLoader)
        installAodHooks(this, classLoader)
        installClassLoaderHook(this)
        SystemUiLifecycleHook.bootstrap(application)
        HookLogger.bootstrap(
            TAG,
            "hot_reloaded unhooked=$unhooked active=${HookRegistry.activeCount()} " +
                "savedVersion=$savedVersion"
        )
    }

    private fun reportDefaultLoaderProbes() {
        val capabilityReport = XiaomiCapabilityResolver.snapshot()
        val presentProbes = capabilityReport.rawProbes.values.count { it }
        // Default-loader probes only. The AOD dex is not loaded yet, so this is an early lower
        // bound, not the effective profile; `capability_report_sent` carries the settled counts.
        HookLogger.bootstrap(
            TAG,
            "systemui_capability_probes_default_loader probes=$presentProbes/" +
                "${capabilityReport.rawProbes.size} " +
                "profile=${capabilityReport.profileState.wireValue} " +
                "missing=${missingProbeNames(capabilityReport.rawProbes)}"
        )
    }

    private class ClassLoaderHooker(private val module: XposedModule) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val loader = chain.thisObject as? ClassLoader ?: return result
            XiaomiCapabilityResolver.observeAodLoader(loader)
            SystemUiLyricProjectionRuntime.projection.reportCapabilities()
            installAodHooks(module, loader)
            return result
        }
    }

    companion object {
        private const val TAG = "HookEntry"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LIBXPOSED_MIN_API = 101
        private const val LIBXPOSED_TARGET_API = 102
        private const val CLASS_LOADER_FEATURE_ID = "classloader"

        private fun processClass(processName: String): String = when {
            processName.isBlank() -> "unknown"
            processName.contains(':') -> "secondary"
            processName == SYSTEM_UI_PACKAGE -> "primary"
            else -> "unexpected"
        }

        private fun installDefaultLoaderHooks(module: XposedModule, classLoader: ClassLoader) {
            try {
                SystemUiLifecycleHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "SystemUI lifecycle hooks unavailable", error)
            }
            try {
                LockscreenSurfaceHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Lockscreen hooks unavailable", error)
            }
            try {
                LinkageTransitionHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Linkage hook unavailable", error)
            }
            try {
                SystemUiClockMorphHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "SystemUI clock morph geometry hook unavailable", error)
            }
            try {
                RaiseToAodHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Raise-to-AOD hook unavailable", error)
            }
            try {
                LockscreenEditorGestureHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "Lockscreen editor gesture hook unavailable", error)
            }
        }

        private fun installAodHooks(module: XposedModule, classLoader: ClassLoader) {
            try {
                AodSurfaceHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD hook unavailable", error)
            }
            try {
                AodLifetimeHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD lifetime hook unavailable", error)
            }
            try {
                AodBrightnessHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD brightness hook unavailable", error)
            }
            try {
                AodPositionHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD position hook unavailable", error)
            }
            try {
                AodDisplayStateHook.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD display-state hook unavailable", error)
            }
            try {
                AodWakeBroker.install(module, classLoader)
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD wake broker unavailable", error)
            }
        }

        private fun installClassLoaderHook(module: XposedModule) {
            try {
                val loaderClass = Class.forName("dalvik.system.BaseDexClassLoader")
                for (constructor in loaderClass.declaredConstructors) {
                    HookRegistry.hook(
                        module,
                        CLASS_LOADER_FEATURE_ID,
                        constructor,
                        // Captured per install so a reloaded generation never reuses
                        // the previous generation's module reference.
                        ClassLoaderHooker(module)
                    )
                }
                HookLogger.i(TAG, "Dynamic class-loader hooks installed")
            } catch (error: Exception) {
                HookLogger.e(TAG, "Class-loader hook failed", error)
            }
        }

        /** Re-derives the live host application; null fails closed to a SystemUI restart. */
        private fun currentSystemUiApplication(): Application? = runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            threadClass.getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }
}
