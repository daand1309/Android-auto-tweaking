package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.Range
import com.daand.drivemusic.obd.Trip
import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripNotice
import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)  // org.json
class ObdFeaturesTest {
    private fun drive(t: Trip, seconds: Int, speedOf: (Int) -> Int, rpm: Int = 2200, startAt: Long = 0L): Long {
        var at = startAt
        for (i in 0 until seconds / 5) { t.add(CarSnapshot(at, rpm = rpm, speedKmh = speedOf(i), litresPerHour = 4.0)); at += 5_000 }
        return at
    }

    @Test fun drivingScoreRewardsSmoothDriving() {
        val smooth = Trip(); drive(smooth, 20 * 60, { 70 })
        assertTrue("smooth ${smooth.score()}", smooth.score()!! >= 90)
        val sporty = Trip(); drive(sporty, 20 * 60, { i -> if (i % 4 < 2) 20 else 85 }, rpm = 4200)   // hard on/off throttle, high revs
        assertTrue("sporty ${sporty.score()}", sporty.score()!! < 50)
        assertNull("too short to judge", Trip().also { drive(it, 60, { 50 }) }.score())
        assertEquals("smooth", Trip.scoreWord(90, false)); assertEquals("sportief", Trip.scoreWord(40, true))
    }

    @Test fun hardAccelerationCountsOncePerPushAndIgnoresJitter() {
        // One long hard push (0 -> 100 in ~8 s, read every 1.5 s) is one event, not five.
        val t = Trip(); var at = 0L
        for (v in listOf(0, 0, 19, 38, 56, 75, 94, 100, 100)) { t.add(CarSnapshot(at, rpm = 3000, speedKmh = v)); at += 1_500 }
        assertEquals(1, t.hardAccelerations)
        // Readings 0.2 s apart with a 2 km/h step (coarse speed steps) aren't a hard acceleration.
        val j = Trip(); at = 0L
        for (v in listOf(50, 52, 50, 52, 50)) { j.add(CarSnapshot(at, rpm = 2000, speedKmh = v)); at += 200 }
        assertEquals(0, j.hardAccelerations); assertEquals(0, j.hardBrakes)
        // Two separate hard stops are two events.
        val b = Trip(); at = 0L
        for (v in listOf(80, 50, 20, 20, 20, 80, 80, 50, 20)) { b.add(CarSnapshot(at, rpm = 2000, speedKmh = v)); at += 2_000 }
        assertEquals(2, b.hardBrakes)
    }

    @Test fun restingBatteryIgnoresTheStarterDip() {
        var max: Double? = null
        max = com.daand.drivemusic.obd.RestingBattery.track(max, CarSnapshot(0, rpm = 0, volts = 12.55))
        max = com.daand.drivemusic.obd.RestingBattery.track(max, CarSnapshot(1, rpm = 250, volts = 9.8))   // cranking
        max = com.daand.drivemusic.obd.RestingBattery.track(max, CarSnapshot(2, rpm = 0, volts = 10.4))    // dip read with rpm 0
        max = com.daand.drivemusic.obd.RestingBattery.track(max, CarSnapshot(3, rpm = 800, volts = 14.2))  // running: ignored
        assertEquals(12.55, com.daand.drivemusic.obd.RestingBattery.value(max)!!, 0.001)
        assertNull("adapter connected after the start", com.daand.drivemusic.obd.RestingBattery.value(null))
        assertNull("garbage", com.daand.drivemusic.obd.RestingBattery.value(3.1))
    }

    @Test fun tripHistoryJsonTotalsAndTypicalConsumption() {
        val trips = listOf(
            TripRecord(1_000, 20, 18.4, 1.16, 86, 92, listOf("P0420")),
            TripRecord(2_000, 35, 40.0, 2.4, null, null),
            TripRecord(3_000, 3, 0.8, 0.0, null, 40),
        )
        val back = TripHistory.fromJson(TripHistory.toJson(trips))
        assertEquals(trips, back)
        assertTrue(TripHistory.fromJson("not json").isEmpty())              // a damaged file can't crash the app
        val all = TripHistory.totals(trips)
        assertEquals(3, all.trips); assertEquals(59.2, all.km, 0.01); assertEquals(3.56, all.litres, 0.001)
        assertEquals(2, TripHistory.totals(trips, since = 2_000).trips)
        assertEquals(6.0, TripHistory.typicalPer100(trips)!!, 0.2)
        assertEquals(40.8, TripHistory.kmSince(trips, 2_000), 0.01)
    }

    @Test fun rangeLeft() {
        assertEquals(380, Range.km(40, 60.0, 6.3))              // 24 L at 6.3 L/100
        assertNull(Range.km(null, 60.0, 6.3))
        assertNull(Range.km(40, 60.0, null))
        assertNull("nonsense consumption", Range.km(40, 60.0, 0.5))
    }

