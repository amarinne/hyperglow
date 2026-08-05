package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.LyricKeepAliveSignal
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricSubscriber

internal object AodPowerCoordinator : SystemUiLyricSubscriber {
    override val surfaceKind = LyricSurfaceKind.AOD

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceAttached = false
    private var aodDisplayOff = false
    private var aodEnabled = false
    private var keepAliveRequested = false
    private var graceEligible = false
    private var graceActive = false
    private var lastWakeSignal = Long.MIN_VALUE
    private var lifetimeActive = false
    private var lifetimeActiveSinceElapsedMs = Long.MIN_VALUE
    private var hideRaceRecoveryPending = false
    private val graceExpiry = Runnable {
        if (!graceActive) return@Runnable
        graceActive = false
        graceEligible = false
        keepAliveRequested = false
        HookLogger.i(TAG, "AOD power grace expired")
        updateLifetimeGuard()
    }

    fun onSurfaceAttached() {
        if (surfaceAttached) return
        surfaceAttached = true
        updateLifetimeGuard()
    }

    fun onSurfaceDetached() {
        aodDisplayOff = false
        if (!surfaceAttached) return
        surfaceAttached = false
        updateLifetimeGuard()
    }

    /**
     * A hide already in flight when keepalive intent arrives cannot be suppressed, so a presenting
     * AOD can vanish moments after the guard activates. That single race is recoverable by
     * re-asserting the wake identity once the doze state has settled. Display power is not otherwise
     * ours to override: a sensor/pocket pause, a deliberate sleep, and an expired session all reach
     * the same off edge, and re-waking those fights Xiaomi in a loop. Recovery is therefore armed
     * only by an inactive-to-active guard edge observed while AOD was still presenting, and is
     * consumed by the first off edge inside the bounded hide-animation window.
     */
    fun onAodDisplayState(state: Int) {
        val off = isAodDisplayOffState(state)
        if (off == aodDisplayOff) return
        aodDisplayOff = off
        if (!off || lastWakeSignal == Long.MIN_VALUE) return
        if (!shouldRecoverRacedAodHide(
                surfaceAttached = surfaceAttached,
                keepAliveRequested = keepAliveRequested,
                recoveryPending = hideRaceRecoveryPending,
                lifetimeActiveSinceElapsedMs = lifetimeActiveSinceElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        ) return
        hideRaceRecoveryPending = false
        HookLogger.i(TAG, "AOD hide race recovery signal=$lastWakeSignal")
        dispatchWake(signal = lastWakeSignal, allowed = aodEnabled, forceRetry = true)
    }

    override fun onLyricSnapshot(snapshot: LyricSnapshot) {
        if (snapshot.visible) {
            aodEnabled = snapshot.aodEnabled
            keepAliveRequested = snapshot.aodEnabled && snapshot.playbackActive && snapshot.keepAlive
            graceEligible = hasPersistentAodPowerIntent(snapshot)
            cancelGrace()
        } else if (shouldStartAodPowerGrace(
                aodEnabled = aodEnabled,
                playbackActive = snapshot.playbackActive,
                keepAliveRequested = keepAliveRequested,
                graceEligible = graceEligible
            )
        ) {
            startGrace()
        } else {
            cancelGrace()
            keepAliveRequested = false
            graceEligible = false
        }
        updateLifetimeGuard()
        dispatchWake(
            snapshot.wakeSignal,
            snapshot.aodEnabled && snapshot.visible && snapshot.playbackActive
        )
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        if (!signal.playbackActive) {
            cancelGrace()
            keepAliveRequested = false
            graceEligible = false
        } else if (signal.keepAlive) {
            keepAliveRequested = aodEnabled
            cancelGrace()
        } else if (!graceActive) {
            keepAliveRequested = false
            graceEligible = false
        }
        updateLifetimeGuard()
        dispatchWake(
            signal = signal.wakeSignal,
            allowed = aodEnabled,
            forceRetry = shouldRetryDetachedAodWake(surfaceAttached, keepAliveRequested)
        )
    }

    override fun onLyricProjectionDisconnected() = clear()

    override fun onLyricProjectionStale() = clear()

    private fun clear() {
        cancelGrace()
        keepAliveRequested = false
        graceEligible = false
        aodEnabled = false
        aodDisplayOff = false
        hideRaceRecoveryPending = false
        lastWakeSignal = Long.MIN_VALUE
        updateLifetimeGuard()
    }

    private fun updateLifetimeGuard() {
        val active = shouldActivateAodPowerLifetime(
            surfaceAttached = surfaceAttached,
            keepAliveRequested = keepAliveRequested,
            capabilityAvailable = XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_LIFETIME_GUARD
            )
        )
        if (active != lifetimeActive) {
            lifetimeActive = active
            lifetimeActiveSinceElapsedMs = if (active) SystemClock.elapsedRealtime() else Long.MIN_VALUE
            hideRaceRecoveryPending = active && !aodDisplayOff
        }
        AodLifetimeController.setLyricActive(active)
    }

