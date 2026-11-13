package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.CarTrends
import com.daand.drivemusic.obd.CarTrends.Kind
import com.daand.drivemusic.obd.HealthSample
import com.daand.drivemusic.obd.HealthStats
import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)  // org.json
class HealthTrendTest {
    /** A cold start, 10 min warming up, idling at lights, then cruising. */
    private fun drive(ltft: Double = 2.0, chargeV: Double = 14.2, idleJitter: Int = 10, warmupSteps: Int = 120, roadC: Int = 90): HealthSample {
        val h = HealthStats()
        var at = 0L
        fun add(s: CarSnapshot) { h.add(s, 5.0); at += 5_000 }
        for (i in 0 until warmupSteps) add(CarSnapshot(at, coolantC = 20 + 59 * i / warmupSteps, rpm = 1800, speedKmh = 40, volts = chargeV, ambientC = 8))
        for (i in 0 until 20) add(CarSnapshot(at, coolantC = 88, rpm = 750 + if (i % 2 == 0) idleJitter else -idleJitter, speedKmh = 0, volts = 14.0,
            extra = mapOf(0x07 to ltft)))
        for (i in 0 until 60) add(CarSnapshot(at, coolantC = roadC, rpm = 2400, speedKmh = 100, volts = chargeV, extra = mapOf(0x07 to ltft)))
        return h.sample()
    }

    @Test fun collectsComparableNumbersDuringADrive() {
        val s = drive(ltft = 3.1, chargeV = 14.15, idleJitter = 12, roadC = 91)
        assertEquals(3.1, s.ltft!!, 0.01)
        assertEquals(14.15, s.chargeV!!, 0.01)
        assertEquals(750.0, s.idleRpm!!, 1.0)
        assertEquals(12.0, s.idleSpread!!, 0.5)
        assertEquals(10.0, s.warmupMin!!, 0.2)     // 120 readings x 5 s
        assertEquals(91.0, s.cruiseCoolant!!, 0.01)
        assertEquals(8, s.ambient)
        // Engine already warm at the start: no warm-up time (it would be meaningless).
        val warm = HealthStats().apply { add(CarSnapshot(0, coolantC = 85, rpm = 800, speedKmh = 0), 0.0) }.sample()
        assertNull(warm.warmupMin)
    }

    @Test fun stableCarHasNoFindings() {
        val history = List(20) { drive() }
        assertTrue(CarTrends.findings(history).isEmpty())
        assertTrue(CarTrends.findings(List(2) { drive(ltft = 20.0) }).isEmpty())   // too few drives to judge
    }

    @Test fun spotsEachSlowChangeBeforeACodeWould() {
        fun kinds(h: List<HealthSample>) = CarTrends.findings(h).map { it.kind }
        val normal = List(10) { drive() }
        assertEquals(listOf(Kind.LEAN), kinds(normal + List(4) { drive(ltft = 11.0) }))
        assertEquals("a big shift from YOUR normal counts too", listOf(Kind.LEAN), kinds(normal + List(4) { drive(ltft = 9.5) }))
        assertEquals(listOf(Kind.RICH), kinds(normal + List(4) { drive(ltft = -12.0) }))
        assertEquals(listOf(Kind.CHARGING), kinds(normal + List(4) { drive(chargeV = 13.6) }))
        assertEquals(listOf(Kind.ROUGH_IDLE), kinds(normal + List(4) { drive(idleJitter = 45) }))
        assertEquals(listOf(Kind.SLOW_WARMUP), kinds(normal + List(4) { drive(warmupSteps = 200) }))
        assertEquals(listOf(Kind.RUNNING_HOTTER), kinds(normal + List(4) { drive(roadC = 99) }))
        // One odd drive isn't a trend.
        assertTrue(kinds(normal + drive(ltft = 15.0) + List(3) { drive() }).isEmpty())
    }

    @Test fun slowerWarmUpInMuchColderWeatherIsNotAFault() {
        val summer = List(10) { drive().copy(ambient = 18) }
        val winter = List(4) { drive(warmupSteps = 200).copy(ambient = 2) }
        assertTrue(CarTrends.findings(summer + winter).isEmpty())
    }

    @Test fun messagesAndOverview() {
        val normal = List(10) { drive() }
        for (k in Kind.entries) for (dutch in listOf(false, true)) for (butler in listOf(false, true)) {
            val msg = CarTrends.message(CarTrends.Finding(k, 12.0, 3.0), dutch, butler)
            assertTrue("$k $dutch", msg.length > 40 && !msg.contains("null"))
        }
        val rows = CarTrends.overview(normal, dutch = false).toMap()
        assertEquals("+2% · usually +2%", rows["Fuel trim (long-term)"])
        assertEquals("14.2 V · usually 14.2 V", rows["Charging voltage"])
        assertTrue(CarTrends.overview(emptyList(), false).isEmpty())
    }

    @Test fun healthIsStoredWithTheTripAndOldFilesStillLoad() {
        val rec = TripRecord(1L, 20, 15.0, 1.0, 80, 90, health = drive())
        val back = TripHistory.fromJson(TripHistory.toJson(listOf(rec))).single()
        assertEquals(rec.health, back.health)
        val old = TripHistory.fromJson("""[{"at":1,"min":20,"km":15,"l":1,"score":80,"maxC":90,"codes":""}]""").single()
        assertNull(old.health)
        val garbage = TripHistory.fromJson("""[{"at":1,"km":15,"h":{"ltft":"x","v":99}}]""").single()
        assertNotNull(garbage.health); assertNull(garbage.health!!.ltft); assertNull(garbage.health!!.chargeV)
    }

    @Test fun pendingCodeIsAnEarlyWarningToldOnce() {
        val a = CarAlerts(readySeconds = 0)
        val first = a.check(CarSnapshot(0, rpm = 800, pendingCodes = listOf("P0301")))
        val early = first.filter { it.kind == CarAlerts.Kind.EARLY_CODE }
        assertEquals(listOf("P0301"), early.single().codes)
        assertTrue(CarAlerts.message(early.single(), false, true).contains("P"))
        assertTrue(a.check(CarSnapshot(5_000, rpm = 800, pendingCodes = listOf("P0301"))).none { it.kind == CarAlerts.Kind.EARLY_CODE })
        // Once confirmed it's a real check-engine alert (a different, urgent message).
        val confirmed = a.check(CarSnapshot(10_000, rpm = 800, checkEngine = true, codes = listOf("P0301"), pendingCodes = listOf("P0301")))
        assertEquals(listOf(CarAlerts.Kind.CHECK_ENGINE), confirmed.map { it.kind }.filter { it == CarAlerts.Kind.CHECK_ENGINE || it == CarAlerts.Kind.EARLY_CODE })
        // Told on an earlier drive: not again.
        val next = CarAlerts(0, setOf(CarAlerts.pendingKey("P0301")))
        assertTrue(next.check(CarSnapshot(0, rpm = 800, pendingCodes = listOf("P0301"))).none { it.kind == CarAlerts.Kind.EARLY_CODE })
    }
}