    @Test fun batteryTrend() {
        val healthy = (1L..10L).map { it to 12.6 }
        assertFalse(CarAlerts.batteryTrendWeak(healthy))
        val declining = (1L..5L).map { it to 12.65 } + (6L..10L).map { it to 12.3 }
        assertTrue("0.35 V lower than before", CarAlerts.batteryTrendWeak(declining))
        assertTrue("low", CarAlerts.batteryTrendWeak((1L..5L).map { it to 12.1 }))
        assertFalse("not enough days yet", CarAlerts.batteryTrendWeak((1L..3L).map { it to 11.9 }))
    }

    @Test fun serviceSyncedWithTheCarsDashboard() {
        val day = 86_400_000L
        // Dashboard said 3,200 km / 60 days on day 100.
        assertEquals(1_000 to 40, CarAlerts.serviceLeft(100 * day, 3_200, 60, 2_200.0, 120 * day))
        fun status(kmSinceSync: Double, now: Long, serviceDate: Long = 0) =
            CarAlerts.serviceStatus(serviceDate, 0.0, 15_000, 12, 100 * day, 3_200, 60, kmSinceSync, now)
        assertNull(status(2_000.0, 130 * day))
        assertEquals("km", status(3_250.0, 130 * day))
        assertEquals("time", status(500.0, 161 * day))
        // A last-service date set after the sync takes over again.
        assertNull(status(9_999.0, 200 * day, serviceDate = 150 * day))
        assertEquals(45, CarAlerts.parseDays("45 days")); assertEquals(60, CarAlerts.parseDays("2 months"))
        assertEquals(60, CarAlerts.parseDays("2 maanden")); assertEquals(14, CarAlerts.parseDays("2 weeks"))
        assertEquals(30, CarAlerts.parseDays("30")); assertNull(CarAlerts.parseDays("soon"))
    }

    @Test fun serviceDue() {
        val day = 86_400_000L
        assertNull(CarAlerts.serviceDue(0, 99_999.0, 15_000, 12, 400 * day))      // not set
        assertNull(CarAlerts.serviceDue(100 * day, 8_000.0, 15_000, 12, 200 * day))
        assertEquals("km", CarAlerts.serviceDue(100 * day, 15_200.0, 15_000, 12, 200 * day))
        assertEquals("time", CarAlerts.serviceDue(100 * day, 3_000.0, 15_000, 12, 470 * day))
        assertTrue(GreetingText.serviceReminder("km", false, true).startsWith("A small reminder, sir"))
    }

    @Test fun thermostatStuckOpen() {
        val a = CarAlerts()
        var at = 0L
        var said = emptyList<CarAlerts.Alert>()
        repeat(16 * 12) { said = said + a.check(CarSnapshot(at, coolantC = 58, rpm = 2200, speedKmh = 80, ambientC = 8)); at += 5_000 }
        assertEquals(1, said.count { it.kind == CarAlerts.Kind.THERMOSTAT })
        assertTrue(CarAlerts.message(said.first { it.kind == CarAlerts.Kind.THERMOSTAT }, false, true).contains("thermostat"))
        // A healthy engine warms up: nothing said. And in a deep freeze it can stay cool on its own.
        val ok = CarAlerts(); at = 0
        repeat(16 * 12) { assertTrue(ok.check(CarSnapshot(at, coolantC = 88, rpm = 2200, speedKmh = 80)).none { it.kind == CarAlerts.Kind.THERMOSTAT }); at += 5_000 }
        val frozen = CarAlerts(); at = 0
        repeat(16 * 12) { assertTrue(frozen.check(CarSnapshot(at, coolantC = 60, rpm = 2200, speedKmh = 80, ambientC = -15)).none { it.kind == CarAlerts.Kind.THERMOSTAT }); at += 5_000 }
    }

    @Test fun lowFuelSaysTheRangeAndTheTripNotice() {
        val msg = CarAlerts.message(CarAlerts.Alert(CarAlerts.Kind.LOW_FUEL, false, 9.0, codes = listOf("65")), false, true)
        assertTrue(msg, msg.endsWith("Enough for roughly 65 kilometres."))
        assertEquals("18.4 km · 6.3 L/100 · €2.38 · 20 min · smooth 86/100",
            TripNotice.text(TripRecord(0, 20, 18.4, 1.16, 86, 92), 2.05, dutch = false))
        assertEquals("0.8 km · 3 min", TripNotice.text(TripRecord(0, 3, 0.8, 0.0, null, null), 0.0, dutch = false))
    }
}
