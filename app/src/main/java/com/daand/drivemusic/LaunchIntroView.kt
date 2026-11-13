package com.daand.drivemusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.PathParser

/**
 * The launch ("boot") animation, drawn by the app itself: the same as res/drawable/splash_icon.xml
 * (note pops in with a swing, two sound waves roll out, on the red circle). Android plays that one
 * at a cold start - but not when the phone's animations are off, so then the app plays this instead.
 */
class LaunchIntroView(context: Context) : View(context) {
    private var note = 0f      // 0..1 (overshoots a little)
    private var wave1 = 0f
    private var wave2 = 0f
    private val notePath = PathParser.createPathFromPathData(
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE60012.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val m = Matrix()
    private val p = Path()
    private val oval = RectF()

    fun play(onDone: () -> Unit) {
        Motion.play(420, interp = OvershootInterpolator(), update = { note = it; invalidate() })
        Motion.play(300, delayMs = 300, update = { wave1 = it; invalidate() })
        Motion.play(320, delayMs = 460, update = { wave2 = it; invalidate() }, end = onDone)
    }

    override fun onDraw(canvas: Canvas) {
        // The 240-unit design canvas, scaled to 60% of the shorter side, centred.
        val side = minOf(width, height) * 0.6f
        val k = side / 240f
        canvas.save()
        canvas.translate((width - side) / 2, (height - side) / 2)
        canvas.scale(k, k)
        canvas.drawCircle(120f, 120f, 80f, circle)
        // Note: scales up from its foot with a swing.
        canvas.save()
        canvas.rotate(-25f * (1 - note.coerceIn(0f, 1f)), 100f, 160f)
        canvas.scale(note, note, 100f, 160f)
        m.setScale(4.5f, 4.5f); m.postTranslate(46f, 62f)
        p.set(notePath); p.transform(m)
        canvas.drawPath(p, white)
        canvas.restore()
        stroke.strokeWidth = 7f
        arc(canvas, 148f, 120f, 30f, 22f, wave1)   // = "M148,98 A30,30 0 0,1 148,142"
        arc(canvas, 162f, 120f, 50f, 38f, wave2)   // = "M162,82 A50,50 0 0,1 162,158"
        canvas.restore()
    }

    /** The sound-wave arc whose ends are at (x, cy ± half), radius r, drawn [f] of the way (top to bottom). */
    private fun arc(canvas: Canvas, x: Float, cy: Float, r: Float, half: Float, f: Float) {
        if (f <= 0f) return
        val cx = x - Math.sqrt((r * r - half * half).toDouble()).toFloat()
        val a = Math.toDegrees(Math.asin((half / r).toDouble())).toFloat()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        stroke.alpha = (255 * minOf(1f, f * 1.5f)).toInt()
        canvas.drawArc(oval, -a, 2 * a * f, false, stroke)
    }
}
