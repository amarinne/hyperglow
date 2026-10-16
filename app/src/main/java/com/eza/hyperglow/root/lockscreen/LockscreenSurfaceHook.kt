package com.eza.hyperglow.root.lockscreen

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.loaderTag
import com.eza.hyperglow.root.readHierarchyField
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

internal object LockscreenSurfaceHook {
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val sectionClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, SECTION_CLASS
        ) ?: return missingSeam(classLoader, SECTION_CLASS)
        val controllerClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, CONTROLLER_CLASS
        ) ?: return missingSeam(classLoader, CONTROLLER_CLASS)
        val constraintLayout = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, "androidx.constraintlayout.widget.ConstraintLayout"
        ) ?: return missingSeam(classLoader, "androidx.constraintlayout.widget.ConstraintLayout")
        if (!hookedClassLoaders.add(classLoader)) return
        val bindData = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(SECTION_CLASS, "bindData", constraintLayout.name)
        )
        val removeViews = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(SECTION_CLASS, "removeViews", constraintLayout.name)
        )
        if (bindData == null) missingSeam(classLoader, "$SECTION_CLASS#bindData")
        if (removeViews == null) missingSeam(classLoader, "$SECTION_CLASS#removeViews")
        bindData?.let { HookRegistry.hook(module, FEATURE_ID, it, BindHooker) }
        removeViews?.let { HookRegistry.hook(module, FEATURE_ID, it, RemoveHooker) }
        hookOptional(module, classLoader, controllerClass, "onViewAttachedToWindow", AttachedHooker, View::class.java)
        hookOptional(module, classLoader, controllerClass, "onViewDetachedFromWindow", DetachedHooker, View::class.java)
        hookOptional(module, classLoader, controllerClass, "updateKeyguardElementsVisibility", RefreshHooker)
        hookNotificationRefresh(module, classLoader)
        hookOptional(
            module,
            classLoader,
            controllerClass,
            "maybeLockScreenThemeChanged",
            RefreshHooker,
            Boolean::class.javaPrimitiveType
        )
        hookOptional(
            module,
            classLoader,
            controllerClass,
            "onLockScreenInfoChange",
            RefreshHooker,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        HookLogger.i(TAG, "Lockscreen lifecycle hooks installed loader=${loaderTag(classLoader)}")
    }

    /**
     * The notification-state refresh, which also tracks the stock media player's presence. Up to
     * HyperOS 3 it ran as the instance method `onUpdateNotificationState()`. HyperOS 4 / Android 17
     * moved the same state update into the synthetic static accessor
     * `access$updateNotificationState(KeyguardPanelViewController, boolean)` — verified on the
     * popsicle OS4.0.2.0 build — so the controller arrives as argument 0 there.
     */
    private fun hookNotificationRefresh(module: XposedModule, classLoader: ClassLoader) {
        val instance = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(CONTROLLER_CLASS, "onUpdateNotificationState")
        )
        if (instance != null) {
            HookRegistry.hook(module, FEATURE_ID, instance, NotificationRefreshHooker)
            return
        }
        val accessor = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(
                CONTROLLER_CLASS,
                "access\$updateNotificationState",
                CONTROLLER_CLASS,
                "boolean"
            )
        )
        if (accessor != null) {
            HookRegistry.hook(module, FEATURE_ID, accessor, StaticNotificationRefreshHooker)
            HookLogger.i(
                TAG,
                "Notification refresh hooked through the HyperOS 4 accessor " +
                    "loader=${loaderTag(classLoader)}"
            )
            return
        }
        missingSeam(classLoader, "$CONTROLLER_CLASS#onUpdateNotificationState")
    }

    /**
     * A required seam that does not resolve used to be a silent absence: the class-miss paths
     * returned before any log line. Name the member once per seam so a field log says which build
     * shape changed.
     */
    private fun missingSeam(classLoader: ClassLoader, seam: String) {
        val tag = loaderTag(classLoader)
        if (!loggedMissingSeams.add("$seam@$tag")) return
        HookLogger.w(TAG, "Lockscreen seam unresolved $seam loader=$tag")
    }

    private object BindHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = readController(chain.thisObject) ?: return result
            LockscreenSurfaceController.attach(controller, chain.args.firstOrNull() as? ViewGroup)
            return result
        }
    }

    private object RemoveHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = readController(chain.thisObject)
            LockscreenSurfaceController.detach(controller, chain.args.firstOrNull() as? ViewGroup)
            return chain.proceed()
        }
    }

    private object AttachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = chain.thisObject ?: return result
            LockscreenSurfaceController.attach(controller, chain.args.firstOrNull() as? ViewGroup)
            return result
        }
    }

    private object DetachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            LockscreenSurfaceController.detach(
                chain.thisObject,
                chain.args.firstOrNull() as? ViewGroup
            )
            return chain.proceed()
        }
    }

    private object RefreshHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            LockscreenSurfaceController.refresh(chain.thisObject)
            return result
        }
    }

    private object NotificationRefreshHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            LockscreenSurfaceController.refreshNotificationState(chain.thisObject)
            return result
        }
    }

    /**
     * The HyperOS 4 seam is a static accessor: the controller is not `thisObject`, it is argument 0.
     * `thisObject` is null for a static method, so the instance hooker would report a stale identity
     * and drop every refresh.
     */
    private object StaticNotificationRefreshHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            LockscreenSurfaceController.refreshNotificationState(chain.args.firstOrNull())
            return result
        }
    }

    private fun readController(section: Any?): Any? =
        readHierarchyField(section, "keyguardViewController")

    private fun hookOptional(
        module: XposedModule,
        classLoader: ClassLoader,
        owner: Class<*>,
        name: String,
        hooker: Hooker,
        vararg parameterTypes: Class<*>?
    ) {
        runCatching {
            val request = SymbolRequest.method(
                owner.name,
                name,
                *parameterTypes.filterNotNull().map { it.name }.toTypedArray()
            )
            SymbolResolver.resolveMethod(classLoader, FEATURE_ID, request)
                ?.let { HookRegistry.hook(module, FEATURE_ID, it, hooker) }
        }.onFailure { HookLogger.w(TAG, "Optional lockscreen hook unavailable: $name", it) }
    }

    private const val SECTION_CLASS = "com.android.keyguard.blueprint.KeyguardPanelViewSection"
    private const val CONTROLLER_CLASS = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val TAG = "LockscreenSurfaceHook"
    private const val FEATURE_ID = "lockscreen-surface"
    private val loggedMissingSeams = Collections.synchronizedSet(mutableSetOf<String>())
}
