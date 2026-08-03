package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
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
    private var aodEnabled = false
    private var keepAliveRequested = false
    private var graceEligible = false
    private var graceActive = false
    private var lastWakeSignal = Long.MIN_VALUE
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
        if (!surfaceAttached) return
        surfaceAttached = false
        updateLifetimeGuard()
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
                "retry=$forceRetry accepted=$accepted"
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

internal fun shouldActivateAodPowerLifetime(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    capabilityAvailable: Boolean
): Boolean = surfaceAttached && keepAliveRequested && capabilityAvailable
