package com.daand.drivemusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * An original side-view drawing of a compact crossover in the theme colour, for the home screen.
 * Drawn in a 100 x 42 unit space and scaled to the view's width. Headlights glow while driving.
 */
class CarHeroView(context: Context) : View(context) {

    var driving = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val cladding = Path()
    private val windows = Path()

    init {
        // Body outline (front on the left).
        body.apply {
            moveTo(4f, 29f); lineTo(3f, 22f); quadTo(3.5f, 17.5f, 8f, 16.8f); lineTo(28f, 13.8f); lineTo(39f, 6.2f)
            quadTo(41f, 5f, 44f, 5f); lineTo(70f, 5.3f); quadTo(79f, 5.9f, 84f, 8.4f); lineTo(92f, 12.2f)
            quadTo(96.5f, 13.3f, 96.8f, 16.5f); lineTo(97f, 27f); quadTo(96.8f, 31f, 93f, 31f); lineTo(87.5f, 31f)
            arcTo(oval(78f), 0f, -180f); lineTo(31.5f, 31f); arcTo(oval(22f), 0f, -180f); lineTo(6f, 31f)
            quadTo(4f, 31f, 4f, 29f); close()
        }
        // Dark lower cladding following the wheel arches.
        cladding.apply {
            moveTo(4.5f, 27.5f); lineTo(12.7f, 27.5f); arcTo(oval(22f), 201.6f, 136.8f); lineTo(68.8f, 27.5f)
            arcTo(oval(78f), 201.6f, 136.8f); lineTo(96.9f, 27.5f); lineTo(96.8f, 29f); quadTo(96.5f, 31f, 93f, 31f)
            lineTo(87.5f, 31f); arcTo(oval(78f), 0f, -180f); lineTo(31.5f, 31f); arcTo(oval(22f), 0f, -180f)
            lineTo(6f, 31f); quadTo(4f, 31f, 4f, 29f); close()
        }
        windows.apply {
            // Rising window line, roof dropping towards the rear.
            moveTo(30.8f, 13.6f); lineTo(40.4f, 7.1f); quadTo(41.8f, 6.5f, 44f, 6.5f); lineTo(69.5f, 6.8f)
            quadTo(77.5f, 7.3f, 82f, 9.3f); lineTo(87.5f, 12.2f); close()
        }
    }

