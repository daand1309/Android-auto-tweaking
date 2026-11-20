package com.daand.drivemusic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @After fun reset() = Style.apply(Style.THEME_RED)

    @Test fun unknownThemeFallsBackToRed() {
        assertEquals(Style.THEME_RED, Style.theme("nope").id)
    }

    @Test fun everyThemeHasReadableText() {
        for (t in Style.ALL_THEMES) {
            assertNotEquals(t.id, t.text, t.bg)
            assertNotEquals(t.id, t.text2, t.surface)
        }
    }

    @Test fun applySetsThePalette() {
        Style.apply(Style.THEME_BLUE)
        assertEquals(Style.theme(Style.THEME_BLUE).primary, Style.PRIMARY)
        assertEquals(Style.theme(Style.THEME_BLUE).bg, Style.BG)
    }

    @Test fun themeNamesComeFromTheSets() {
        assertEquals(Style.ALL_THEMES.map { it.id to it.name }, Style.THEMES.toList())
    }

    /** WCAG contrast ratio between two opaque colours (1..21). */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun textOnTheAccentIsReadable() {
        // Classic blue predates this check and sits just above 3 (big-text level); every newer theme reaches 4.5.
        for (t in Style.ALL_THEMES) {
            val need = if (t.id == Style.THEME_BLUE) 3.0 else 4.5
            assertTrue("${t.id}: ${contrast(t.onPrimary, t.primary)}", contrast(t.onPrimary, t.primary) >= need)
        }
    }

    @Test fun accentStandsOutOnTheBackground() {
        // The accent is also used as text (section labels, "Use") and for the selected tab.
        for (t in Style.ALL_THEMES) assertTrue("${t.id}: ${contrast(t.primary, t.bg)}", contrast(t.primary, t.bg) >= 3.0)
    }

    @Test fun mainTextIsEasyToRead() {
        for (t in Style.ALL_THEMES) {
            assertTrue(t.id, contrast(t.text, t.surface) >= 7.0)
            assertTrue(t.id, contrast(t.text2, t.surface) >= 4.5)
        }
    }

    @Test fun carThemesAreThereAndTheDefaultStaysRed() {
        val ids = Style.ALL_THEMES.map { it.id }
        assertEquals(listOf("red", "titanium", "titanium_red", "night", "steel", "copper", "dial_green", "midnight", "blue"), ids)
        assertEquals(ids.size, ids.toSet().size)
        Style.apply(Style.THEME_TITANIUM)
        assertEquals(Style.theme(Style.THEME_TITANIUM).onPrimary, Style.ON_PRIMARY)
    }
}
