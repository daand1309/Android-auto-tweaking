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

    /** A theme is one named set of colours. A new theme = a new entry in [ALL_THEMES]. */
    data class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val surface2: Int,
                     val primary: Int, val highlight: Int, val text: Int, val text2: Int)

    val ALL_THEMES: List<Theme> = listOf(
        // Red, black and silver.
        Theme(THEME_RED, "Red & silver", bg = 0xFF0A0A0B.toInt(), surface = 0xFF17171A.toInt(), surface2 = 0xFF26262B.toInt(),
            primary = 0xFFE60012.toInt(), highlight = 0xFF4A1015.toInt(), text = 0xFFF4F4F6.toInt(), text2 = 0xFFBDBFC3.toInt()),
        Theme(THEME_BLUE, "Classic blue", bg = 0xFF0E1116.toInt(), surface = 0xFF171C24.toInt(), surface2 = 0xFF232B36.toInt(),
            primary = 0xFF4F8CFF.toInt(), highlight = 0xFF1D3A6B.toInt(), text = 0xFFF2F4F8.toInt(), text2 = 0xFFB0BAC5.toInt()),
    )

    /** The theme with this id, or the red one for an unknown id (e.g. a theme removed in an update). */
    fun theme(id: String): Theme = ALL_THEMES.firstOrNull { it.id == id } ?: ALL_THEMES.first()

    val THEMES: Map<String, String> = ALL_THEMES.associate { it.id to it.name }

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

    fun apply(id: String) {
        val t = theme(id)
        BG = t.bg; SURFACE = t.surface; SURFACE2 = t.surface2
        PRIMARY = t.primary; HIGHLIGHT = t.highlight
        TEXT = t.text; TEXT2 = t.text2
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
