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
    private var timedGraceEligible = false
    private var timedGraceActive = false
    private var lastWakeSignal = Long.MIN_VALUE
    private val timedGraceExpiry = Runnable {
        if (!timedGraceActive) return@Runnable
        timedGraceActive = false
        timedGraceEligible = false
        keepAliveRequested = false
        HookLogger.i(TAG, "Timed AOD power grace expired")
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
            keepAliveRequested = snapshot.aodEnabled && snapshot.keepAlive
            timedGraceEligible = hasPersistentTimedAodPower(snapshot)
            cancelTimedGrace()
        } else if (shouldStartTimedAodPowerGrace(
                aodEnabled = aodEnabled,
                playbackActive = snapshot.playbackActive,
                keepAliveRequested = keepAliveRequested,
                timedGraceEligible = timedGraceEligible
            )
        ) {
            startTimedGrace()
        } else {
            cancelTimedGrace()
            keepAliveRequested = false
            timedGraceEligible = false
        }
        updateLifetimeGuard()
        dispatchWake(snapshot.wakeSignal, snapshot.aodEnabled && snapshot.visible)
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        if (!signal.playbackActive) {
            cancelTimedGrace()
            keepAliveRequested = false
            timedGraceEligible = false
        } else if (signal.keepAlive) {
            keepAliveRequested = aodEnabled
            cancelTimedGrace()
        } else if (!timedGraceActive) {
            keepAliveRequested = false
            timedGraceEligible = false
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
        cancelTimedGrace()
        keepAliveRequested = false
        timedGraceEligible = false
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

    private fun startTimedGrace() {
        if (timedGraceActive) return
        timedGraceActive = true
        mainHandler.removeCallbacks(timedGraceExpiry)
        mainHandler.postDelayed(timedGraceExpiry, PAUSED_AOD_KEEP_ALIVE_MS)
        HookLogger.i(TAG, "Timed AOD power grace started")
    }

    private fun cancelTimedGrace() {
        timedGraceActive = false
        mainHandler.removeCallbacks(timedGraceExpiry)
    }

    private const val TAG = "AodPowerCoordinator"
}

internal fun hasPersistentTimedAodPower(snapshot: LyricSnapshot): Boolean =
    snapshot.keepAlive && (
        snapshot.lineEndMs > snapshot.lineStartMs ||
            snapshot.words.any { it.endMs > it.startMs }
        )

internal fun shouldStartTimedAodPowerGrace(
    aodEnabled: Boolean,
    playbackActive: Boolean,
    keepAliveRequested: Boolean,
    timedGraceEligible: Boolean
): Boolean = aodEnabled && playbackActive && keepAliveRequested && timedGraceEligible

internal fun shouldRetryDetachedAodWake(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean
): Boolean = !surfaceAttached && keepAliveRequested

internal fun shouldActivateAodPowerLifetime(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    capabilityAvailable: Boolean
): Boolean = surfaceAttached && keepAliveRequested && capabilityAvailable
