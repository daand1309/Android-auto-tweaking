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

    @Test fun watchdogActsOnlyOnUnexplainedSilence() {
        // 27 Nov 10:03:41 -> 10:08: PAUSED, nothing explaining it, five silent minutes.
        assertTrue(MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED")))
        assertTrue(!MusicStatus.unexplainedSilence(Facts(ytState = "PLAYING")))
        assertTrue("you paused it", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", youPaused = true)))
        assertTrue("auto-resume off", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", autoResumeSetting = false)))
        assertTrue("off this drive", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", autoResumeThisDrive = false)))
        assertTrue("Waze talking", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", otherSound = "navigation voice")))
        assertTrue("radio on", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", radio = "Qmusic")))
        assertTrue("already on it", !MusicStatus.unexplainedSilence(Facts(ytState = "PAUSED", resumePending = true)))
    }

    @Test fun watchdogWaitsTwoLooksAndStopsAfterFive() {
        assertTrue(!MusicStatus.watchdogShouldPlay(silentLooks = 1, recentPlays = 0))
        assertTrue(MusicStatus.watchdogShouldPlay(silentLooks = 2, recentPlays = 0))
        assertTrue(MusicStatus.watchdogShouldPlay(silentLooks = 2, recentPlays = MusicStatus.WATCH_MAX - 1))
        assertTrue(!MusicStatus.watchdogShouldPlay(silentLooks = 5, recentPlays = MusicStatus.WATCH_MAX))
    }
}
