package com.eza.hyperglow.root.lockscreen

import android.view.MotionEvent
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
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
    private const val EDITOR_HELPER = "com.android.keyguard.editor.KeyguardEditorHelper"
    private const val MAGAZINE_CONTROLLER =
        "com.android.keyguard.magazine.LockScreenMagazineController"
    private var installed = false

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        val helper = classLoader.loadClass(EDITOR_HELPER)
        val touch = helper.getDeclaredMethod("onTouchEvent", MotionEvent::class.java).apply {
            isAccessible = true
        }
        val launch = helper.getDeclaredMethod("tryStartEditActivity").apply {
            isAccessible = true
        }
        val magazine = classLoader.loadClass(MAGAZINE_CONTROLLER)
        val showMagazinePreview = magazine.getDeclaredMethod("handleSingleClickEvent").apply {
            isAccessible = true
        }
        module.deoptimize(touch)
        module.deoptimize(launch)
        module.deoptimize(showMagazinePreview)
        module.hook(touch).intercept(EditorTouchHooker)
        module.hook(launch).intercept(EditorLaunchHooker)
        module.hook(showMagazinePreview).intercept(MagazinePreviewHooker)
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
