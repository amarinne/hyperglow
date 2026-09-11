package com.eza.hyperglow.root.aod

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Accelerometer-driven canvas orientation for the full-screen AOD scene.
 *
 * The listener runs only while a suppressed-stock lyric scene is attached and
 * rendering; every other state unregisters it so the sensor costs nothing
 * outside an active rotated session. Steps are debounced: a candidate must
 * hold for the configured settle delay before it commits, so shaking the
 * phone cannot spam orientation transitions.
 */
internal object AodOrientationMonitor {
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var listener: SensorEventListener? = null
    // Strong reference: the callback belongs to the singleton surface
    // controller, so nothing leaks, while a weak reference lets GC collect
    // it within seconds on a churning device. The next sensor event then
    // finds no callback and stops the monitor, leaving the canvas stuck
    // until an unrelated restart (device-verified 2026-09-05: "callback
    // lost" followed by an unrecoverable portrait snap).
    private var callback: ((Int) -> Unit)? = null
    private var committedStep = 0
    private var pendingStep = -1
    private var pendingSinceElapsedMs = 0L
    private var settleMs = DEFAULT_SETTLE_MS
    private var unavailableLogged = false

    fun isActive(): Boolean = listener != null

    fun activeSettleMs(): Long? = settleMs.takeIf { listener != null }

    /** Last sensor-committed step, or null while the monitor is not registered. */
    fun committedStep(): Int? = committedStep.takeIf { listener != null }

    /**
     * Re-anchors the debounce to an externally applied step without firing the
     * callback. External snaps (framework display rotation) must rebase the
     * sensor, otherwise the monitor holds a stale step, stays silent on
     * matching samples, and the canvas sticks until the user moves the phone.
     */
    fun rebase(step: Int) {
        if (step != 0 && step != 90 && step != 180 && step != 270) return
        committedStep = step
        pendingStep = -1
    }

    fun start(context: Context, settleDelayMs: Long, onStep: (Int) -> Unit) {
        val settled = settleDelayMs.coerceAtLeast(0L)
        if (listener != null) {
            settleMs = settled
            callback = onStep
            return
        }
        val manager = context.getSystemService(SensorManager::class.java) ?: run {
            if (!unavailableLogged) {
                unavailableLogged = true
                HookLogger.w(TAG, "Accelerometer unavailable: no sensor service")
            }
            return
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            if (!unavailableLogged) {
                unavailableLogged = true
                HookLogger.w(TAG, "Accelerometer unavailable: no accelerometer sensor")
            }
            return
        }
        unavailableLogged = false
        committedStep = 0
        pendingStep = -1
        settleMs = settled
        callback = onStep
        val registeredListener = OrientationListener()
        if (!manager.registerListener(
                registeredListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                Handler(Looper.getMainLooper())
            )
        ) {
            HookLogger.w(TAG, "Accelerometer registration failed")
            callback = null
            return
        }
        sensorManager = manager
        accelerometer = sensor
        listener = registeredListener
        HookLogger.i(TAG, "Orientation monitor started")
    }

    fun stop() {
        val manager = sensorManager
        val registered = listener
        sensorManager = null
        accelerometer = null
        listener = null
        callback = null
        pendingStep = -1
        settleMs = DEFAULT_SETTLE_MS
        if (registered != null) {
            runCatching { manager?.unregisterListener(registered) }
            HookLogger.i(TAG, "Orientation monitor stopped")
        }
    }

    private class OrientationListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.size < 2) return
            val candidate = resolveAodRotationStep(values[0], values[1], committedStep)
            if (candidate == committedStep) {
                pendingStep = -1
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (pendingStep != candidate) {
                pendingStep = candidate
                pendingSinceElapsedMs = now
                return
            }
            if (now - pendingSinceElapsedMs < settleMs) return
            pendingStep = -1
            committedStep = candidate
            val current = callback
            if (current != null) {
                current(candidate)
            } else {
                HookLogger.w(TAG, "Orientation callback lost; stopping monitor")
                stop()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private const val DEFAULT_SETTLE_MS = 1_000L
    private const val TAG = "AodOrientation"
}

/**
 * Gravity-upright canvas step for an accelerometer sample. Returns 0, 90, 180,
 * or 270 (clockwise degrees matching `View.rotation`), or [current] when the
 * device is flat or the tilt is ambiguous, so the canvas holds its step.
 */
internal fun resolveAodRotationStep(ax: Float, ay: Float, current: Int): Int {
    if (!ax.isFinite() || !ay.isFinite()) return current
    if (sqrt(ax * ax + ay * ay) < FLAT_THRESHOLD) return current
    val absX = abs(ax)
    val absY = abs(ay)
    if (absX < absY * AMBIGUITY_RATIO && absY < absX * AMBIGUITY_RATIO) return current
    return if (absX > absY) {
        if (ax > 0f) 90 else 270
    } else {
        if (ay > 0f) 0 else 180
    }
}

private const val FLAT_THRESHOLD = 2.5f
private const val AMBIGUITY_RATIO = 1.2f

/**
 * Whether a framework display-rotation step may move the canvas in auto mode.
 * A frozen portrait framebuffer report must not clobber a sensor-held side
 * step: in doze the framework rotation can read portrait while the device is
 * landscape (for example across a song-change wake that flaps display state),
 * and the already-committed sensor would stay silent on matching samples, so
 * the canvas would stick in portrait until the user re-rotates. The settle
 * delay cannot prevent that because the framework path bypasses the debounce.
 * Every other combination applies; the caller rebases the monitor to the
 * applied step so the sensor re-decides ground truth afterwards.
 */
internal fun frameworkRotationStepApplies(frameworkStep: Int, sensorCommittedStep: Int?): Boolean {
    if (sensorCommittedStep != 90 && sensorCommittedStep != 270) return true
    return frameworkStep != 0
}
