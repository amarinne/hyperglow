package com.eza.hyperglow.root.aod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import com.eza.hyperglow.root.hierarchyField
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Why a wake request could not be served. The three missing-reference states are faults worth
 * naming in the log; [INTERACTIVE] is the ordinary suppressed case and never reaches it.
 */
internal enum class AodWakeAvailability(val wireValue: String) {
    READY("ready"),
    INTERACTIVE("interactive"),
    NO_HOST("no_host"),
    NO_METHOD("no_method"),
    NO_POWER_MANAGER("no_power_manager");

    val isFault: Boolean get() = this != READY && this != INTERACTIVE
}

/**
 * A missing reference is reported before interactivity, because a request served on a dead host is
 * a defect and a request suppressed on a lit screen is not. Ordering the faults first is what lets
 * one reason describe every reason a wake was refused.
 */
internal fun resolveAodWakeAvailability(
    hostCaptured: Boolean,
    methodResolved: Boolean,
    powerManagerResolved: Boolean,
    interactive: Boolean
): AodWakeAvailability = when {
    !hostCaptured -> AodWakeAvailability.NO_HOST
    !methodResolved -> AodWakeAvailability.NO_METHOD
    !powerManagerResolved -> AodWakeAvailability.NO_POWER_MANAGER
    interactive -> AodWakeAvailability.INTERACTIVE
    else -> AodWakeAvailability.READY
}

/**
 * Whether a reference the broker holds is worth replacing. A live host is only adopted when it is
 * a different instance, so a repeat of the same one cannot re-log, and a null is never allowed to
 * clear a reference Xiaomi is still using. The wake method is kept once resolved: it belongs to
 * the class, not the instance, and re-reading it per loader would churn the reference for nothing.
 */
internal fun shouldAdoptAodWakeReference(
    current: Any?,
    candidate: Any?
): Boolean = candidate != null && candidate !== current

/**
 * A refusal is reported once per distinct reason and a new reason is never suppressed by an older
 * one. The previous single global latch reported the first fault only, so a process that lost the
 * host and then the power manager looked identical to one that never had a host at all.
 */
internal fun shouldReportAodWakeUnavailable(
    reason: AodWakeAvailability,
    lastReported: AodWakeAvailability?
): Boolean = reason.isFault && reason != lastReported

/**
 * The install reasons a refusal carries. Only a missing host has an install story: the other two
 * faults are about a reference the broker holds, which install cannot affect.
 */
internal fun aodWakeUnavailableDetail(
    reason: AodWakeAvailability,
    installSkips: String
): String = if (reason == AodWakeAvailability.NO_HOST) {
    " install=" + installSkips.ifEmpty { "none" }
} else {
    ""
}

