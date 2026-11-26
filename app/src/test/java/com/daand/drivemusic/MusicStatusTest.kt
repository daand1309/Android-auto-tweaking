package com.daand.drivemusic

import com.daand.drivemusic.MusicStatus.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicStatusTest {
    @Test fun playingSaysWhat() {
        assertEquals("MUSIC: playing - Samurai - Lupe Fiasco", MusicStatus.line(Facts(ytState = "PLAYING", song = "Samurai - Lupe Fiasco")))
        assertEquals("MUSIC: radio on - NPO Radio 2", MusicStatus.line(Facts(radio = "NPO Radio 2", ytState = "PAUSED")))
    }

    @Test fun silenceSaysWhy() {
        // 26 Nov 20:36: auto-resume switched off for the drive, 14 silent minutes.
        val l = MusicStatus.line(Facts(ytState = "PAUSED", autoResumeThisDrive = false))
        assertTrue(l, l.startsWith("MUSIC: SILENT (YouTube Music PAUSED)") && l.contains("off for this drive"))
        assertTrue(MusicStatus.line(Facts(ytState = "PAUSED", youPaused = true)).contains("you paused it"))
        assertTrue(MusicStatus.line(Facts(ytState = "PAUSED", resumeLimitReached = true)).contains("limit"))
        assertTrue(MusicStatus.line(Facts(ytState = "STOPPED")).contains("nothing is bringing it back"))
        assertTrue(MusicStatus.line(Facts(ytState = "PAUSED", otherSound = "navigation voice")).endsWith("also playing: navigation voice"))
    }

    @Test fun whatYouDidComesFirst() {
        // You pausing explains the silence even with auto-resume off.
        assertTrue(MusicStatus.line(Facts(ytState = "PAUSED", youPaused = true, autoResumeThisDrive = false)).contains("you paused it"))
    }
}
