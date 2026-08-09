package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.readHierarchyField
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object AodLifetimeHook {
    private const val CONTROLLER_CLASS = "com.miui.aod.doze.MiuiShowStyleController"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val controllerClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
            ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        for (constructor in controllerClass.declaredConstructors) {
            constructor.isAccessible = true
            module.hook(constructor).intercept(ControllerConstructorHooker)
        }
        for (methodName in POLICY_HIDE_METHODS) {
            val method = controllerClass.getDeclaredMethod(methodName)
            method.isAccessible = true
            module.deoptimize(method)
            module.hook(method).intercept(PolicyHideHooker(method))
        }
        HookLogger.i(
            TAG,
            "AOD lifetime hooks installed constructors=${controllerClass.declaredConstructors.size} " +
                "policyMethods=${POLICY_HIDE_METHODS.size}"
        )
    }

    private object ControllerConstructorHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let(AodLifetimeController::registerController)
            return result
        }
    }

    private class PolicyHideHooker(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (AodLifetimeController.suppressPolicyHide(chain.thisObject, method)) return null
            return chain.proceed()
        }
    }

    private val POLICY_HIDE_METHODS = listOf("smartHide", "hideDoze")
    private const val TAG = "AodLifetimeHook"
}

object AodLifetimeController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lyricActive = false
    private var activeController = WeakReference<Any>(null)
    private var controllerGeneration = 0L
    private var pendingController = WeakReference<Any>(null)
    private var pendingControllerGeneration = -1L
    private var pendingMethod: Method? = null
    private var pendingReplay: Runnable? = null
    private var guardCause = "init"

    /** Records what the coordinator last acted on, so a guard transition can name its own cause. */
    @Synchronized
    fun noteGuardCause(cause: String) {
        guardCause = cause
    }

    @Synchronized
    fun setLyricActive(active: Boolean) {
        if (lyricActive == active) return
        lyricActive = active
        HookLogger.i(TAG, "Lyric lifetime guard active=$active cause=$guardCause")
        if (active) {
            clearPendingHideLocked()
            activeController.get()?.let(::cancelPolicyTimeouts)
        } else {
            replayPendingHide()
        }
    }

    @Synchronized
    fun registerController(controller: Any) {
        controllerGeneration++
        activeController = WeakReference(controller)
        clearPendingHideLocked()
        HookLogger.i(TAG, "AOD policy controller captured")
        if (lyricActive) cancelPolicyTimeouts(controller)
    }

    @Synchronized
    fun suppressPolicyHide(controller: Any, method: Method): Boolean {
        if (!lyricActive) return false
        if (activeController.get() !== controller) {
            controllerGeneration++
            activeController = WeakReference(controller)
            clearPendingHideLocked()
        }
        pendingController = WeakReference(controller)
        pendingControllerGeneration = controllerGeneration
        pendingMethod = method
        HookLogger.i(TAG, "Suppressed ${method.name} during active lyrics")
        return true
    }

    @Synchronized
    private fun replayPendingHide() {
        val controller = pendingController.get()
        val method = pendingMethod
        val generation = pendingControllerGeneration
        pendingController.clear()
        pendingControllerGeneration = -1L
        pendingMethod = null
        if (controller == null || method == null) return
        val controllerRef = WeakReference(controller)
        val replay = object : Runnable {
            override fun run() {
                try {
                    val currentController = controllerRef.get()
                    val allowed = synchronized(this@AodLifetimeController) {
                        pendingReplay === this && shouldReplaySuppressedPolicyHide(
                            lyricActive = lyricActive,
                            capturedGeneration = generation,
                            currentGeneration = controllerGeneration,
                            sameController = currentController != null &&
                                activeController.get() === currentController
                        )
                    }
                    if (!allowed || currentController == null) return
                    try {
                        method.invoke(currentController)
                        HookLogger.i(TAG, "Replayed ${method.name} after lyric session")
                    } catch (error: Exception) {
                        (error as? java.lang.reflect.InvocationTargetException)
                            ?.cause
                            ?.let { if (it is Error) throw it }
                        HookLogger.w(TAG, "Pending AOD hide replay failed", error)
                    }
                } finally {
                    synchronized(this@AodLifetimeController) {
                        if (pendingReplay === this) pendingReplay = null
                    }
                }
            }
        }
        pendingReplay = replay
        mainHandler.postDelayed(replay, REPLAY_DELAY_MS)
    }

    private fun clearPendingHideLocked() {
        pendingReplay?.let(mainHandler::removeCallbacks)
        pendingReplay = null
        pendingController.clear()
        pendingControllerGeneration = -1L
        pendingMethod = null
    }

    private fun cancelPolicyTimeouts(controller: Any) {
        var cancelled = 0
        for (fieldName in POLICY_TIMEOUT_FIELDS) {
            val timeout = readHierarchyField(controller, fieldName) ?: continue
            if (runCatching {
                    timeout.javaClass.getMethod("cancel").invoke(timeout)
                }.isSuccess) {
                cancelled++
            }
        }
        HookLogger.i(TAG, "Cancelled active AOD policy timeouts=$cancelled")
    }

    private val POLICY_TIMEOUT_FIELDS = listOf("mSmartHideDozeTimeout", "mHideDozeTimeout")
    private const val TAG = "AodLifetimeController"
    private const val REPLAY_DELAY_MS = 500L
}

internal fun shouldReplaySuppressedPolicyHide(
    lyricActive: Boolean,
    capturedGeneration: Long,
    currentGeneration: Long,
    sameController: Boolean
): Boolean = !lyricActive && capturedGeneration >= 0L &&
    capturedGeneration == currentGeneration && sameController
