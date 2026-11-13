package com.daand.drivemusic

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Shared palette and simple rounded backgrounds (no external UI library). Switchable theme. */
object Style {
    /** Theme ids stored in settings. */
    const val THEME_RED = "red"
    const val THEME_BLUE = "blue"
    val THEMES = linkedMapOf(THEME_RED to "Red & silver", THEME_BLUE to "Classic blue")

    var BG = 0; private set          // page background
    var SURFACE = 0; private set     // cards
    var SURFACE2 = 0; private set    // inputs / secondary buttons
    var PRIMARY = 0; private set     // accent (buttons, section labels, switches)
    var HIGHLIGHT = 0; private set   // "update available" card
    var TEXT = 0; private set
    var TEXT2 = 0; private set       // secondary text
    const val GREEN = 0xFF32D74B.toInt()
    /** Dark text for bright fills (green/amber): white on them is hard to read. */
    const val ON_BRIGHT = 0xFF0A0A0B.toInt()
    /** Stop-now red. Deliberately a different red from the theme accent, so warnings stand out. */
    const val DANGER = 0xFFC62828.toInt()

    // The few sizes everything uses (dp): small controls, cards and buttons, big Drive Mode controls.
    const val R_SMALL = 10
    const val R_CARD = 14
    const val R_BIG = 20
    const val AMBER = 0xFFFFB020.toInt()
    const val WARN_BG = 0xFF4A3510.toInt()

    init {
        apply(THEME_RED)
    }

    fun apply(theme: String) {
        if (theme == THEME_BLUE) {
            BG = 0xFF0E1116.toInt(); SURFACE = 0xFF171C24.toInt(); SURFACE2 = 0xFF232B36.toInt()
            PRIMARY = 0xFF4F8CFF.toInt(); HIGHLIGHT = 0xFF1D3A6B.toInt()
            TEXT = 0xFFF2F4F8.toInt(); TEXT2 = 0xFFB0BAC5.toInt()
        } else {
            // Red, black and silver.
            BG = 0xFF0A0A0B.toInt(); SURFACE = 0xFF17171A.toInt(); SURFACE2 = 0xFF26262B.toInt()
            PRIMARY = 0xFFE60012.toInt(); HIGHLIGHT = 0xFF4A1015.toInt()
            TEXT = 0xFFF4F4F6.toInt(); TEXT2 = 0xFFBDBFC3.toInt()
        }
    }

    /** The accent at ~40% opacity (switch tracks). */
    fun primarySoft() = (PRIMARY and 0x00FFFFFF) or 0x66000000

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

    /** Digits all the same width, so live numbers (speed, RPM) don't shuffle sideways as they change. */
    const val TABULAR = "tnum"
}
