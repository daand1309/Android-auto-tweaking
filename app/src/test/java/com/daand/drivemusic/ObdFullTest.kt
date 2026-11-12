package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.CarStatusText
import com.daand.drivemusic.obd.Elm327
import com.daand.drivemusic.obd.FuelMath
import com.daand.drivemusic.obd.ObdSession
import com.daand.drivemusic.obd.Pids
import com.daand.drivemusic.obd.TroubleCodes
import com.daand.drivemusic.obd.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdFullTest {
    private fun d(pid: Int, vararg bytes: Int) = Pids.info(pid)!!.decode(bytes.toList())!!

    @Test fun everyStandardValueDecodes() {
        assertEquals(50.2, d(0x04, 128), 0.1)          // load %
        assertEquals(-3.1, d(0x06, 124), 0.1)          // fuel trim %
        assertEquals(15.0, d(0x0E, 158), 0.1)          // timing ° (158/2 - 64)
        assertEquals(12.34, d(0x10, 0x04, 0xD2), 0.01) // MAF g/s
        assertEquals(0.45, d(0x14, 90), 0.001)         // O2 volts
        assertEquals(584.0, d(0x3C, 0x18, 0x60), 0.1)  // catalyst °C (6240/10 - 40)
        assertEquals(1.0, d(0x44, 0x80, 0x00), 0.001)  // lambda
        assertEquals(101.0, d(0x33, 101), 0.1)         // air pressure
        assertEquals(1234.0, d(0x31, 0x04, 0xD2), 0.1) // km since codes cleared
        assertEquals(123456.7, d(0xA6, 0x00, 0x12, 0xD6, 0x87), 0.01)  // odometer
        assertEquals("92 °C", Pids.format(0x05, 92.4))
        assertEquals("-3.1 %", Pids.format(0x06, -3.12))
        // Every entry decodes a sane input without throwing.
        Pids.ALL.forEach { it.decode(listOf(0x40, 0x40, 0x40, 0x40)) }
    }

    @Test fun fuelConsumptionFromAirFlowOrIntakePressure() {
        val fromMaf = FuelMath.litresPerHour(mapOf(0x10 to 10.0), 1.6)!!
        assertEquals(3.29, fromMaf, 0.01)
        assertEquals(6.57, FuelMath.per100km(fromMaf, 50.0)!!, 0.01)
        assertNull(FuelMath.per100km(fromMaf, 0.0))                       // standing still: L/h, not L/100km
        assertEquals(2.5, FuelMath.litresPerHour(mapOf(0x5E to 2.5, 0x10 to 10.0), 1.6)!!, 0.001)  // car's own fuel rate wins
        val est = FuelMath.litresPerHour(mapOf(0x0B to 50.0, 0x0F to 25.0, 0x0C to 2000.0), 1.6)!!  // speed-density estimate
        assertTrue("estimate $est L/h", est in 2.0..6.0)
        assertNull(FuelMath.litresPerHour(mapOf(0x0C to 2000.0), 1.6))
    }

    @Test fun tripAddsUpDistanceFuelAndIdle() {
        val t = Trip()
        var at = 0L
        repeat(13) { t.add(CarSnapshot(at, rpm = 800, speedKmh = 0, litresPerHour = 0.8)); at += 5_000 }        // 1 min idle
        repeat(121) { t.add(CarSnapshot(at, rpm = 2200, speedKmh = 60, litresPerHour = 3.6)); at += 5_000 }     // 10 min at 60
        assertEquals(10.0, t.km, 0.6)
        assertEquals(0.61, t.litres, 0.05)
        assertEquals(6.0, t.avgPer100()!!, 0.6)
        assertTrue(t.idleSeconds >= 60)
        assertTrue(t.summary(false).contains("L/100km"))
        val gap = Trip(); gap.add(CarSnapshot(0, speedKmh = 100)); gap.add(CarSnapshot(60_000, speedKmh = 100))
        assertEquals("a reconnect gap isn't driving", 0.0, gap.km, 0.001)
    }

    @Test fun readinessAndVin() {
        // 3 continuous tests (misfire, fuel, components) done + 5 others available (0xE5), one not done (0x04).
        assertEquals(7 to 8, Elm327.readiness(listOf(0x00, 0x07, 0xE5, 0x04)))
        assertEquals("JMBXTGA2WBE123456", Elm327.vin(listOf("014", "0:4902014A4D42", "1:58544741325742", "2:45313233343536")))
        assertNull(Elm327.vin(listOf("NO DATA")))
    }

    @Test fun faultCodesExplainedWithSeverityAndAdvice() {
        val cat = TroubleCodes.explanation("P0420", false)
        assertEquals("the catalytic converter is underperforming", cat.what)
        assertEquals(TroubleCodes.Severity.CHECK_SOON, cat.severity)
        assertEquals("misfire in cylinder 2", TroubleCodes.explain("P0302", false))
        assertEquals(TroubleCodes.Severity.CAREFUL, TroubleCodes.explanation("P0302", false).severity)
        assertEquals("ignition coil 3 fault", TroubleCodes.explain("P0353", false))
        assertEquals("fuel injector fault, cylinder 4", TroubleCodes.explain("P0204", false))
        // Oxygen sensors by the official numbering: 0136 is the REAR sensor, 0135 the front heater.
        assertEquals("rear oxygen sensor: circuit fault", TroubleCodes.explain("P0136", false))
        assertEquals("front oxygen sensor: heater fault", TroubleCodes.explain("P0135", false))
        assertEquals("rear oxygen sensor (bank 2): slow to respond", TroubleCodes.explain("P0159", false))
        assertEquals("achterste lambdasonde: storing in de verwarming", TroubleCodes.explain("P0141", true))
        // Unknown codes still get their area explained.
        assertTrue(TroubleCodes.explain("P1650", false).startsWith("a Mitsubishi-specific code: a fault in"))
        assertTrue(TroubleCodes.explain("C0035", false).contains("chassis"))
        assertTrue(TroubleCodes.explain("P0899", false).contains("gearbox"))
        assertTrue(TroubleCodes.explain("U1234", false).contains("communication"))
        assertTrue(TroubleCodes.explanation("P0457", false).advice.contains("fuel cap"))
    }

    @Test fun theVoiceGivesTheAdviceForTheWorstCode() {
        val a = CarAlerts.Alert(CarAlerts.Kind.CHECK_ENGINE, true, codes = listOf("P0420", "P0302"))
        val msg = CarAlerts.message(a, dutch = false, butler = true)
        assertTrue(msg, msg.contains("P 0 3 0 2: misfire in cylinder 2"))
        assertTrue(msg, msg.endsWith("Drive gently, avoid hard acceleration, and get it checked soon."))
    }

    @Test fun icyRoadFromTheOutsideSensorOrTheColdIntakeAir() {
        val real = CarAlerts()
        assertEquals(CarAlerts.Kind.ICY_ROAD, real.check(CarSnapshot(0, ambientC = 2, rpm = 900, coolantC = 70)).single { it.kind == CarAlerts.Kind.ICY_ROAD }.kind)
        assertTrue(real.check(CarSnapshot(5_000, ambientC = 1, rpm = 900, coolantC = 70)).none { it.kind == CarAlerts.Kind.ICY_ROAD })   // once
        val proxy = CarAlerts()  // no outside sensor: cold engine, intake air in the first 90 s
        assertTrue(proxy.check(CarSnapshot(0, coolantC = 5, rpm = 1000, runtimeS = 20, extra = mapOf(0x0F to 0.0))).any { it.kind == CarAlerts.Kind.ICY_ROAD })
        val warmEngine = CarAlerts()  // intake air of a warm engine says nothing about outside
        assertTrue(warmEngine.check(CarSnapshot(0, coolantC = 80, rpm = 1000, runtimeS = 20, extra = mapOf(0x0F to 2.0))).none { it.kind == CarAlerts.Kind.ICY_ROAD })
        assertTrue(CarAlerts.message(CarAlerts.Alert(CarAlerts.Kind.ICY_ROAD, false, 1.0), false, true).contains("black ice"))
    }

    @Test fun fullReadOfARicherAsxWithPendingPermanentAndVin() {
        val fake = FakeElm(FakeElm.ASX + mapOf(
            "0902" to "014\r0:4902014A4D42\r1:58544741325742\r2:45313233343536\r\r",
            "07" to "47010141\r\r",          // pending: rear O2 heater
            "0A" to "4A010420\r\r",          // permanent: catalyst
            "0110" to "41100190\r\r",        // MAF 4.0 g/s
            "0104" to "410440\r\r",
            "0133" to "413365\r\r",
        ))
        val session = ObdSession(fake, 1.6)
        assertNull(session.start())
        assertEquals("JMBXTGA2WBE123456", session.vin)
        val snap = session.read(null, full = true, readCodes = true)
        assertEquals(listOf("P0141"), snap.pendingCodes)
        assertEquals(listOf("P0420"), snap.permanentCodes)
        assertEquals(4.0, snap.extra[0x10]!!, 0.01)
        assertEquals(101.0, snap.extra[0x33]!!, 0.1)
        assertTrue(snap.litresPerHour!! > 1.0)
        val report = CarStatusText.report(snap, dutch = false)
        assertTrue(report, report.contains("Engine\n") && report.contains("Coolant temperature: 83 °C"))
        assertTrue(report, report.contains("P0420: the catalytic converter is underperforming"))
        assertTrue(report, report.contains("Pending codes - noticed, not confirmed yet"))
        assertTrue(report, report.contains("P0141: rear oxygen sensor: heater fault"))
        assertTrue(report, report.contains("→ Safe to keep driving; have it checked soon."))
    }
}
