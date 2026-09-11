package com.eza.hyperglow.root.aod

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object AodSurfaceHook {
    private const val AOD_VIEW_CLASS = "com.miui.aod.AODView"
    private const val FEATURE_ID = "aod-surface"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private var visibilitySeamInstalled = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        // Framework seam: enforced once per process, not per loader.
        if (!visibilitySeamInstalled) {
            visibilitySeamInstalled = true
            runCatching {
                val setVisibility = View::class.java.getDeclaredMethod(
                    "setVisibility",
                    Int::class.javaPrimitiveType
                )
                HookRegistry.hook(module, FEATURE_ID, setVisibility, StockVisibilityHooker)
                HookLogger.bootstrap(TAG, "stock_visibility_seam_installed")
                HookLogger.i(TAG, "Stock visibility enforcement seam installed")
            }.onFailure { error ->
                HookLogger.w(TAG, "Stock visibility enforcement unavailable", error)
            }
        }
        val aodViewClass = runCatching { classLoader.loadClass(AOD_VIEW_CLASS) }.getOrNull() ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val attached = aodViewClass.getDeclaredMethod("onAttachedToWindow")
        val detached = aodViewClass.getDeclaredMethod("onDetachedFromWindow")
        HookRegistry.hook(module, FEATURE_ID, attached, AttachedHooker())
        HookRegistry.hook(module, FEATURE_ID, detached, DetachedHooker())
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

    /**
     * Upstream suppress seam. Xiaomi re-shows its clock container through its own
     * show paths while lyrics play, so a visibility flag alone does not stick:
     * any non-GONE request for our suppressed container is forced back to GONE
     * at the call boundary. GONE requests pass through, so no recursion.
     */
    private object StockVisibilityHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val requested = chain.args.firstOrNull() as? Int
            if (requested != null && requested != View.GONE &&
                AodSurfaceController.isStockSuppressEnforced(chain.thisObject)
            ) {
                return chain.proceed(arrayOf(View.GONE))
            }
            return chain.proceed()
        }
    }

    private const val TAG = "AodSurfaceHook"
}
