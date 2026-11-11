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
 * photo is cropped to a wide frame and fades into the page at the top and bottom.
 */
class CarPhotoView(context: Context, private val photo: Bitmap) : View(context) {

    /** True when the photo has a see-through background (e.g. a car on black, auto-removed). */
    private val cutout = photo.getPixel(0, 0) ushr 24 == 0 && photo.getPixel(photo.width - 1, 0) ushr 24 == 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (cutout) (w * photo.height / photo.width.toFloat()).toInt().coerceAtMost((w * 0.75f).toInt())
        else (w * 0.56f).toInt()
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
            Rect(0, (photo.height - ch) / 2, photo.width, (photo.height + ch) / 2)
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
