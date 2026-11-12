package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveHoldTest {
    private val hold = DriveHold.Decision.Hold

    @Test fun withObdTheIgnitionDecides() {
        // Stall: radio rebooting, but the car's computer still answers -> keep the drive.
        assertEquals(hold, DriveHold.decide(sinceDropMs = 30_000, obdConnected = true, ecuSilentMs = 2_000))
        assertEquals(hold, DriveHold.decide(sinceDropMs = 4 * 60_000, obdConnected = true, ecuSilentMs = 0))
        // Key out: the car's computer goes quiet -> you left, within ~15 s.
        val off = DriveHold.decide(sinceDropMs = 16_000, obdConnected = true, ecuSilentMs = 15_000)
        assertTrue(off is DriveHold.Decision.End && off.reason.contains("ignition"))
        // Ignition on but Android Auto never returns -> give up after 5 minutes.
        assertTrue(DriveHold.decide(5 * 60_000, true, 0) is DriveHold.Decision.End)
    }

    @Test fun withoutObdItWaitsAFixedTime() {
        DriveHold.holdWithoutObdMs = 90_000
        assertEquals(hold, DriveHold.decide(60_000, obdConnected = false, ecuSilentMs = Long.MAX_VALUE))
        assertTrue(DriveHold.decide(90_000, obdConnected = false, ecuSilentMs = Long.MAX_VALUE) is DriveHold.Decision.End)
    }
}
