package com.eza.hyperglow.root

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.aod.AodPowerCoordinator
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

internal object SystemUiLifecycleHook {
    private val mainHandler = Handler(Looper.getMainLooper())
    fun install(module: XposedModule, classLoader: ClassLoader) {
        try {
            val applicationClass = SymbolResolver.resolveClass(
                classLoader, "systemui-lifecycle", SYSTEM_UI_APPLICATION
            ) ?: throw ClassNotFoundException(SYSTEM_UI_APPLICATION)
            val onCreate = SymbolResolver.resolveMethod(
                classLoader,
                "systemui-lifecycle",
                SymbolRequest.method(SYSTEM_UI_APPLICATION, "onCreate")
            ) ?: throw NoSuchMethodException("onCreate")
            HookRegistry.hook(module, FEATURE_ID, onCreate, ApplicationCreateHooker)
            HookLogger.bootstrap(TAG, "systemui_application_hook_installed")
        } catch (error: Exception) {
            HookLogger.bootstrap(TAG, "systemui_application_hook_failed")
            throw error
        }
        try {
            val setUserId = SymbolResolver.resolveMethod(
                classLoader,
                "systemui-lifecycle",
                SymbolRequest.method(USER_TRACKER_IMPL, "setUserIdInternal", "int")
            ) ?: throw NoSuchMethodException("setUserIdInternal")
            HookRegistry.hook(module, FEATURE_ID, setUserId, UserChangedHooker)
            HookLogger.bootstrap(TAG, "systemui_user_tracker_hook_installed")
        } catch (error: Exception) {
            HookLogger.bootstrap(TAG, "systemui_user_tracker_hook_failed")
            throw error
        }
        HookLogger.i(TAG, "SystemUI bootstrap/user hooks installed")
    }

    /** Re-runs the application-create bootstrap for a re-derived host, e.g. after hot reload. */
    fun bootstrap(application: Application) {
        XiaomiCapabilityResolver.observeContext(application)
        SymbolResolver.observeContext(application)
        SystemUiLyricProjectionRuntime.projection.bootstrap(application)
        HookLogger.bootstrap(TAG, "systemui_projection_bootstrapped")
        SystemUiLyricProjectionRuntime.projection.attach(AodPowerCoordinator, application)
        HookLogger.bootstrap(TAG, "systemui_power_subscriber_attached")
    }

    private object ApplicationCreateHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            HookLogger.bootstrap(TAG, "systemui_application_oncreate_entered")
            val result = chain.proceed()
            val application = chain.thisObject as? Application ?: return result
            bootstrap(application)
            return result
        }
    }

    private object UserChangedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val userId = chain.args.firstOrNull() as? Int
            mainHandler.post {
                SystemUiLyricProjectionRuntime.projection.onUserChanged(userId)
            }
            return chain.proceed()
        }
    }

    private const val SYSTEM_UI_APPLICATION = "com.android.systemui.SystemUIApplication"
    private const val USER_TRACKER_IMPL = "com.android.systemui.settings.UserTrackerImpl"
    private const val FEATURE_ID = "systemui-lifecycle"
    private const val TAG = "SystemUiLifecycle"
}
