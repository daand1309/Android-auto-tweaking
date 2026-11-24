package com.daand.drivemusic

import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.Diagnostics
import com.daand.drivemusic.obd.Diagnostics.Step
import com.daand.drivemusic.obd.Diagnostics.Verdict
import com.daand.drivemusic.obd.OnBoardTests
import com.daand.drivemusic.obd.ReadOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)   // org.json for the history
class DiagnosticsTest {
    private fun snap(rpm: Int = 750, coolant: Int = 88, volts: Double = 14.1, speed: Int = 0, extra: Map<Int, Double> = emptyMap(),
                     codes: List<String> = emptyList(), pending: List<String> = emptyList(), mil: Boolean = false,
                     readiness: Pair<Int, Int>? = 5 to 5, at: Long = 1L) =
        CarSnapshot(at = at, coolantC = coolant, rpm = rpm, speedKmh = speed, volts = volts, extra = extra, codes = codes,
            pendingCodes = pending, checkEngine = mil, readiness = readiness)

    private fun List<Diagnostics.Check>.of(id: String) = first { it.id == id }

    @Test fun healthyCarIsAllGreen() {
        val r = Diagnostics.quick(snap(extra = mapOf(0x06 to 1.0, 0x07 to 2.0, 0x31 to 5000.0, 0x30 to 200.0)),
            OnBoardTests.Catalyst(0.4, true), mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 0))
        assertTrue(r.joinToString { "${it.id}=${it.verdict}" }, r.none { it.verdict == Verdict.BAD || it.verdict == Verdict.WARN })
        assertEquals(Verdict.OK, r.of("codes").verdict)
        assertEquals(Verdict.OK, r.of("battery").verdict)
        assertEquals(Verdict.OK, r.of("trim").verdict)
    }

    @Test fun codesAndEngineLight() {
        val r = Diagnostics.quick(snap(codes = listOf("P0420"), mil = true), null, null)
        assertEquals(Verdict.BAD, r.of("codes").verdict)
        assertTrue(r.of("codes").detail.contains("P0420"))
        assertEquals(Verdict.BAD, r.of("mil").verdict)
        assertEquals(Verdict.WARN, Diagnostics.quick(snap(pending = listOf("P0171")), null, null).of("codes").verdict)
    }

    @Test fun recentlyClearedCodesAreFlagged() {
        val r = Diagnostics.quick(snap(extra = mapOf(0x31 to 35.0, 0x30 to 3.0)), null, null)
        assertEquals(Verdict.WARN, r.of("cleared").verdict)
    }

    @Test fun selfTestsNotFinished() {
        assertEquals(Verdict.WARN, Diagnostics.quick(snap(readiness = 3 to 5), null, null).of("ready").verdict)
    }

    @Test fun batteryAtRestAndCharging() {
        assertEquals(Verdict.OK, Diagnostics.battery(12.6, running = false)!!.verdict)
        assertEquals(Verdict.WARN, Diagnostics.battery(12.3, running = false)!!.verdict)
        assertEquals(Verdict.BAD, Diagnostics.battery(11.9, running = false)!!.verdict)
        assertEquals(Verdict.OK, Diagnostics.battery(14.1, running = true)!!.verdict)
        assertEquals(Verdict.WARN, Diagnostics.battery(13.3, running = true)!!.verdict)
        assertEquals(Verdict.BAD, Diagnostics.battery(12.6, running = true)!!.verdict)
        assertEquals(Verdict.BAD, Diagnostics.battery(15.4, running = true)!!.verdict)
    }

    @Test fun fuelTrimOnlyJudgedWarm() {
        val cold = Diagnostics.quick(snap(coolant = 40, extra = mapOf(0x06 to 25.0, 0x07 to 5.0)), null, null)
        assertEquals(Verdict.INFO, cold.of("trim").verdict)
        val lean = Diagnostics.quick(snap(extra = mapOf(0x06 to 8.0, 0x07 to 9.0)), null, null)
        assertEquals(Verdict.WARN, lean.of("trim").verdict)
        assertTrue(lean.of("trim").detail.contains("lean"))
        assertEquals(Verdict.BAD, Diagnostics.trimCheck("t", "T", -24.0, -20.0).verdict)
    }

    @Test fun coldEngineTemperatureSensorsMustAgree() {
        val ok = Diagnostics.quick(CarSnapshot(at = 1, coolantC = 4, rpm = 0, ambientC = 3, extra = mapOf(0x0F to 5.0)), null, null)
        assertEquals(Verdict.OK, ok.of("temps").verdict)
        val off = Diagnostics.quick(CarSnapshot(at = 1, coolantC = 25, rpm = 0, ambientC = 3, extra = mapOf(0x0F to 4.0)), null, null)
        assertEquals(Verdict.WARN, off.of("temps").verdict)
    }

    @Test fun catalystAndMisfires() {
        assertEquals(Verdict.INFO, Diagnostics.catalystCheck(null).verdict)
        assertEquals(Verdict.BAD, Diagnostics.catalystCheck(OnBoardTests.Catalyst(1.3, false)).verdict)
        assertEquals(Verdict.OK, Diagnostics.misfireCheck(mapOf(1 to 2, 2 to 0))!!.verdict)
        assertEquals(Verdict.WARN, Diagnostics.misfireCheck(mapOf(1 to 2, 3 to 14))!!.verdict)
        assertEquals(Verdict.BAD, Diagnostics.misfireCheck(mapOf(2 to 80))!!.verdict)
        assertNull(Diagnostics.misfireCheck(emptyMap()))
    }

    @Test fun misfireCountsComeFromMode06() {
        val results = listOf(
            OnBoardTests.Result(0xA2, 0x0B, 0x24, 3, 0, 0xFFFF), OnBoardTests.Result(0xA2, 0x0C, 0x24, 1, 0, 0xFFFF),
            OnBoardTests.Result(0xA4, 0x0B, 0x24, 17, 0, 0xFFFF), OnBoardTests.Result(0x21, 0x0B, 0x24, 99, 0, 0xFFFF))
        assertEquals(mapOf(1 to 3, 3 to 17), Diagnostics.misfires(results))
    }

    @Test fun theMisfireQuestionsAreReadOnly() {
        for (mid in listOf(0xA2, 0xA3, 0xA4, 0xA5)) assertTrue(ReadOnly.allowed("06%02X".format(mid)))
        assertFalse(ReadOnly.allowed("04"))   // clearing codes stays impossible
    }

    @Test fun fullTestOnAHealthyWarmCar() {
        val t = Diagnostics.FullTest()
        var at = 0L
        fun add(step: Step, s: CarSnapshot) = assertTrue(t.add(step, s.copy(at = ++at)))
        repeat(20) { i ->
            add(Step.IDLE, snap(rpm = 740 + (i % 3) * 10, extra = mapOf(0x06 to 1.0, 0x07 to 2.0, 0x10 to 2.6,
                0x14 to if (i % 2 == 0) 0.1 else 0.8, 0x15 to 0.62 + (i % 2) * 0.05)))
        }
        repeat(10) { add(Step.LOAD, snap(volts = 13.9)) }
        repeat(10) { add(Step.REV, snap(rpm = 2500, extra = mapOf(0x06 to 0.0, 0x07 to 2.0, 0x10 to 10.0))) }
        listOf(750, 3400, 1500, 760, 750).forEach { add(Step.SNAP, snap(rpm = it)) }
        val r = t.evaluate()
        assertTrue(r.joinToString { "${it.id}=${it.verdict}:${it.detail}" }, r.all { it.verdict == Verdict.OK })
        listOf("idle", "trim_idle", "trim_2500", "o2", "maf", "load", "snap").forEach { assertNotNull(it, r.firstOrNull { c -> c.id == it }) }
    }

    @Test fun leanAtIdleOnlyMeansAnAirLeak() {
        assertEquals(Verdict.WARN, Diagnostics.leakCheck(idleTrim = 16.0, revTrim = 2.0)!!.verdict)
        assertNull(Diagnostics.leakCheck(idleTrim = 16.0, revTrim = 14.0))
        assertNull(Diagnostics.leakCheck(idleTrim = 3.0, revTrim = 1.0))
    }

    @Test fun weakAlternatorAndLazySensor() {
        assertEquals(Verdict.BAD, Diagnostics.loadCheck(listOf(12.9, 12.6, 12.5), listOf(14.0))!!.verdict)
        assertEquals(Verdict.WARN, Diagnostics.o2(List(10) { 0.45 + it * 0.01 }, emptyList())!!.verdict)
        // The rear following the front: tired catalytic converter.
        assertEquals(Verdict.WARN, Diagnostics.o2(List(10) { if (it % 2 == 0) 0.1 else 0.85 }, List(10) { if (it % 2 == 0) 0.15 else 0.8 })!!.verdict)
        assertEquals(Verdict.BAD, Diagnostics.idleSteadiness(List(10) { if (it % 2 == 0) 650.0 else 820.0 })!!.verdict)
    }

    @Test fun fullTestStopsWhenTheCarMoves() {
        val t = Diagnostics.FullTest()
        assertTrue(t.add(Step.IDLE, snap()))
        assertFalse(t.add(Step.IDLE, snap(speed = 12)))
        assertNotNull(t.aborted)
    }

    @Test fun coldEngineSkipsTheMixtureVerdicts() {
        val t = Diagnostics.FullTest()
        repeat(10) { t.add(Step.IDLE, snap(coolant = 40, rpm = 1100, extra = mapOf(0x06 to 15.0, 0x07 to 10.0))) }
        val r = t.evaluate()
        assertEquals(Verdict.INFO, r.first { it.id == "warm" }.verdict)
        assertTrue(r.none { it.id == "trim_idle" })
    }

    @Test fun historyKeepsTheLastTenAndSurvivesJunk() {
        val one = Diagnostics.Result(5L, true, listOf(Diagnostics.Check("idle", "Idle", Verdict.WARN, "a little uneven")))
        val many = List(12) { one.copy(at = it.toLong()) }
        val back = Diagnostics.decode(Diagnostics.encode(many))
        assertEquals(Diagnostics.KEEP, back.size)
        assertEquals(11L, back.last().at)
        assertEquals(one.checks, back.last().checks)
        assertTrue(Diagnostics.decode("not json").isEmpty())
        assertTrue(Diagnostics.decode(null).isEmpty())
        assertTrue(one.text("24 Nov").contains("⚠ Idle: a little uneven"))
    }
}
