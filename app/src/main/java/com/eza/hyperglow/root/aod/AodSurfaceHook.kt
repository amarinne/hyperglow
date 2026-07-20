package com.eza.hyperglow.root.aod

import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object AodSurfaceHook {
    private const val AOD_VIEW_CLASS = "com.miui.aod.AODView"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val aodViewClass = runCatching { classLoader.loadClass(AOD_VIEW_CLASS) }.getOrNull() ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val attached = aodViewClass.getDeclaredMethod("onAttachedToWindow")
        val detached = aodViewClass.getDeclaredMethod("onDetachedFromWindow")
        module.deoptimize(attached)
        module.deoptimize(detached)
        module.hook(attached).intercept(AttachedHooker())
        module.hook(detached).intercept(DetachedHooker())
        HookLogger.i(TAG, "Direct AOD hooks installed")
    }

    class AttachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                (chain.thisObject as? ViewGroup)?.let(AodSurfaceController::attach)
            } catch (error: Exception) {
                HookLogger.e(TAG, "Attach failed", error)
            }
            return result
        }
    }

    class DetachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                (chain.thisObject as? ViewGroup)?.let(AodSurfaceController::detach)
            } catch (error: Exception) {
                HookLogger.e(TAG, "Detach failed", error)
            }
            return result
        }
    }

    private const val TAG = "AodSurfaceHook"
}
