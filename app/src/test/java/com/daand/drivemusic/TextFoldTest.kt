package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFoldTest {
    @Test fun shortTextIsNotFolded() {
        assertNull(TextFold.short("x".repeat(100)))
    }

    @Test fun longTextFoldsToFirstSentence() {
        val s = "Checks every 10 minutes and stays quiet. " + "More words here. ".repeat(12)
        assertEquals("Checks every 10 minutes and stays quiet.", TextFold.short(s))
    }

    @Test fun aQuestionKeepsItsAnswer() {
        val s = "Music too quiet? Set the media volume above. " + "Quiet calls? Turn on VoLTE and raise the volume. ".repeat(4)
        assertEquals("Music too quiet? Set the media volume above.", TextFold.short(s))
    }

    @Test fun abbreviationsAreNotSentenceEnds() {
        val s = "Much more natural voices, e.g. a properly posh British butler. " + "Filler text here. ".repeat(10)
        assertEquals("Much more natural voices, e.g. a properly posh British butler.", TextFold.short(s))
    }

    @Test fun inclIsNotASentenceEnd() {
        val s = "A voice from Microsoft - the text (incl. your name) goes to Microsoft. " + "Filler text here. ".repeat(10)
        assertEquals("A voice from Microsoft - the text (incl. your name) goes to Microsoft.", TextFold.short(s))
    }

    @Test fun noSentenceBreakCutsAtASpace() {
        val s = "word ".repeat(40).trim()
        val f = TextFold.short(s)!!
        assertTrue(f, f.length <= 121 && f.endsWith("…") && !f.contains("wor…"))
    }

    @Test fun forPageMapsEveryPage() {
        val m = mapOf("MUSIC" to "music", "OBD" to "car_data", "TRIPS" to "trips", "CAR" to "car_screen",
            "THEMES" to "themes", "SETUP" to "setup", "HELP" to "help", "GREETING" to "greeting", "VOICE" to "voice",
            "DRIVE_MODE" to "drive_mode", "UPDATES" to "updates", "BACKUP" to "backup", "SERVICE" to "service")
        m.forEach { (page, id) -> assertEquals(page, id, HomeFeatures.forPage(page)?.id) }
        assertNull(HomeFeatures.forPage("HOME"))
    }
}
