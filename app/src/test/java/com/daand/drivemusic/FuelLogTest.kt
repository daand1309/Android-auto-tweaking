package com.daand.drivemusic

import com.daand.drivemusic.obd.FuelLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelLogTest {
    private val tank = 63.0   // ASX

    @Test fun aJumpBetweenDrivesIsAFillUp() {
        val f = FuelLog.detect(lastEndPct = 32, startPct = 95, tankLitres = tank, price = 2.074, at = 1L)!!
        assertEquals(39.7, f.litres, 0.05)
        assertEquals(82.3, f.cost, 0.1)
        assertNull("sensor wobble on a slope", FuelLog.detect(40, 46, tank, 2.0, 1L))
        assertNull("used fuel, not a fill-up", FuelLog.detect(60, 55, tank, 2.0, 1L))
        assertNull("no previous level", FuelLog.detect(null, 90, tank, 2.0, 1L))
        assertEquals(listOf(f), FuelLog.decode(FuelLog.encode(listOf(f))))
    }

    @Test fun realConsumptionBetweenFillUps() {
        // Filled to 95%, drove 500 km, down to 20% -> 75% of 63 L = 47.25 L over 500 km = 9.45 L/100.
        val a = FuelLog.Fill(1_000, 30, 95, 41.0, 2.07)
        val b = FuelLog.Fill(2_000, 20, 90, 44.1, 2.09)
        val c = FuelLog.consumption(listOf(a, b), tank, { _, _ -> 500.0 })!!
        assertEquals(9.45, c.per100, 0.01)
        assertEquals(0.196, FuelLog.costPerKm(c, 2.07)!!, 0.001)
        assertNull("too few km to say anything", FuelLog.consumption(listOf(a, b), tank, { _, _ -> 40.0 }))
        assertNull("one fill-up isn't an interval", FuelLog.consumption(listOf(a), tank, { _, _ -> 500.0 }))
    }
}
