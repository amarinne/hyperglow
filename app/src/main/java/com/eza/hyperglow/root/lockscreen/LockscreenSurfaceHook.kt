package com.eza.hyperglow.root.lockscreen

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.readHierarchyField
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
        val sectionClass = runCatching { classLoader.loadClass(SECTION_CLASS) }.getOrNull() ?: return
        val controllerClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
            ?: return
        val constraintLayout = classLoader.loadClass("androidx.constraintlayout.widget.ConstraintLayout")
        if (!hookedClassLoaders.add(classLoader)) return
        val bindData = sectionClass.getDeclaredMethod("bindData", constraintLayout)
        val removeViews = sectionClass.getDeclaredMethod("removeViews", constraintLayout)
        module.deoptimize(bindData)
        module.deoptimize(removeViews)
        module.hook(bindData).intercept(BindHooker)
        module.hook(removeViews).intercept(RemoveHooker)
        hookOptional(module, controllerClass, "onViewAttachedToWindow", AttachedHooker, View::class.java)
        hookOptional(module, controllerClass, "onViewDetachedFromWindow", DetachedHooker, View::class.java)
        hookOptional(module, controllerClass, "updateKeyguardElementsVisibility", RefreshHooker)
        hookOptional(module, controllerClass, "onUpdateNotificationState", NotificationRefreshHooker)
        hookOptional(
            module,
            controllerClass,
            "maybeLockScreenThemeChanged",
            RefreshHooker,
            Boolean::class.javaPrimitiveType
        )
        hookOptional(
            module,
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
        owner: Class<*>,
        name: String,
        hooker: Hooker,
        vararg parameterTypes: Class<*>?
    ) {
        runCatching {
            val method = owner.getDeclaredMethod(name, *parameterTypes)
            module.deoptimize(method)
            module.hook(method).intercept(hooker)
        }.onFailure { HookLogger.w(TAG, "Optional lockscreen hook unavailable: $name", it) }
    }

    private const val SECTION_CLASS = "com.android.keyguard.blueprint.KeyguardPanelViewSection"
    private const val CONTROLLER_CLASS = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val TAG = "LockscreenSurfaceHook"
}
