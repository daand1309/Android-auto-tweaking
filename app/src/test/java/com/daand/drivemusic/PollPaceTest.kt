package com.daand.drivemusic

import com.daand.drivemusic.obd.ObdManager
import com.daand.drivemusic.obd.PollPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPaceTest {
    @Test fun everyThreeSecondsNormally() {
        assertEquals(3_000L, PollPace.interval(liveView = false, countingDown = false, silent = 0, slowed = false))
        assertEquals("the Car tab", ObdManager.LIVE_POLL_MS, PollPace.interval(true, false, 0, false))
        assertEquals("counting down to ready", ObdManager.FAST_POLL_MS, PollPace.interval(false, true, 0, false))
        assertEquals("ignition off", ObdManager.QUIET_POLL_MS, PollPace.interval(true, true, 3, false))
    }

    @Test fun aStrugglingAdapterGetsRoom() {
        // 18 Nov: 1366 of 3723 unanswered (37%) - far behind.
        assertTrue(PollPace.fallingBehind(170, 63))
        assertFalse("17 Nov: 0 unanswered", PollPace.fallingBehind(100, 0))
        assertFalse("one miss in a quiet minute isn't a trend", PollPace.fallingBehind(5, 1))
        assertEquals("slowed down wins over the Car tab", 5_000L, PollPace.interval(true, true, 0, slowed = true))
    }
}
