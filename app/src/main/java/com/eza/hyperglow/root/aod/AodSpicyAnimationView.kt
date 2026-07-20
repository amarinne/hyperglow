package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/** Small canvas port of Spicy's per-word scale/y/glow animation curves. */
internal class AodSpicyAnimationView(context: android.content.Context) : View(context) {
    private val words = listOf("今", "年", "も", "早", "いね")
    private val baseTextSize = 27f * resources.displayMetrics.scaledDensity
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseTextSize
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var startedAt = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (visibility != VISIBLE) return
            invalidate()
            postDelayed(this, 100L)
        }
    }

    fun start() {
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        removeCallbacks(frame)
        post(frame)
    }

    fun stop() {
        removeCallbacks(frame)
        startedAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) % 3_000L
        val activeIndex = (elapsed / 600L).toInt().coerceIn(0, words.lastIndex)
        val progress = (elapsed % 600L) / 600f
        val spacing = 44f * resources.displayMetrics.density
        val center = width / 2f
        val startX = center - spacing * (words.size - 1) / 2f
        val baseY = height / 2f - (paint.ascent() + paint.descent()) / 2f
        words.forEachIndexed { index, word ->
            val t = when {
                index < activeIndex -> 1f
                index == activeIndex -> progress
                else -> 0f
            }
            val scale = scaleSpline(t)
            val y = yOffsetSpline(t) * paint.textSize
            val glow = glowSpline(t)
            paint.textSize = baseTextSize * scale
            paint.alpha = (150 + 105 * glow).toInt().coerceIn(0, 255)
            paint.setShadowLayer(8f * glow, 0f, 0f, Color.WHITE)
            canvas.drawText(word, startX + spacing * index, baseY + y, paint)
            paint.clearShadowLayer()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (64f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    private fun scaleSpline(t: Float): Float = if (t <= 0.7f) {
        lerp(0.95f, 1.0505f, t / 0.7f)
    } else lerp(1.0505f, 1f, (t - 0.7f) / 0.3f)

    private fun yOffsetSpline(t: Float): Float = if (t <= 0.9f) {
        lerp(0.01f, -(1f / 60f), t / 0.9f)
    } else lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f)

    private fun glowSpline(t: Float): Float = when {
        t <= 0.15f -> lerp(0f, 1f, t / 0.15f)
        t <= 0.6f -> 1f
        else -> lerp(1f, 0f, (t - 0.6f) / 0.4f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)
}
