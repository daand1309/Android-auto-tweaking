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
    const val THEME_TITANIUM = "titanium"

    /** A theme is one named set of colours. A new theme = a new entry in [ALL_THEMES]. */
    data class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val surface2: Int,
                     val primary: Int, val highlight: Int, val text: Int, val text2: Int,
                     /** Text and icons on an accent fill: dark for the light accents (silver, white, copper, green). */
                     val onPrimary: Int = 0xFFFFFFFF.toInt())

    val ALL_THEMES: List<Theme> = listOf(
        // Red, black and silver.
        Theme(THEME_RED, "Red & silver", bg = 0xFF0A0A0B.toInt(), surface = 0xFF17171A.toInt(), surface2 = 0xFF26262B.toInt(),
            primary = 0xFFE60012.toInt(), highlight = 0xFF4A1015.toInt(), text = 0xFFF4F4F6.toInt(), text2 = 0xFFBDBFC3.toInt()),
        // Built around the owner's grey ASX (grey/black interior); see design/themes-preview.png.
        Theme(THEME_TITANIUM, "Titanium", bg = 0xFF0D0F11.toInt(), surface = 0xFF1A1D21.toInt(), surface2 = 0xFF2B3036.toInt(),
            primary = 0xFFC3CCD6.toInt(), highlight = 0xFF363C44.toInt(), text = 0xFFF2F4F6.toInt(), text2 = 0xFFA3ACB6.toInt(),
            onPrimary = 0xFF0D0F11.toInt()),
        Theme("titanium_red", "Titanium red", bg = 0xFF0E1013.toInt(), surface = 0xFF1B1E23.toInt(), surface2 = 0xFF2C3037.toInt(),
            primary = 0xFFE60012.toInt(), highlight = 0xFF4A1418.toInt(), text = 0xFFF2F4F6.toInt(), text2 = 0xFFA9B0B8.toInt()),
        // Darkest, dimmed text: keeps the eyes used to the dark at night.
        Theme("night", "Night drive", bg = 0xFF000000.toInt(), surface = 0xFF0C0C0D.toInt(), surface2 = 0xFF18181A.toInt(),
            primary = 0xFFC8161F.toInt(), highlight = 0xFF2E0A0D.toInt(), text = 0xFFCFCFD2.toInt(), text2 = 0xFF8A8A90.toInt(),
            onPrimary = 0xFFF0F0F0.toInt()),
        Theme("steel", "Steel blue", bg = 0xFF0C1014.toInt(), surface = 0xFF182027.toInt(), surface2 = 0xFF26303A.toInt(),
            primary = 0xFF2A74C0.toInt(), highlight = 0xFF163556.toInt(), text = 0xFFF0F4F8.toInt(), text2 = 0xFFA6B3C0.toInt()),
        Theme("copper", "Copper", bg = 0xFF0F0E0D.toInt(), surface = 0xFF1D1B19.toInt(), surface2 = 0xFF2E2B28.toInt(),
            primary = 0xFFD9772B.toInt(), highlight = 0xFF4A2A12.toInt(), text = 0xFFF5F2EE.toInt(), text2 = 0xFFB8AFA6.toInt(),
            onPrimary = 0xFF140B03.toInt()),
        Theme("dial_green", "Dial green", bg = 0xFF0B0F0D.toInt(), surface = 0xFF171D1A.toInt(), surface2 = 0xFF25302B.toInt(),
            primary = 0xFF3FC48A.toInt(), highlight = 0xFF14402C.toInt(), text = 0xFFEEF5F1.toInt(), text2 = 0xFFA3B5AC.toInt(),
            onPrimary = 0xFF06140D.toInt()),
        // True black: the pixels are off on an OLED screen (saves battery).
        Theme("midnight", "Midnight black", bg = 0xFF000000.toInt(), surface = 0xFF101010.toInt(), surface2 = 0xFF1E1E1E.toInt(),
            primary = 0xFFFFFFFF.toInt(), highlight = 0xFF2A2A2A.toInt(), text = 0xFFFFFFFF.toInt(), text2 = 0xFF9E9E9E.toInt(),
            onPrimary = 0xFF000000.toInt()),
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
    var ON_PRIMARY = 0; private set  // text/icons on an accent fill
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
        TEXT = t.text; TEXT2 = t.text2; ON_PRIMARY = t.onPrimary
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
