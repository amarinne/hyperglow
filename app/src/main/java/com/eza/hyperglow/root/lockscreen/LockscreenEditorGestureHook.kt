package com.eza.hyperglow.root.lockscreen

import android.view.MotionEvent
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

internal object LockscreenEditorGestureController {
    @Volatile
    private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun shouldSuppress(): Boolean = shouldSuppressLockscreenEditorGesture(
        enabled = enabled,
        supported = XiaomiCapabilityResolver.hasCapability(
            XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE
        )
    )
}

internal object LockscreenEditorGestureHook {
    private const val TAG = "LockscreenEditorGesture"
    private const val FEATURE_ID = "editor-gesture"
    private const val EDITOR_HELPER = "com.android.keyguard.editor.KeyguardEditorHelper"
    private const val MAGAZINE_CONTROLLER =
        "com.android.keyguard.magazine.LockScreenMagazineController"
    private var installed = false

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        val touch = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(EDITOR_HELPER, "onTouchEvent", MotionEvent::class.java.name)
        ) ?: return
        val launch = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(EDITOR_HELPER, "tryStartEditActivity")
        ) ?: return
        val showMagazinePreview = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(MAGAZINE_CONTROLLER, "handleSingleClickEvent")
        ) ?: return
        HookRegistry.hook(module, FEATURE_ID, touch, EditorTouchHooker)
        HookRegistry.hook(module, FEATURE_ID, launch, EditorLaunchHooker)
        HookRegistry.hook(module, FEATURE_ID, showMagazinePreview, MagazinePreviewHooker)
        installed = true
        HookLogger.i(TAG, "Lockscreen customization hooks installed")
    }

    private object EditorTouchHooker : Hooker {
        override fun intercept(chain: Chain): Any? =
            if (LockscreenEditorGestureController.shouldSuppress()) null else chain.proceed()
    }

    private object EditorLaunchHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!LockscreenEditorGestureController.shouldSuppress()) return chain.proceed()
            HookLogger.i(TAG, "Suppressed lockscreen editor long press")
            return null
        }
    }

    private object MagazinePreviewHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!LockscreenEditorGestureController.shouldSuppress()) return chain.proceed()
            HookLogger.i(TAG, "Suppressed lock screen wallpaper carousel preview")
            return false
        }
    }
}

internal fun shouldSuppressLockscreenEditorGesture(
    enabled: Boolean,
    supported: Boolean
): Boolean = enabled && supported
