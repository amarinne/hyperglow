package com.eza.hyperglow.root

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.root.aod.AodPowerCoordinator
import com.eza.hyperglow.root.aod.AodWakeBroker
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections

internal object SystemUiLifecycleHook {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loggedMissingSeams = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Two independent installs on purpose. They used to share one `try` block whose first failure
     * rethrew, so a build that renamed the application class silently lost the user-tracker hook as
     * well — and the application seam is the one that boots the projection runtime on a cold
     * SystemUI start.
     */
    fun install(module: XposedModule, classLoader: ClassLoader) {
        installApplicationHook(module, classLoader)
        installUserTrackerHook(module, classLoader)
    }

    private fun installApplicationHook(module: XposedModule, classLoader: ClassLoader) {
        val applicationClass = SymbolResolver.resolveClassInOrder(
            classLoader,
            FEATURE_ID,
            APPLICATION_CLASS_CANDIDATES
        )
        if (applicationClass == null) {
            installApplicationFactoryDiscovery(module, classLoader)
            return
        }
        val onCreate = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(applicationClass.name, "onCreate")
        )
        if (onCreate == null) {
            missingSeam(classLoader, "${applicationClass.name}#onCreate")
            return
        }
        runCatching { HookRegistry.hook(module, FEATURE_ID, onCreate, ApplicationCreateHooker) }
            .onSuccess {
                HookLogger.bootstrap(
                    TAG,
                    "systemui_application_hook_installed class=${applicationClass.name} " +
                        "loader=${loaderTag(classLoader)}"
                )
            }
            .onFailure { HookLogger.w(TAG, "systemui_application_hook_failed", it) }
    }

    /**
     * Last resort when no known application class name resolves: the component factory instantiates
     * the application before `onCreate` runs, so the class it returns can still be hooked in time.
     * The returned object is not used for bootstrapping itself; the factory runs before the context
     * is attached and before any system service is reachable.
     */
    private fun installApplicationFactoryDiscovery(module: XposedModule, classLoader: ClassLoader) {
        val instantiate = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(
                APP_COMPONENT_FACTORY,
                "instantiateApplication",
                "java.lang.ClassLoader",
                "java.lang.String"
            )
        )
        if (instantiate == null) {
            missingSeam(classLoader, "application-class candidates=${APPLICATION_CLASS_CANDIDATES.joinToString(",")}")
            return
        }
        runCatching {
            HookRegistry.hook(module, FEATURE_ID, instantiate, ApplicationFactoryHooker(module))
        }.onSuccess {
            HookLogger.bootstrap(TAG, "systemui_application_factory_hook_installed")
        }.onFailure { HookLogger.w(TAG, "systemui_application_factory_hook_failed", it) }
    }

    private fun installUserTrackerHook(module: XposedModule, classLoader: ClassLoader) {
        val setUserId = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(USER_TRACKER_IMPL, "setUserIdInternal", "int")
        )
        if (setUserId == null) {
            missingSeam(classLoader, "$USER_TRACKER_IMPL#setUserIdInternal(int)")
            return
        }
        runCatching { HookRegistry.hook(module, FEATURE_ID, setUserId, UserChangedHooker) }
            .onSuccess { HookLogger.bootstrap(TAG, "systemui_user_tracker_hook_installed loader=${loaderTag(classLoader)}") }
            .onFailure { HookLogger.w(TAG, "systemui_user_tracker_hook_failed", it) }
    }

    /**
     * A seam that does not resolve used to be a silent absence: the only trace was a downstream
     * "hooks unavailable" line that did not name the missing member. Name it once per loader so a
     * field log says which build shape changed.
     */
    private fun missingSeam(classLoader: ClassLoader?, seam: String) {
        val tag = classLoader?.let { loaderTag(it) } ?: "unknown"
        if (!loggedMissingSeams.add("$seam@$tag")) return
        HookLogger.w(TAG, "systemui lifecycle seam unresolved $seam loader=$tag")
    }

    /** Re-runs the application-create bootstrap for a re-derived host, e.g. after hot reload. */
    fun bootstrap(application: Application) {
        XiaomiCapabilityResolver.observeContext(application)
        SymbolResolver.observeContext(application)
        AodWakeBroker.observeContext(application)
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

    private class ApplicationFactoryHooker(private val module: XposedModule) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val application = result as? Application ?: return result
            val classLoader = chain.args.firstOrNull() as? ClassLoader
            val onCreate = runCatching {
                application.javaClass.getDeclaredMethod("onCreate")
            }.getOrNull()
            if (onCreate == null) {
                missingSeam(classLoader, "${application.javaClass.name}#onCreate")
                return result
            }
            runCatching { HookRegistry.hook(module, FEATURE_ID, onCreate, ApplicationCreateHooker) }
                .onSuccess {
                    HookLogger.bootstrap(
                        TAG,
                        "systemui_application_hook_installed class=${application.javaClass.name} " +
                            "via=factory loader=${classLoader?.let(::loaderTag) ?: "unknown"}"
                    )
                }
                .onFailure { HookLogger.w(TAG, "systemui_application_hook_failed via=factory", it) }
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

    /**
     * Both application classes are verified: `SystemUIApplication` up to HyperOS 3 / Android 16,
     * `SystemUIApplicationImpl` on HyperOS 4 / Android 17, named by that build's manifest.
     */
    private val APPLICATION_CLASS_CANDIDATES = listOf(
        "com.android.systemui.SystemUIApplication",
        "com.android.systemui.application.impl.SystemUIApplicationImpl"
    )
    private const val APP_COMPONENT_FACTORY =
        "com.android.systemui.SystemUIAppComponentFactoryBase"
    private const val USER_TRACKER_IMPL = "com.android.systemui.settings.UserTrackerImpl"
    private const val FEATURE_ID = "systemui-lifecycle"
    private const val TAG = "SystemUiLifecycle"
}
