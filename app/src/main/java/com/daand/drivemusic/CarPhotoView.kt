package com.daand.drivemusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Your car photo for the home screen. A cut-out (transparent background) is shown whole; a normal
 * photo is cropped to a wide frame around the car (found automatically) and fades into the page.
 */
class CarPhotoView(context: Context, private val photo: Bitmap) : View(context) {

    /** True when the photo has a see-through background (e.g. a car on black, auto-removed). */
    private val cutout = photo.getPixel(0, 0) ushr 24 == 0 && photo.getPixel(photo.width - 1, 0) ushr 24 == 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Tall (phone wallpaper) photos get a slightly taller frame so the whole car fits. */
    private val frameRatio = if (photo.height > photo.width) 0.72f else 0.56f

    /** Where the car sits vertically (0..1 of the photo height, top of the crop). */
    private val focusTop: Float by lazy { focusCache.getOrPut(photo) { findFocus() } }

    private fun findFocus(): Float {
        val sw = 64
        val sh = (sw * photo.height / photo.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(photo, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== photo) small.recycle()
        val top = PhotoFraming.bestWindowTop(px, sw, sh, (sw * frameRatio).toInt())
        return top / sh.toFloat()
    }

    private companion object {
        /** The photo bitmap is cached, so its framing only needs working out once. */
        val focusCache = java.util.WeakHashMap<Bitmap, Float>()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (cutout) (w * photo.height / photo.width.toFloat()).toInt().coerceAtMost((w * 0.75f).toInt())
        else (w * frameRatio).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (cutout) {
            // Fit the whole car.
            val scale = minOf(w / photo.width, h / photo.height)
            val dw = photo.width * scale
            val dh = photo.height * scale
            canvas.drawBitmap(photo, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
            return
        }
        // Centre-crop the photo to the frame.
        val frame = w / h
        val src = if (photo.width / photo.height.toFloat() > frame) {
            val cw = (photo.height * frame).toInt()
            Rect((photo.width - cw) / 2, 0, (photo.width + cw) / 2, photo.height)
        } else {
            val ch = (photo.width / frame).toInt()
            val top = (focusTop * photo.height).toInt().coerceIn(0, photo.height - ch)
            Rect(0, top, photo.width, top + ch)
        }
        canvas.drawBitmap(photo, src, RectF(0f, 0f, w, h), paint)

        // Fade into the page background at the top, bottom and sides.
        val bg = Style.BG
        val clear = bg and 0x00FFFFFF
        val fade = Paint()
        fade.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(bg, clear, clear, bg), floatArrayOf(0f, 0.14f, 0.8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fade)
        fade.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(bg, clear, clear, bg), floatArrayOf(0f, 0.06f, 0.94f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fade)
    }
}

object PhotoFraming {
    /**
     * Finds the top row of the [window]-row band with the most detail (edges) - that's the car,
     * not the empty sky/backdrop - then nudges it up a little, because shiny-floor reflections
     * below the car also count as detail. Pure logic, unit tested.
     */
    fun bestWindowTop(px: IntArray, w: Int, h: Int, window: Int): Int {
        if (window >= h || w < 2) return 0
        fun lum(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        val energy = LongArray(h)
        for (y in 1 until h) for (x in 1 until w) {
            val l = lum(px[y * w + x])
            energy[y] += (Math.abs(l - lum(px[y * w + x - 1])) + Math.abs(l - lum(px[(y - 1) * w + x]))).toLong()
        }
        var sum = (0 until window).sumOf { energy[it] }
        var best = sum
        var bestTop = 0
        var bestEnd = 0 // equally good frames in a row: use the middle one
        for (t in 1..h - window) {
            sum += energy[t + window - 1] - energy[t - 1]
            if (sum > best) { best = sum; bestTop = t; bestEnd = t }
            else if (sum == best && bestEnd == t - 1) bestEnd = t
        }
        return ((bestTop + bestEnd) / 2 - window * 12 / 100).coerceAtLeast(0)
    }
}
