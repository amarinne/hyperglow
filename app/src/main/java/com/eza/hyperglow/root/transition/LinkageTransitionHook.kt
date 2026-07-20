package com.eza.hyperglow.root.transition

import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

internal object LinkageTransitionHook {
    private val directionDebouncer = LinkageDirectionDebouncer()
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
        val primary = controller?.let { owner ->
            runCatching {
                owner.getDeclaredMethod(
                    "linkageViewAnim\$default",
                    owner,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
            }.getOrNull()
        }
        if (primary != null) {
            runCatching {
                module.deoptimize(primary)
                module.hook(primary).intercept(PrimaryHooker)
            }.onSuccess {
                HookLogger.i(TAG, "Primary linkage direction hook installed")
            }.onFailure {
                HookLogger.w(TAG, "Primary linkage direction hook failed", it)
            }
        }
        val animationHelper = runCatching { classLoader.loadClass(ANIMATION_HELPER_CLASS) }.getOrNull()
            ?: return
        runCatching {
            animationHelper.getDeclaredMethod(
                "doAnimationToAod",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }.onSuccess { fallback ->
            runCatching {
                module.deoptimize(fallback)
                module.hook(fallback).intercept(FallbackHooker)
            }.onSuccess {
                HookLogger.i(TAG, "Fallback linkage direction hook installed")
            }.onFailure {
                HookLogger.w(TAG, "Fallback linkage direction hook failed", it)
            }
        }
    }

    private object PrimaryHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val toLockscreen = chain.args.getOrNull(1) as? Boolean
            HookLogger.i(
                TAG,
                "Primary linkage invoked args=${chain.args.size} toLockscreen=$toLockscreen"
            )
            dispatchDirection(toLockscreen)
            return chain.proceed()
        }
    }

    private object FallbackHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val toAod = chain.args.firstOrNull() as? Boolean
            val toLockscreen = toAod?.not()
            HookLogger.i(
                TAG,
                "Fallback linkage invoked args=${chain.args.size} toAod=$toAod " +
                    "toLockscreen=$toLockscreen"
            )
            dispatchDirection(toLockscreen)
            return chain.proceed()
        }
    }

    private fun dispatchDirection(toLockscreen: Boolean?) {
        if (toLockscreen != null &&
            directionDebouncer.accept(toLockscreen, SystemClock.elapsedRealtime())
        ) {
            LinkageTransitionCoordinator.onLinkage(toLockscreen)
        }
    }

    private const val CONTROLLER_CLASS = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val ANIMATION_HELPER_CLASS = "com.android.keyguard.clock.animation.AnimationHelper"
    private const val TAG = "LinkageTransitionHook"
}