internal object AodWakeBroker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val recordedInstallSkips = Collections.synchronizedSet(mutableSetOf<String>())

    // Latest verified host remains usable after Xiaomi tears down the visible AOD plugin instance.
    // One strong reference is intentional: it is the recovery seam that can recreate sleeping AOD.
    // Hook interceptors write these from arbitrary threads and a wake request reads them from a
    // Binder or main thread, so every access is guarded and callers act on a snapshot taken under
    // the monitor. Nothing that can re-enter the host is called while holding it.
    private var host: Any? = null
    private var fireAodStateMethod: Method? = null
    private var powerManager: PowerManager? = null
    private var lastRequestElapsedMs = Long.MIN_VALUE
    private var unavailableReason: AodWakeAvailability? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val triggersClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, DOZE_TRIGGERS_CLASS
        )
        if (triggersClass == null) {
            noteInstallSkip("triggers_class")
            return
        }
        val hostField = hierarchyField(triggersClass, "mHost")
        if (hostField == null) {
            noteInstallSkip("m_host")
            return
        }
        val contextField = hierarchyField(triggersClass, "mContext")
        if (contextField == null) {
            noteInstallSkip("m_context")
            return
        }
        val fireAodState = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(DOZE_HOST_CLASS, "fireAodState", "boolean", "java.lang.String")
        )
        if (fireAodState == null) {
            noteInstallSkip("fire_aod_state")
            return
        }
        // Resolved here, not in the constructor hooker. A plugin instance that already existed when
        // the hook installed never runs that constructor, and a wake method owned only by the
        // constructor seam stays null for the rest of the process, so adoption cannot serve a wake.
        noteWakeMethod(fireAodState)
        if (!hookedClassLoaders.add(classLoader)) return
        for (constructor in triggersClass.declaredConstructors) {
            constructor.isAccessible = true
            HookRegistry.hook(
                module,
                FEATURE_ID,
                constructor,
                DozeTriggersConstructorHooker(hostField, contextField)
            )
        }
        HookLogger.i(
            TAG,
            "AOD wake broker hook installed constructors=${triggersClass.declaredConstructors.size}"
        )
    }

    /**
     * Supplies the power manager from a process context. Seeded at SystemUI `onCreate`, so the
     * interactive check no longer depends on the AOD plugin constructor having been observed.
     */
    @Synchronized
    fun observeContext(context: Context) {
        if (powerManager != null) return
        powerManager = runCatching {
            context.getSystemService(PowerManager::class.java)
        }.getOrNull()
    }

    /**
     * Adopts a live `DozeHost` handed over by an existing hook. The constructor seam only fires for
     * a plugin instance created after the hook installed, so a ROM that preloads or reuses its AOD
     * plugin left the wake path dead for the whole process while the wake-broker capability still
     * resolved from symbols alone. An instance Xiaomi is itself calling is verified by that use,
     * and it fills the same single strong reference the constructor seam keeps.
     */
    @Synchronized
    fun adoptHost(candidate: Any?, source: String) {
        if (!shouldAdoptAodWakeReference(host, candidate)) return
        host = candidate
        unavailableReason = null
        HookLogger.i(
            TAG,
            "AOD wake host captured class=${candidate?.javaClass?.name} source=$source"
        )
    }

    fun requestWake(signal: Long): Boolean = enqueueWake(signal, "lyrics")

    fun requestPickupWake(): Boolean = enqueueWake(
        signal = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        source = "pickup"
    )

    private fun enqueueWake(signal: Long, source: String): Boolean {
        if (signal == 0L || !XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_WAKE_BROKER
            )
        ) return false
        val state = readState()
        if (resolveAvailability(state) != AodWakeAvailability.READY) {
            noteUnavailable(state, source, deferred = false)
            return false
        }
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (lastRequestElapsedMs != Long.MIN_VALUE &&
                now - lastRequestElapsedMs < MIN_REQUEST_INTERVAL_MS
            ) return@post
            val dispatched = readState()
            if (resolveAvailability(dispatched) != AodWakeAvailability.READY) {
                noteUnavailable(dispatched, source, deferred = true)
                return@post
            }
            val method = dispatched.method ?: return@post
            try {
                method.invoke(dispatched.host, true, WAKE_REASON)
                lastRequestElapsedMs = now
                HookLogger.i(
                    TAG,
                    "AOD wake dispatched signal=$signal source=$source reason=$WAKE_REASON"
                )
            } catch (error: Exception) {
                (error as? java.lang.reflect.InvocationTargetException)
                    ?.cause
                    ?.let { if (it is Error) throw it }
                HookLogger.w(TAG, "AOD wake dispatch failed", error)
            }
        }
        return true
    }

    /**
     * Records why an install bailed, without judging it yet. `install` runs per class loader, and on
     * a healthy ROM a loader that cannot see the AOD dex bails while a later one on the same dex
     * succeeds and captures a host — so a skip reported on sight is a false alarm. The record is
     * only read when a wake is actually refused with no host, which is the moment the answer
     * matters, and it is then reported with the reason attached.
     */
    private fun noteInstallSkip(reason: String) {
        if (recordedInstallSkips.add(reason)) {
            HookLogger.i(TAG, "AOD wake broker install skipped reason=$reason")
        }
    }

    @Synchronized
    private fun installSkipSummary(): String =
        if (recordedInstallSkips.isEmpty()) {
            "none"
        } else {
            recordedInstallSkips.sorted().joinToString("+")
        }

    @Synchronized
    private fun noteWakeMethod(method: Method) {
        if (fireAodStateMethod == null) fireAodStateMethod = method
    }

    @Synchronized
    private fun readState(): AodWakeState = AodWakeState(
        host = host,
        method = fireAodStateMethod,
        powerManager = powerManager
    )

    /**
     * One reason is reported once, and a distinct later reason is still reported. A single global
     * latch lost the second fault: it read as "already known" while a different reference was the
     * one missing.
     */
    @Synchronized
    private fun noteUnavailable(state: AodWakeState, source: String, deferred: Boolean) {
        val reason = resolveAvailability(state)
        if (!shouldReportAodWakeUnavailable(reason, unavailableReason)) return
        unavailableReason = reason
        // The install reasons travel with the refusal. "no host" alone could not say whether the
        // host was never handed over or the installer never bound, and that is the whole question a
        // field report has to answer.
        val detail = aodWakeUnavailableDetail(reason, installSkipSummary())
        HookLogger.w(
            TAG,
            "AOD wake unavailable reason=${reason.wireValue} source=$source deferred=$deferred$detail"
        )
    }

    private fun resolveAvailability(state: AodWakeState): AodWakeAvailability =
        resolveAodWakeAvailability(
            hostCaptured = state.host != null,
            methodResolved = state.method != null,
            powerManagerResolved = state.powerManager != null,
            interactive = state.powerManager?.isInteractive == true
        )

    private data class AodWakeState(
        val host: Any?,
        val method: Method?,
        val powerManager: PowerManager?
    )

    private class DozeTriggersConstructorHooker(
        private val hostField: java.lang.reflect.Field,
        private val contextField: java.lang.reflect.Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                val owner = chain.thisObject ?: return result
                val host = hostField.get(owner) ?: return result
                val context = contextField.get(owner) as? Context ?: return result
                observeContext(context)
                adoptHost(host, "constructor")
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD wake host capture failed", error)
            }
            return result
        }
    }

    private const val DOZE_TRIGGERS_CLASS = "com.miui.aod.doze.DozeTriggers"
    private const val DOZE_HOST_CLASS = "com.miui.aod.DozeHost"
    private const val WAKE_REASON = "reason_keycode_goto"
    private const val MIN_REQUEST_INTERVAL_MS = 750L
    private const val FEATURE_ID = "aod-wake"
    private const val TAG = "AodWakeBroker"
}
