package com.daand.drivemusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * The Drive Mode "power-up": a rev counter doing its self-test like a real instrument cluster - the
 * needle sweeps to the end of the scale and back while the scale lights up. [onDone] runs at the end.
 */
class GaugeSweepView(context: Context, private val accent: Int, private val track: Int) : View(context) {
    /** 0 = needle at rest, 1 = full scale. */
    private var level = 0f
    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = track }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = accent }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2.5f * d }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * d; color = 0xFFFFFFFF.toInt() }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val oval = RectF()

    fun play(onDone: () -> Unit = {}) {
        // Up to full scale and back down: 0 -> 1 -> 0 over the run (own frame clock, see Motion).
        Motion.play(1300, delayMs = 150, interp = AccelerateDecelerateInterpolator(), update = { f ->
            level = if (f < 0.5f) f * 2 else (1 - f) * 2; invalidate()
        }, end = { level = 0f; invalidate(); onDone() })
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.055f
        trackPaint.strokeWidth = stroke; arcPaint.strokeWidth = stroke
        val cx = width / 2f; val cy = height / 2f
        val r = size / 2f - stroke
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // A 270° scale, open at the bottom, like a rev counter.
        canvas.drawArc(oval, START, SWEEP, false, trackPaint)
        if (level > 0.001f) canvas.drawArc(oval, START, SWEEP * level, false, arcPaint)
        // Ticks: they light up as the needle passes them.
        for (i in 0..TICKS) {
            val f = i / TICKS.toFloat()
            val a = Math.toRadians((START + SWEEP * f).toDouble())
            val outer = r - stroke * 1.2f; val inner = outer - (if (i % 2 == 0) stroke * 1.3f else stroke * 0.7f)
            tickPaint.color = if (f <= level) accent else track
            canvas.drawLine(cx + (inner * Math.cos(a)).toFloat(), cy + (inner * Math.sin(a)).toFloat(),
                cx + (outer * Math.cos(a)).toFloat(), cy + (outer * Math.sin(a)).toFloat(), tickPaint)
        }
        // The needle (short, from the hub) - the name sits in the middle, above it.
        val na = Math.toRadians((START + SWEEP * level).toDouble())
        val nl = r * 0.32f; val hubY = cy + r * 0.55f
        canvas.drawLine(cx, hubY, cx + (nl * Math.cos(na)).toFloat(), hubY + (nl * Math.sin(na)).toFloat(), needlePaint)
        canvas.drawCircle(cx, hubY, 5f * d, hubPaint)
    }

    private companion object {
        const val START = 135f
        const val SWEEP = 270f
        const val TICKS = 10
    }
}
