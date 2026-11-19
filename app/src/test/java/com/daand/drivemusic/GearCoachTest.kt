package com.daand.drivemusic

import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.GearCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ASX 1.6 five-speed: about 8.7, 15.9, 23.3, 30.3 and 37.9 km/h per 1000 rpm. */
class GearCoachTest {
    private val asx = listOf(8.7, 15.9, 23.3, 30.3, 37.9)
    private var t = 0L

    /** [secs] of steady driving at [kmh] in gear [g] (1-based), a reading every 3.5 s. */
    private fun GearCoach.cruise(g: Int, kmh: Int, secs: Int, warm: Boolean = true): List<GearCoach.Hint> {
        val rpm = (kmh / asx[g - 1] * 1000).toInt()
        return (0 until secs * 2 / 7).mapNotNull { t += 3_500; add(CarSnapshot(t, speedKmh = kmh, rpm = rpm, coolantC = 90), warm) }
    }

    private fun learned(): GearCoach = GearCoach().apply {
        repeat(6) { cruise(2, 25, 60); cruise(3, 45, 120); cruise(4, 60, 120); cruise(5, 100, 300) }
    }

    @Test fun learnsTheGearsFromSteadyDriving() {
        val g = GearCoach(learned().save()).gears()   // survives being saved between drives
        assertEquals(4, g.size)   // 1st is never held steady long enough - fine, it isn't needed
        g.zip(asx.drop(1)).forEach { (got, want) -> assertEquals(want, got, 0.8) }
    }

    @Test fun cruisingHighInFourthGetsOneHint() {
        val c = learned()
        val hints = c.cruise(4, 95, 60)          // 4th at 95 km/h = ~3,140 rpm; 5th would be ~2,500
        assertEquals(1, hints.size)
        assertTrue("rpm ${hints[0].rpm}", hints[0].rpm > 3_000)
    }

    @Test fun nothingInTopGearWhenColdWhenLuggingOrTooOften() {
        val c = learned()
        assertTrue("top gear", c.cruise(5, 125, 60).isEmpty())
        assertTrue("cold engine", c.cruise(4, 95, 60, warm = false).isEmpty())
        assertTrue("2nd at 30: 3rd would lug", c.cruise(2, 30, 60).isEmpty())
        c.cruise(4, 95, 60)
        assertTrue("4 minutes apart", c.cruise(3, 75, 60).isEmpty())
    }

    @Test fun acceleratingDoesntCount() {
        val c = learned()
        // Pulling hard through 3rd: speed rises every reading - never steady, never a hint (MIVEC style).
        val hints = (40..100 step 5).mapNotNull { v -> t += 3_500; c.add(CarSnapshot(t, speedKmh = v, rpm = (v / 23.3 * 1000).toInt(), coolantC = 90), true) }
        assertTrue(hints.isEmpty())
    }

    @Test fun withoutClearGearsItStaysQuiet() {
        // An automatic / CVT: every speed at its own ratio - no peaks, no hints.
        val c = GearCoach()
        for (v in 20..130) { t += 3_500; c.add(CarSnapshot(t, speedKmh = v, rpm = 3_000, coolantC = 90), true); t += 3_500; assertNull(c.add(CarSnapshot(t, speedKmh = v, rpm = 3_000, coolantC = 90), true)) }
        assertTrue(c.gears().size < 3)
    }
}