    private fun oval(cx: Float, r: Float = 9.5f) = RectF(cx - r, 31f - r, cx + r, 31f + r)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.42f).toInt())
    }

    private fun mix(c: Int, other: Int, t: Float) = Color.rgb(
        (Color.red(c) + (Color.red(other) - Color.red(c)) * t).toInt(),
        (Color.green(c) + (Color.green(other) - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (Color.blue(other) - Color.blue(c)) * t).toInt(),
    )

    override fun onDraw(canvas: Canvas) {
        val s = width / 100f
        val oy = 2 * s
        val main = Style.PRIMARY
        val light = mix(main, Color.WHITE, 0.3f)
        val dark = mix(main, Color.BLACK, 0.47f)

        // Ground shadow and a soft glow in the theme colour.
        paint.shader = RadialGradient(50 * s, 40 * s + oy, 46 * s, 0xD0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.12f, 0f, 40 * s + oy)
        canvas.drawCircle(50 * s, 40 * s + oy, 46 * s, paint); canvas.restore()
        paint.shader = RadialGradient(50 * s, 40 * s + oy, 40 * s, (main and 0x00FFFFFF) or 0x40000000, main and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.1f, 0f, 40 * s + oy)
        canvas.drawCircle(50 * s, 40 * s + oy, 40 * s, paint); canvas.restore()

        // Headlight beam while driving.
        if (driving) {
            paint.shader = RadialGradient(2 * s, 21 * s + oy, 22 * s, 0x66FFF6D8, 0x00FFF6D8, Shader.TileMode.CLAMP)
            canvas.drawCircle(2 * s, 21 * s + oy, 22 * s, paint)
        }

        canvas.save()
        canvas.translate(0f, oy)
        canvas.scale(s, s)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 5f, 0f, 31f, intArrayOf(light, main, dark), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(body, paint)
        paint.shader = null

        paint.color = 0xE60F0F12.toInt(); canvas.drawPath(cladding, paint)

        paint.shader = LinearGradient(0f, 6.5f, 0f, 13.5f, 0xFF3B4250.toInt(), 0xFF101318.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(windows, paint)
        paint.shader = null
        paint.color = 0xFF16181D.toInt()                                              // blacked-out pillars
        canvas.drawRect(56.3f, 6.4f, 58.1f, 13.4f, paint); canvas.drawRect(71.5f, 6.9f, 72.9f, 12.9f, paint)

        paint.color = 0xFFC9CBCF.toInt()                                              // roof rail
        canvas.drawRect(46f, 4.1f, 70f, 4.65f, paint)
        canvas.drawRect(47f, 4.1f, 47.6f, 5.1f, paint); canvas.drawRect(69.2f, 4.1f, 69.8f, 5.35f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.35f; paint.color = 0x59FFFFFF                           // shoulder line
        canvas.drawPath(Path().apply { moveTo(9f, 17.2f); lineTo(28.5f, 14.7f); lineTo(90f, 13.4f) }, paint)
        paint.strokeWidth = 0.25f; paint.color = 0x59000000                           // door lines
        canvas.drawLine(57.2f, 13.5f, 56.9f, 27.4f, paint); canvas.drawLine(39.5f, 14.1f, 40.5f, 27.4f, paint)
        paint.style = Paint.Style.FILL

        paint.color = 0x73FFFFFF                                                      // door handles
        canvas.drawRect(48.5f, 15.9f, 51.9f, 16.5f, paint); canvas.drawRect(63f, 15.6f, 66.4f, 16.2f, paint)

        paint.color = 0xEB0F0F12.toInt()                                              // black lower front bumper
        canvas.drawPath(Path().apply { moveTo(3.2f, 24f); lineTo(11.5f, 23.2f); quadTo(12.7f, 26f, 12.7f, 27.5f); lineTo(4.5f, 27.5f); close() }, paint)
        paint.color = 0xFFC9CBCF.toInt(); canvas.drawCircle(7f, 25.6f, 0.9f, paint)  // fog light

        paint.color = if (driving) 0xFFFFFBEA.toInt() else 0xFFD8E6EE.toInt()       // headlight
        canvas.drawPath(Path().apply { moveTo(4f, 20.2f); lineTo(11.8f, 17.6f); lineTo(12.3f, 18.6f); lineTo(4.8f, 21.4f); close() }, paint)
        paint.color = 0xFFFF2A2A.toInt()                                              // tail light
        canvas.drawPath(Path().apply { moveTo(93.5f, 14.2f); lineTo(96.6f, 15.2f); lineTo(96.7f, 18.4f); lineTo(93.8f, 17.4f); close() }, paint)
        paint.color = dark                                                            // mirror
        canvas.drawPath(Path().apply { moveTo(31f, 14.6f); lineTo(34.6f, 13.4f); lineTo(35.1f, 15.4f); lineTo(31.4f, 15.8f); close() }, paint)

        for (x in floatArrayOf(22f, 78f)) drawWheel(canvas, x)
        canvas.restore()
    }

    private fun drawWheel(canvas: Canvas, x: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF111214.toInt(); canvas.drawCircle(x, 31f, 7.6f, paint)
        paint.color = 0xFF2A2C31.toInt(); canvas.drawCircle(x, 31f, 5.1f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f; paint.color = 0xFFB8BBC1.toInt()
        for (i in 0 until 5) {
            val a = i * 2 * Math.PI / 5 - Math.PI / 2
            canvas.drawLine(x + cos(a).toFloat() * 1.2f, 31f + sin(a).toFloat() * 1.2f,
                x + cos(a).toFloat() * 4.6f, 31f + sin(a).toFloat() * 4.6f, paint)
        }
        paint.strokeWidth = 0.5f; paint.color = 0xFFC9CBCF.toInt(); canvas.drawCircle(x, 31f, 4.9f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFD6D8DC.toInt(); canvas.drawCircle(x, 31f, 1.1f, paint)
    }
}
