package com.daand.drivemusic

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Shared dark-theme palette and simple rounded backgrounds (no external UI library). */
object Style {
    const val BG = 0xFF0E1116.toInt()        // page background
    const val SURFACE = 0xFF171C24.toInt()   // cards
    const val SURFACE2 = 0xFF232B36.toInt()  // inputs / secondary buttons
    const val PRIMARY = 0xFF4F8CFF.toInt()   // accent
    const val GREEN = 0xFF32D74B.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val TEXT = 0xFFF2F4F8.toInt()
    const val TEXT2 = 0xFF9AA6B2.toInt()

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
    }

    /** Filled button with a ripple on press. */
    fun button(color: Int, radiusPx: Float): RippleDrawable {
        val content = rounded(color, radiusPx)
        val mask = rounded(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    fun tint(color: Int) = ColorStateList.valueOf(color)
}
