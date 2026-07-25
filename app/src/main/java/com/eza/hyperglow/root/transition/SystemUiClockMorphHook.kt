package com.eza.hyperglow.root.transition

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.aod.AodRenderedClockBounds
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field

internal object SystemUiClockMorphHook {
    private var clockViewRef = WeakReference<View>(null)
    private var morphingToAod = false
    private val clockScratch = Rect()
    private val rootLocation = IntArray(2)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val helperClass = classLoader.loadClass(ANIMATION_HELPER_CLASS)
        val method = helperClass.getDeclaredMethod(
            "doAnimationToAod",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val clockAnimationField = helperClass.getDeclaredField("mClockAnima").apply {
            isAccessible = true
        }
        val clockViewField = helperClass.getDeclaredField("mClockView").apply {
            isAccessible = true
        }
        val allContainerField = classLoader.loadClass(CLOCK_BASE_ANIMATION_CLASS)
            .getDeclaredField("mAllContainer").apply { isAccessible = true }
        module.deoptimize(method)
        module.hook(method).intercept(
            AnimationHooker(clockAnimationField, clockViewField, allContainerField)
        )
        HookLogger.i(TAG, "SystemUI clock morph geometry hook installed")
    }

    fun renderedBoundsInRoot(root: ViewGroup): AodRenderedClockBounds? {
        if (!morphingToAod) return null
        val clock = clockViewRef.get() ?: return null
        if (!clock.isAttachedToWindow || clock.visibility != View.VISIBLE ||
            clock.width <= 0 || clock.height <= 0 ||
            effectiveAlpha(clock) <= MIN_VISIBLE_ALPHA
        ) return null
        if (!clock.getGlobalVisibleRect(clockScratch) || clockScratch.height() <= 0) return null
        root.getLocationInWindow(rootLocation)
        val top = (clockScratch.top - rootLocation[1]).coerceIn(0, root.height)
        val bottom = (clockScratch.bottom - rootLocation[1]).coerceIn(top, root.height)
        return AodRenderedClockBounds(top, bottom).takeIf { it.height > 0 }
    }

    fun isMorphingToAod(): Boolean = morphingToAod

    private fun effectiveAlpha(view: View): Float {
        var alpha = view.alpha
        var parent = view.parent
        while (parent is View) {
            if (parent.visibility != View.VISIBLE) return 0f
            alpha *= parent.alpha
            if (alpha <= MIN_VISIBLE_ALPHA) return alpha
            parent = parent.parent
        }
        return alpha
    }

    private class AnimationHooker(
        private val clockAnimationField: Field,
        private val clockViewField: Field,
        private val allContainerField: Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val helper = chain.thisObject ?: return result
            val toAod = chain.args.firstOrNull() as? Boolean ?: return result
            morphingToAod = toAod
            if (!toAod) {
                clockViewRef.clear()
                return result
            }
            try {
                val clockAnimation = clockAnimationField.get(helper)
                val clockView = clockAnimation?.let {
                    allContainerField.get(it) as? View
                } ?: clockViewField.get(helper) as? View
                clockViewRef = WeakReference(clockView)
                HookLogger.i(
                    TAG,
                    "SystemUI clock morph view captured=${clockView?.javaClass?.name}"
                )
            } catch (error: Exception) {
                clockViewRef.clear()
                HookLogger.w(TAG, "SystemUI clock morph view capture failed", error)
            }
            return result
        }
    }

    private const val ANIMATION_HELPER_CLASS =
        "com.android.keyguard.clock.animation.AnimationHelper"
    private const val CLOCK_BASE_ANIMATION_CLASS =
        "com.android.keyguard.clock.animation.ClockBaseAnimation"
    private const val MIN_VISIBLE_ALPHA = 0.02f
    private const val TAG = "SystemUiClockMorph"
}