    private fun dispatchWake(signal: Long, allowed: Boolean, forceRetry: Boolean = false) {
        val newSignal = isNewAodWakeSignal(lastWakeSignal, signal)
        if (!allowed || (!newSignal && !forceRetry)) return
        val accepted = AodWakeBroker.requestWake(signal)
        if (newSignal && accepted) lastWakeSignal = signal
        HookLogger.i(
            TAG,
            "AOD wake requested signal=$signal attached=$surfaceAttached " +
                "displayOff=$aodDisplayOff retry=$forceRetry accepted=$accepted"
        )
    }

    private fun startGrace() {
        if (graceActive) return
        graceActive = true
        mainHandler.removeCallbacks(graceExpiry)
        mainHandler.postDelayed(graceExpiry, PAUSED_AOD_KEEP_ALIVE_MS)
        HookLogger.i(TAG, "AOD power grace started")
    }

    private fun cancelGrace() {
        graceActive = false
        mainHandler.removeCallbacks(graceExpiry)
    }

    private const val TAG = "AodPowerCoordinator"
}

/**
 * Grace eligibility follows validated keepalive intent, not lyric timing. `Keep unsynced songs
 * active` produces persistent keepalive without timed rows, and those sessions need the same
 * protection from transient producer gaps at a song boundary.
 */
internal fun hasPersistentAodPowerIntent(snapshot: LyricSnapshot): Boolean =
    snapshot.playbackActive && snapshot.keepAlive

internal fun shouldStartAodPowerGrace(
    aodEnabled: Boolean,
    playbackActive: Boolean,
    keepAliveRequested: Boolean,
    graceEligible: Boolean
): Boolean = aodEnabled && playbackActive && keepAliveRequested && graceEligible

internal fun shouldRetryDetachedAodWake(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean
): Boolean = !surfaceAttached && keepAliveRequested

/**
 * Bounded to the one unwinnable race: keepalive intent landing after Xiaomi's policy hide has
 * already run, so the presenting AOD disappears while the guard is nominally active. Every other
 * powered-off AOD belongs to Xiaomi. The armed flag is one-shot per guard activation and is never
 * armed when AOD was already dark, which is what separates the race from a sensor pause, a
 * deliberate sleep, or a session that simply ended.
 */
internal fun shouldRecoverRacedAodHide(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    recoveryPending: Boolean,
    lifetimeActiveSinceElapsedMs: Long,
    nowElapsedMs: Long
): Boolean = surfaceAttached && keepAliveRequested && recoveryPending &&
    lifetimeActiveSinceElapsedMs != Long.MIN_VALUE &&
    nowElapsedMs - lifetimeActiveSinceElapsedMs <= HIDE_RACE_RECOVERY_WINDOW_MS

internal fun isAodDisplayOffState(state: Int): Boolean = state == 1

/** Bounds recovery to Xiaomi's hide animation; the captured race lost the panel 1.79 s in. */
internal const val HIDE_RACE_RECOVERY_WINDOW_MS = 2_500L

internal fun shouldActivateAodPowerLifetime(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    capabilityAvailable: Boolean
): Boolean = surfaceAttached && keepAliveRequested && capabilityAvailable
