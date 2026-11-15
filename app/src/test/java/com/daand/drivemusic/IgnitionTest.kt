package com.daand.drivemusic

import com.daand.drivemusic.obd.Ignition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "is the car switched off?" decision: the adapter talks, but the car's computer has gone quiet. */
class IgnitionTest {

    @Test fun quietCarBehindAWorkingAdapterIsSwitchedOff() {
        assertTrue("a minute of silence while the adapter still answers", Ignition.off(adapterConnected = true, Ignition.OFF_AFTER_MS))
        assertTrue("never heard from the car at all (ignition was off all along)", Ignition.off(true, Long.MAX_VALUE))
    }

    @Test fun notWhileTheCarIsStillAnswering() {
        assertFalse("the car is answering", Ignition.off(adapterConnected = true, ecuSilentMs = 0L))
        assertFalse("a slow read is not a switched-off car", Ignition.off(true, Ignition.OFF_AFTER_MS - 1))
    }

    @Test fun notWhenTheAdapterIsGoneItself() {
        // No adapter = no opinion: an unreachable adapter must never end a drive.
        assertFalse(Ignition.off(adapterConnected = false, ecuSilentMs = 10 * 60_000L))
        assertFalse(Ignition.off(adapterConnected = false, ecuSilentMs = Long.MAX_VALUE))
    }

    @Test fun saysHowLongTheCarHasBeenOff() {
        assertEquals("Car switched off · 3 min", Ignition.text(3 * 60_000L, dutch = false))
        assertEquals("Auto uit · al 3 min", Ignition.text(3 * 60_000L, dutch = true))
        // Just under the threshold still reads as a minute, never as "0 min".
        assertEquals("Car switched off · 1 min", Ignition.text(61_000L, dutch = false))
    }
}
