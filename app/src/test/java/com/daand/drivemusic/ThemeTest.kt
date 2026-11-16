package com.daand.drivemusic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
