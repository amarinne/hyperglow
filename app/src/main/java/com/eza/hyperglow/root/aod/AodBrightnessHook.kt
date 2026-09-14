package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.customization.MAX_AOD_BRIGHTNESS
import com.eza.hyperglow.customization.MIN_AOD_BRIGHTNESS
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object AodBrightnessHook {
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val adapterMethod = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(ADAPTER_CLASS, SET_BRIGHTNESS_METHOD, "int")
        ) ?: return
        val adapterClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, ADAPTER_CLASS
        ) ?: return
        val transitionMethod = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(CONTROLLER_CLASS, TRANSITION_METHOD, STATE_CLASS, STATE_CLASS)
        ) ?: return
        val readableBrightness = resolveBrightnessOn(classLoader)
        if (!hookedClassLoaders.add(classLoader)) return

        AodBrightnessController.registerTarget(readableBrightness)
        HookRegistry.hook(module, FEATURE_ID, adapterMethod, BrightnessHooker)
        HookRegistry.hook(module, FEATURE_ID, transitionMethod, TransitionHooker)
        val constructorHooker = AdapterConstructorHooker(adapterMethod)
        for (constructor in adapterClass.declaredConstructors) {
            constructor.isAccessible = true
            HookRegistry.hook(module, FEATURE_ID, constructor, constructorHooker)
        }
        HookLogger.i(
            TAG,
            "AOD brightness hooks installed constructors=${adapterClass.declaredConstructors.size} " +
                "methods=2 target=$readableBrightness"
        )
    }

    private fun resolveBrightnessOn(classLoader: ClassLoader): Int {
        val field = SymbolResolver.resolveField(
            classLoader,
            FEATURE_ID,
            SymbolRequest.field(COMMON_UTILS_CLASS, BRIGHTNESS_ON_FIELD, "int")
        )
        val value = runCatching { field?.get(null) }.getOrNull()
        if (value is Int && value > 0) return value
        HookLogger.w(
            TAG,
            "CommonUtils.$BRIGHTNESS_ON_FIELD unavailable or non-positive; " +
                "using fallback=$FALLBACK_BRIGHTNESS_ON"
        )
        return FALLBACK_BRIGHTNESS_ON
    }

    private class AdapterConstructorHooker(private val brightnessMethod: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let { adapter ->
                AodBrightnessController.registerAdapter(adapter, brightnessMethod)
            }
            return result
        }
    }

    private object TransitionHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            AodBrightnessController.noteDozeState(chain.args.getOrNull(1)?.toString())
            return chain.proceed()
        }
    }

    private object BrightnessHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (chain.args.size != 1) return chain.proceed()
            val requested = chain.args.firstOrNull() as? Int ?: return chain.proceed()
            val resolved = AodBrightnessController.resolveBrightnessRequest(requested)
            return chain.proceed(arrayOf(resolved))
        }
    }

    private const val ADAPTER_CLASS = "com.miui.aod.doze.MiuiDozeBrightnessTimeoutAdapter"
    private const val CONTROLLER_CLASS = "com.miui.aod.doze.MiuiDozeScreenBrightnessController"
    private const val STATE_CLASS = "com.miui.aod.doze.DozeMachine\$State"
    private const val COMMON_UTILS_CLASS = "com.miui.aod.utils.CommonUtils"
    private const val SET_BRIGHTNESS_METHOD = "setDozeScreenBrightness"
    private const val TRANSITION_METHOD = "transitionTo"
    private const val BRIGHTNESS_ON_FIELD = "BRIGHTNESS_ON"
    private const val FALLBACK_BRIGHTNESS_ON = 255
    private const val FEATURE_ID = "aod-brightness"
    private const val TAG = "AodBrightnessHook"
}

