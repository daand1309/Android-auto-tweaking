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
    /** Photo of your car for the home screen (stored as JPEG; the name is kept for older installs). */
    const val HERO = "hero_car.png"
    private const val MAX_SIDE = 1200
    /** Wider than any phone screen, so the photo is sharp edge to edge. */
    private const val CAR_PHOTO_WIDTH = 1600
    private const val CAR_PHOTO_MAX_HEIGHT = 4000

    fun file(ctx: Context, name: String = FILE) = File(ctx.filesDir, name)

    fun exists(ctx: Context, name: String = FILE) = file(ctx, name).exists()

    /** Decoded images, so the home screen's 5-second refresh doesn't re-read a big photo each time. */
    private val cache = HashMap<String, Pair<Long, Bitmap>>()

    fun load(ctx: Context, name: String = FILE): Bitmap? {
        val f = file(ctx, name)
        if (!f.exists()) { cache.remove(name); return null }
        cache[name]?.let { (stamp, bmp) -> if (stamp == f.lastModified()) return bmp }
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        cache[name] = f.lastModified() to bmp
        return bmp
    }

    fun clear(ctx: Context, name: String = FILE) {
        cache.remove(name)
        file(ctx, name).delete()
    }

    /**
     * Copies the chosen image, scaled down. For the intro logo a dark background is made
     * transparent; the car photo is kept as-is (a dark studio shot is still a photo, not a logo).
     */
    fun save(ctx: Context, uri: Uri, name: String = FILE) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file isn't an image" }
        cache.remove(name)
        if (name == HERO) { saveCarPhoto(ctx, uri, bounds.outWidth, bounds.outHeight); return }

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
        file(ctx, name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The car photo is shown full-width, so it's sized by WIDTH (sharp on any phone screen) and
     * scaled smoothly to the exact size - not in halving steps, which could leave it half as sharp.
     * Saved as high-quality JPEG (photos as PNG get huge and slow to load).
     */
    private fun saveCarPhoto(ctx: Context, uri: Uri, srcW: Int, srcH: Int) {
        val (tw, th) = carPhotoSize(srcW, srcH)
        var sample = 1
        // Cheap pre-shrink while decoding, never below the target size.
        while (srcW / (sample * 2) >= tw && srcH / (sample * 2) >= th) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("That file isn't an image")
        val out = if (src.width == tw && src.height == th) src else Bitmap.createScaledBitmap(src, tw, th, true)
        file(ctx, HERO).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    /**
     * Car photo target size: at most [CAR_PHOTO_WIDTH] wide and [CAR_PHOTO_MAX_HEIGHT] tall (so an
     * extreme panorama can't use hundreds of MB of memory), never upscaled, aspect kept.
     */
    fun carPhotoSize(w: Int, h: Int): Pair<Int, Int> {
        var tw = minOf(w, CAR_PHOTO_WIDTH)
        var th = maxOf(1, (h.toLong() * tw / w).toInt())
        if (th > CAR_PHOTO_MAX_HEIGHT) {
            th = CAR_PHOTO_MAX_HEIGHT
            tw = maxOf(1, (w.toLong() * th / h).toInt())
        }
        return tw to th
    }

    // ------------------------------------------------------------------ pure pixel logic (unit tested)

    private fun lum(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    /** True when all four corners are near-black, i.e. a light logo on a dark square. */
    fun hasDarkBackground(px: IntArray, w: Int, h: Int): Boolean {
        if (w == 0 || h == 0) return false
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        return corners.all { (it ushr 24) < 16 || lum(it) < 40 }
    }

    /**
     * Removes a black background while keeping the logo's colours: black becomes transparent,
     * bright colours stay solid, and soft edges/gradients become partly see-through. (The colour is
     * "un-mixed" from black, so drawn on black it looks exactly like the original.)
     */
    fun lightOnTransparent(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val l = lum(c)
            val a = (c ushr 24) * l / 255
            if (a == 0 || l == 0) { px[i] = 0; continue }
            val r = ((c shr 16) and 0xFF) * 255 / l
            val g = ((c shr 8) and 0xFF) * 255 / l
            val b = (c and 0xFF) * 255 / l
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
