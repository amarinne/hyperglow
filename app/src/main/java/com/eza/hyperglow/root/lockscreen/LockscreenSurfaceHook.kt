package com.eza.hyperglow.root.lockscreen

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
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
        ) ?: return
        val controllerClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, CONTROLLER_CLASS
        ) ?: return
        val constraintLayout = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, "androidx.constraintlayout.widget.ConstraintLayout"
        ) ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val bindData = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(SECTION_CLASS, "bindData", constraintLayout.name)
        )
        val removeViews = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(SECTION_CLASS, "removeViews", constraintLayout.name)
        )
        bindData?.let { HookRegistry.hook(module, FEATURE_ID, it, BindHooker) }
        removeViews?.let { HookRegistry.hook(module, FEATURE_ID, it, RemoveHooker) }
        hookOptional(module, classLoader, controllerClass, "onViewAttachedToWindow", AttachedHooker, View::class.java)
        hookOptional(module, classLoader, controllerClass, "onViewDetachedFromWindow", DetachedHooker, View::class.java)
        hookOptional(module, classLoader, controllerClass, "updateKeyguardElementsVisibility", RefreshHooker)
        hookOptional(module, classLoader, controllerClass, "onUpdateNotificationState", NotificationRefreshHooker)
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
        HookLogger.i(TAG, "Lockscreen lifecycle hooks installed")
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
}