object AodBrightnessController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var guardActive = false
    private var dozeStateName: String? = null
    private var activeAdapter = WeakReference<Any>(null)
    private var adapterSetBrightness = WeakReference<Method>(null)
    private var lastRawBrightness: Int? = null
    private var readableBrightness = DEFAULT_READABLE_BRIGHTNESS
    private var brightnessOverrideEnabled = false
    private var brightnessOverrideLevel = DEFAULT_READABLE_BRIGHTNESS
    private var pendingResubmit: Runnable? = null

    @Synchronized
    fun registerAdapter(adapter: Any, brightnessMethod: Method) {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
        activeAdapter = WeakReference(adapter)
        adapterSetBrightness = WeakReference(brightnessMethod)
        lastRawBrightness = null
        dozeStateName = null
        HookLogger.i(TAG, "AOD brightness adapter captured")
    }

    @Synchronized
    fun registerTarget(target: Int) {
        if (target <= 0) return
        readableBrightness = target
    }

    @Synchronized
    fun setBrightnessOverride(enabled: Boolean, level: Int) {
        val normalizedLevel = level.coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS)
        if (brightnessOverrideEnabled == enabled && brightnessOverrideLevel == normalizedLevel) {
            return
        }
        brightnessOverrideEnabled = enabled
        brightnessOverrideLevel = normalizedLevel
        scheduleResubmitLocked(guardActive)
    }

    /**
     * Drops the pending re-submit owned by this generation so it cannot fire after
     * the old module class loader is retired by hot reload.
     */
    @Synchronized
    fun cancelPendingForReload() {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
    }

    @Synchronized
    fun noteDozeState(stateName: String?) {
        val changed = dozeStateName != stateName
        dozeStateName = stateName
        if (changed && stateName == "DOZE_AOD" && guardActive) {
            // Xiaomi can send the first low request before the state transition finishes. Re-run
            // that raw request after the exact state is known so the initial AOD frame is readable.
            scheduleResubmitLocked(requestedGuardState = true)
        }
    }

    @Synchronized
    fun resolveBrightnessRequest(requested: Int): Int {
        lastRawBrightness = requested
        val resolved = resolveAodBrightnessRequest(
            requestedBrightness = requested,
            readableBrightness = readableBrightness,
            lyricGuardActive = guardActive,
            dozeStateName = dozeStateName,
            brightnessOverrideEnabled = brightnessOverrideEnabled,
            brightnessOverrideLevel = brightnessOverrideLevel
        )
        if (resolved != requested) {
            HookLogger.i(
                TAG,
                "AOD brightness clamped raw=$requested resolved=$resolved state=$dozeStateName"
            )
        }
        return resolved
    }

    @Synchronized
    fun setLyricGuardActive(active: Boolean) {
        if (guardActive == active) return
        guardActive = active
        scheduleResubmitLocked(active)
    }

    private fun scheduleResubmitLocked(requestedGuardState: Boolean) {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
        val raw = lastRawBrightness
        val adapter = activeAdapter.get()
        val method = adapterSetBrightness.get()
        if (raw == null || raw < 0 || adapter == null || method == null) return
        val adapterRef = WeakReference(adapter)
        val resubmit = object : Runnable {
            override fun run() {
                try {
                    val currentAdapter = adapterRef.get()
                    val allowed = synchronized(this@AodBrightnessController) {
                        pendingResubmit === this &&
                            guardActive == requestedGuardState &&
                            currentAdapter != null &&
                            activeAdapter.get() === currentAdapter &&
                            adapterSetBrightness.get() === method
                    }
                    if (!allowed || currentAdapter == null) return
                    try {
                        method.invoke(currentAdapter, raw)
                        HookLogger.i(
                            TAG,
                            "AOD brightness raw request re-submitted raw=$raw " +
                                "guard=$requestedGuardState"
                        )
                    } catch (error: Exception) {
                        (error as? InvocationTargetException)?.cause
                            ?.let { if (it is Error) throw it }
                        HookLogger.w(TAG, "AOD brightness re-submit failed", error)
                    }
                } finally {
                    synchronized(this@AodBrightnessController) {
                        if (pendingResubmit === this) pendingResubmit = null
                    }
                }
            }
        }
        pendingResubmit = resubmit
        mainHandler.post(resubmit)
    }

    private const val DEFAULT_READABLE_BRIGHTNESS = 255
    private const val TAG = "AodBrightnessController"
}

internal fun resolveAodBrightnessRequest(
    requestedBrightness: Int,
    readableBrightness: Int,
    lyricGuardActive: Boolean,
    dozeStateName: String?,
    brightnessOverrideEnabled: Boolean = false,
    brightnessOverrideLevel: Int = DEFAULT_READABLE_BRIGHTNESS
): Int {
    val eligible = lyricGuardActive &&
        dozeStateName == "DOZE_AOD" &&
        requestedBrightness > 0 &&
        readableBrightness > 0
    return if (!eligible) {
        requestedBrightness
    } else if (brightnessOverrideEnabled) {
        brightnessOverrideLevel.coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS)
    } else {
        requestedBrightness.coerceAtLeast(readableBrightness)
    }
}

private const val DEFAULT_READABLE_BRIGHTNESS = 255
