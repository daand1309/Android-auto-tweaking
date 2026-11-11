package com.daand.drivemusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Your own picture for the Drive Mode intro (e.g. your car's badge). Picked from the phone and
 * stored only in the app's private storage - never bundled with or published in the app.
 */
object IntroImage {
    private const val FILE = "intro_logo.png"
    private const val MAX_SIDE = 1200

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun exists(ctx: Context) = file(ctx).exists()

    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Copies the chosen image, scaled down, and makes a dark background transparent. */
    fun save(ctx: Context, uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("That file isn't an image")

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (hasDarkBackground(px, w, h)) lightOnTransparent(px)
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ pure pixel logic (unit tested)

    private fun lum(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    /** True when all four corners are near-black, i.e. a light logo on a dark square. */
    fun hasDarkBackground(px: IntArray, w: Int, h: Int): Boolean {
        if (w == 0 || h == 0) return false
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        return corners.all { (it ushr 24) < 16 || lum(it) < 40 }
    }

    /** Turns brightness into opacity: white stays solid white, black becomes fully transparent. */
    fun lightOnTransparent(px: IntArray) {
        for (i in px.indices) {
            val a = (px[i] ushr 24) * lum(px[i]) / 255
            px[i] = (a shl 24) or 0x00FFFFFF
        }
    }
}
