package com.daand.drivemusic

import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CarDashTest {
    @Test fun warningColourMatchesHowUrgentItIs() {
        assertFalse("get it checked = amber", CarDash.warningIsSevere(CarSnapshot(0, codes = listOf("P0420"))))
        assertTrue("misfires = red", CarDash.warningIsSevere(CarSnapshot(0, codes = listOf("P0420", "P0300"))))
        assertTrue("overheating = red", CarDash.warningIsSevere(CarSnapshot(0, coolantC = 114)))
        assertFalse("garbage temperature isn't a warning", CarDash.warningIsSevere(CarSnapshot(0, coolantC = 215)))
        assertFalse(CarDash.warningIsSevere(null))
    }

    @Test fun bigNumbersWithColours() {
        val s = CarSnapshot(0, coolantC = 45, rpm = 2100, speedKmh = 82, volts = 14.2, ambientC = 2, litresPerHour = 5.0,
            extra = mapOf(0x11 to 18.0, 0x04 to 37.4, 0x0F to 9.0))
        val v = CarDash.values(s, null, dutch = false)
        assertEquals("82" to null, v["speed"])
        assertEquals("2100" to null, v["rpm"])
        assertEquals("45 °C" to CarDash.COLD, v["coolant"])
        assertEquals("6.1 L/100", v["fuel"]!!.first)
        assertEquals("14.2 V" to null, v["battery"])
        assertEquals("18% · 37%", v["load"]!!.first)
        assertEquals("2° · 9°" to CarDash.COLD, v["temps"])    // icy: shown cold
        assertEquals(CarDash.GOOD, CarDash.values(s.copy(coolantC = 90), null, false)["coolant"]!!.second)
        assertEquals(CarDash.HOT, CarDash.values(s.copy(coolantC = 108), null, false)["coolant"]!!.second)
        // Standing still: fuel in litres per hour.
        assertEquals("0.8 L/h", CarDash.values(s.copy(speedKmh = 0, litresPerHour = 0.8), null, false)["fuel"]!!.first)
        assertTrue(CarDash.values(null, null, false).isEmpty())
    }

    @Test fun warningBar() {
        assertNull(CarDash.warning(CarSnapshot(0, coolantC = 90, checkEngine = false), false))
        assertTrue(CarDash.warning(CarSnapshot(0, coolantC = 112), false)!!.contains("Engine hot: 112"))
        assertEquals("⚠  P0420: the catalytic converter is underperforming (+1)",
            CarDash.warning(CarSnapshot(0, codes = listOf("P0420", "P0171"), checkEngine = true), false))
        assertNull("a garbled 215 °C is not a hot engine", CarDash.warning(CarSnapshot(0, coolantC = 215), false))
    }
}
