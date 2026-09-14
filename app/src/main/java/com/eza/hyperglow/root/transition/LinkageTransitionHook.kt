package com.eza.hyperglow.root.transition

import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
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
        val primary = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(
                CONTROLLER_CLASS,
                "linkageViewAnim\$default",
                CONTROLLER_CLASS,
                "boolean",
                "java.lang.String",
                "int"
            )
        )
        if (primary != null) {
            runCatching {
                HookRegistry.hook(module, FEATURE_ID, primary, PrimaryHooker)
            }.onSuccess {
                HookLogger.i(TAG, "Primary linkage direction hook installed")
            }.onFailure {
                HookLogger.w(TAG, "Primary linkage direction hook failed", it)
            }
        }
        val fallback = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(
                ANIMATION_HELPER_CLASS,
                "doAnimationToAod",
                "boolean",
                "boolean",
                "boolean"
            )
        ) ?: return
        runCatching {
            HookRegistry.hook(module, FEATURE_ID, fallback, FallbackHooker)
        }.onSuccess {
            HookLogger.i(TAG, "Fallback linkage direction hook installed")
        }.onFailure {
            HookLogger.w(TAG, "Fallback linkage direction hook failed", it)
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
    private const val FEATURE_ID = "linkage"
}
