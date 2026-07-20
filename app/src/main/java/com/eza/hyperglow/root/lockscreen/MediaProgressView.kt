package com.eza.hyperglow.root.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.eza.hyperglow.root.aod.CadenceChange
import com.eza.hyperglow.root.aod.EffectiveCadenceGate
import com.eza.hyperglow.root.aod.EffectiveCadenceInputs
import com.eza.hyperglow.root.aod.isEffectiveCadenceActive
import com.eza.hyperglow.root.aod.resolveAodPalette

internal fun projectedMediaProgress(
    durationMs: Long,
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    nowElapsedMs: Long
): Float {
    if (durationMs <= 0L) return 0f
    val projected = positionMs +
        ((nowElapsedMs - sampledAtElapsedMs).coerceAtLeast(0L) * speed.coerceAtLeast(0f)).toLong()
    return (projected.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal class MediaProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
    private var durationMs = 0L
    private var positionMs = 0L
    private var sampledAtElapsedMs = 0L
    private var speed = 1f
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    private val frame = object : Runnable {
        override fun run() {
            if (!effectiveCadenceActive()) {
                syncCadence()
                return
            }
            invalidate()
            postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun setPlayback(durationMs: Long, positionMs: Long, sampledAtElapsedMs: Long, speed: Float) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.positionMs = positionMs.coerceAtLeast(0L)
        this.sampledAtElapsedMs = sampledAtElapsedMs.coerceAtLeast(0L)
        this.speed = speed.takeIf { it.isFinite() && it >= 0f } ?: 1f
        syncCadence()
        invalidate()
    }

    fun setSceneActive(active: Boolean) {
        if (sceneActive == active) return
        sceneActive = active
        syncCadence()
    }

    fun setPalette(tokens: Map<String, String>) {
        val palette = resolveAodPalette(tokens)
        trackPaint.color = Color.argb(
            64,
            Color.red(palette.secondaryText),
            Color.green(palette.secondaryText),
            Color.blue(palette.secondaryText)
        )
        progressPaint.color = Color.argb(
            217,
            Color.red(palette.accent),
            Color.green(palette.accent),
            Color.blue(palette.accent)
        )
        invalidate()
    }

    fun stop() {
        cadenceGate.update(false)
        removeCallbacks(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        syncCadence()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncCadence()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncCadence()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        syncCadence()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        syncCadence()
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)
        val progress = projectedMediaProgress(
            durationMs,
            positionMs,
            sampledAtElapsedMs,
            speed,
            SystemClock.elapsedRealtime()
        )
        canvas.drawRoundRect(0f, 0f, width * progress, height.toFloat(), radius, radius, progressPaint)
    }

    override fun onDetachedFromWindow() {
        stop()
        aggregatedVisible = false
        super.onDetachedFromWindow()
    }

    private fun effectiveCadenceActive(): Boolean = isEffectiveCadenceActive(
        EffectiveCadenceInputs(
            attached = isAttachedToWindow,
            sceneActive = sceneActive,
            ownVisible = visibility == VISIBLE,
            windowVisible = windowVisibility == VISIBLE,
            aggregatedVisible = aggregatedVisible && isShown,
            effectiveAlpha = effectiveAlpha(),
            timedOrTransitionActive = durationMs > 0L
        )
    )

    private fun effectiveAlpha(): Float {
        var value = alpha * transitionAlpha
        var ancestor = parent as? View
        while (ancestor != null) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value <= EFFECTIVE_ALPHA_THRESHOLD) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun syncCadence() {
        when (cadenceGate.update(effectiveCadenceActive())) {
            CadenceChange.START -> {
                removeCallbacks(frame)
                post(frame)
            }
            CadenceChange.STOP -> removeCallbacks(frame)
            CadenceChange.NONE -> Unit
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 250L
        const val EFFECTIVE_ALPHA_THRESHOLD = 0.01f
    }
}
