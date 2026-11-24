package com.daand.drivemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** From the 17 Nov 18:14 drive: YouTube Music stopped 4 times in its first 30 s (no other audio). */
class StartRulesTest {
    @Test fun upToThreeQuickRestartsWhileItSettles() {
        // stop 2 s after the start: restart #1 (used to be the only one)
        assertTrue(StartRules.quickRestart(sinceFirstStartMs = 2_000, sinceLastStartMs = 2_000, restartsUsed = 0, bounceWindowMs = 6_000))
        // stop 1 s after that restart, 8 s in: restart #2 (this one used to be called "deliberate")
        assertTrue(StartRules.quickRestart(8_000, 1_000, restartsUsed = 1, bounceWindowMs = 6_000))
        assertTrue(StartRules.quickRestart(20_000, 1_000, restartsUsed = 2, bounceWindowMs = 6_000))
        assertFalse("three is enough - then it's a normal pause", StartRules.quickRestart(25_000, 1_000, restartsUsed = 3, bounceWindowMs = 6_000))
        assertFalse("long after the start: a normal pause", StartRules.quickRestart(60_000, 1_000, restartsUsed = 0, bounceWindowMs = 6_000))
        assertFalse("a stop well after the last (re)start: a normal pause", StartRules.quickRestart(10_000, 9_000, restartsUsed = 0, bounceWindowMs = 6_000))
        assertFalse("bounce switched off (tests)", StartRules.quickRestart(2_000, 2_000, restartsUsed = 0, bounceWindowMs = 0))
    }

    @Test fun neverDeliberateInTheFirstMinute() {
        assertFalse("YouTube Music settling, not you", StartRules.looksDeliberate(sinceResumeMs = 4_000, noClues = true, sinceFirstStartMs = 15_000))
        assertTrue("later in the drive: paused again right after an auto-resume = you", StartRules.looksDeliberate(4_000, true, sinceFirstStartMs = 600_000))
        assertFalse("with a clue (a call, the Assistant...) it isn't you", StartRules.looksDeliberate(4_000, false, 600_000))
        assertFalse("long after the auto-resume", StartRules.looksDeliberate(120_000, true, 600_000))
    }

    @Test fun aPauseThatPlaysOnBySoonWasTheMusicAppsHiccup() {
        // 18 Nov 09:51: paused with no clues, played on by itself 4 s later - YouTube Music, not you.
        assertTrue(StartRules.selfRecovered(4_400))
        assertFalse("long after: you pressed play yourself", StartRules.selfRecovered(30_000))
        assertFalse(StartRules.selfRecovered(-1))
    }

    @Test fun stopJustAfterOurVoiceIsTheMusicAppSettling() {
        // 24 Nov 12:16: voice done at :17.6, music back at :18.0, stopped at :19.1, confirmed at :22.1 (4.5 s).
        assertTrue(StartRules.afterOwnVoice(4_540, wasPlayingBefore = true))
        assertFalse("music wasn't playing before we spoke", StartRules.afterOwnVoice(4_540, wasPlayingBefore = false))
        assertFalse("long after the voice: a real pause", StartRules.afterOwnVoice(StartRules.AFTER_VOICE_MS, wasPlayingBefore = true))
    }
}
