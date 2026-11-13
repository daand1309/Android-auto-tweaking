package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.CarStatusText
import com.daand.drivemusic.obd.Elm327
import com.daand.drivemusic.obd.ObdLink
import com.daand.drivemusic.obd.ObdSession
import com.daand.drivemusic.obd.StreamLink
import com.daand.drivemusic.obd.TroubleCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake ELM327 that answers like a Vgate adapter plugged into a CAN car (like the ASX). */
class FakeElm(private val answers: Map<String, String>) : ObdLink {
    val sent = mutableListOf<String>()
    override fun send(cmd: String, timeoutMs: Long): String {
        sent += cmd
        return answers[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
    }
    override fun close() {}

    companion object {
        /** Engine running, check-engine light on with 3 codes (multi-frame CAN answer). */
        val ASX = mapOf(
            "ATZ" to "\r\rELM327 v2.3\r\r",
            "ATE0" to "ATE0\rOK\r\r",
            "0100" to "SEARCHING...\r4100BE3FA813\r\r",
            "0120" to "41208003A001\r\r",
            "0140" to "414044000000\r\r",
            "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
            "0105" to "41057B\r\r",          // 83 °C
            "010C" to "410C1AF8\r\r",        // 1726 rpm
            "010D" to "410D32\r\r",          // 50 km/h
            "012F" to "412F80\r\r",          // 50 %
            "0142" to "414236B0\r\r",        // 14.0 V
            "0146" to "41461E\r\r",          // -10 °C outside
            "0101" to "41018307E500\r\r",    // light ON, 3 codes
            "03" to "00A\r0:430301330420\r1:01710000000000\r\r",
        )
    }
}

class ObdTest {
    // ------------------------------------------------------------------ protocol parsing

    @Test fun cleansAdapterOutput() {
        assertEquals(listOf("4100BE3FA813"), Elm327.lines("SEARCHING...\r4100BE3FA813\r\r>"))
        assertTrue(Elm327.isError(Elm327.lines("SEARCHING...\rUNABLE TO CONNECT\r\r")))
        assertTrue(Elm327.isError(Elm327.lines("NO DATA\r")))
        assertTrue(Elm327.isError(Elm327.lines("?\r")))
        assertFalse(Elm327.isError(Elm327.lines("410C1AF8")))
    }

    @Test fun readsBytesWithOrWithoutSpacesAndFrameNumbers() {
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), Elm327.bytes("41 0C 1A F8"))
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), Elm327.bytes("410C1AF8"))
        assertEquals(listOf(0x43, 0x03), Elm327.bytes("0:4303"))
        assertTrue(Elm327.bytes("00A").isEmpty())         // CAN length header, not data
        assertTrue(Elm327.bytes("ELM327 v2.3").isEmpty())
    }

    @Test fun decodesStandardValues() {
        assertEquals(83, Elm327.coolantC(listOf(0x7B)))
        assertEquals(1726, Elm327.rpm(listOf(0x1A, 0xF8)))
        assertEquals(50, Elm327.speedKmh(listOf(0x32)))
        assertEquals(50, Elm327.fuelPct(listOf(0x80)))
        assertEquals(14.0, Elm327.moduleVolts(listOf(0x36, 0xB0))!!, 0.001)
        assertEquals(true to 3, Elm327.status(listOf(0x83, 0x07)))
        assertEquals(12.4, Elm327.atrvVolts(listOf("12.4V"))!!, 0.001)
        assertNull(Elm327.atrvVolts(listOf("?")))
        // Several control units answering: the engine's answer is used.
        assertEquals(listOf(0x7B), Elm327.pidData(listOf("NO DATA", "41057B", "410580"), 0x05))
    }

    @Test fun supportedValuesBitmask() {
        val s = Elm327.supported(listOf(0xBE, 0x3F, 0xA8, 0x13), 0x00)
        assertTrue(s.containsAll(listOf(0x01, 0x05, 0x0C, 0x0D, 0x20)))
        assertFalse(0x02 in s)
    }

    @Test fun troubleCodesInEveryFormat() {
        assertEquals(listOf("P0133", "P0420", "P0171"), Elm327.dtcs(listOf("00A", "0:430301330420", "1:01710000000000")))  // CAN multi-frame
        assertEquals(listOf("P0420"), Elm327.dtcs(listOf("43010420")))                       // CAN single frame
        assertEquals(listOf("P0420"), Elm327.dtcs(listOf("00A", "0:43010420000000", "1:00000000000000")))  // padded: count wins
        Elm327.dtcs(listOf("430133042000000"))                                               // odd-length junk: no crash
        assertEquals(listOf("P0133", "P0420"), Elm327.dtcs(listOf("43013304200000")))         // older protocols: 3 codes per line, no count byte
        assertEquals(listOf("P0420", "U0100"), Elm327.dtcs(listOf("43010420", "4301C100")))   // two control units
        assertTrue(Elm327.dtcs(listOf("4300")).isEmpty())
        assertEquals("C0300", Elm327.code(0x43, 0x00))
        assertEquals("B1234", Elm327.code(0x92, 0x34))
    }

    // ------------------------------------------------------------------ a whole session against the fake adapter

    @Test fun fullSessionWithTheFakeAsx() {
        val fake = FakeElm(FakeElm.ASX)
        val session = ObdSession(fake)
        assertNull(session.start())
        assertEquals("ISO 15765-4 (CAN 11/500)", session.protocol)
        assertTrue(session.supported.containsAll(listOf(0x05, 0x0C, 0x0D, 0x2F, 0x42, 0x46)))
        val snap = session.read(null, full = true, readCodes = true)
        assertEquals(83, snap.coolantC); assertEquals(1726, snap.rpm); assertEquals(50, snap.speedKmh)
        assertEquals(50, snap.fuelPct); assertEquals(14.0, snap.volts!!, 0.001); assertEquals(-10, snap.ambientC)
        assertEquals(true, snap.checkEngine)
        assertEquals(listOf("P0133", "P0420", "P0171"), snap.codes)
        assertTrue(snap.engineRunning)
        // Quick reads only ask for the fast values.
        fake.sent.clear()
        val quick = session.read(snap, full = false, readCodes = false)
        // Only the fast values this car supports (+ engine run time while it's young) - nothing else.
        assertEquals(listOf("0104", "0105", "010C", "010D", "0111", "010B", "010F", "0110", "011F"), fake.sent)
        assertEquals(snap.codes, quick.codes)
    }

    @Test fun ignitionOffGivesAClearReason() {
        val off = FakeElm(mapOf("0100" to "SEARCHING...\rUNABLE TO CONNECT\r\r"))
        assertTrue(ObdSession(off).start()!!.contains("ignition"))
    }

    @Test fun unsupportedValuesAreNotAskedAndVoltageFallsBackToTheAdapter() {
        val minimal = FakeElm(mapOf("0100" to "4100BE3E0000\r", "0105" to "41055A\r", "ATRV" to "12.3V\r"))
        val session = ObdSession(minimal)
        assertNull(session.start())
        val snap = session.read(null, full = true, readCodes = false)
        assertEquals(50, snap.coolantC)
        assertEquals(12.3, snap.volts!!, 0.001)    // no PID 42 -> adapter's own measurement
        assertNull(snap.fuelPct)
        assertFalse("never asks for unsupported values", minimal.sent.contains("012F"))
    }

    /** A serial line where the "adapter" answers only after a command arrives - like the real thing. */
    private class FakeSerial(private val answer: (String) -> String) {
        private val pending = java.util.concurrent.LinkedBlockingQueue<Int>()
        val written = StringBuilder()
        val input = object : java.io.InputStream() {
            override fun read(): Int = pending.poll() ?: -1
            override fun available(): Int = pending.size
        }
        val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                written.append(b.toChar())
                if (b.toChar() == '\r') answer(written.toString().trimEnd().substringAfterLast('\r')).forEach { pending.add(it.code) }
            }
        }
    }

    @Test fun streamLinkReadsUpToThePromptAndDropsLeftovers() {
        val serial = FakeSerial { cmd -> if (cmd == "010C") "410C1AF8\r\r>" else "OK\r\r>" }
        val link = StreamLink(serial.input, serial.output)
        assertEquals("410C1AF8\r\r", link.send("010C", 500))
        assertEquals("OK\r\r", link.send("ATE0", 500))
        assertTrue(serial.written.toString().startsWith("010C\r"))
        val silent = FakeSerial { "" }
        try { StreamLink(silent.input, silent.output).send("0105", 200); org.junit.Assert.fail("should time out") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("didn't answer")) }
    }

    // ------------------------------------------------------------------ what the voice says, and when

    /** These older tests are about the other alerts; "ready to drive" is tested separately. */
    private fun CarAlerts.checkOthers(s: CarSnapshot) = check(s).filter { it.kind != CarAlerts.Kind.READY_TO_DRIVE }

    @Test fun alertsComeOncePerTopicAndOnlyWhenTheyMatter() {
        val a = CarAlerts()
        val t0 = 1_000_000L
        assertTrue(a.checkOthers(CarSnapshot(t0, coolantC = 20, rpm = 800, volts = 14.2)).isEmpty())       // cold start
        val warm = a.checkOthers(CarSnapshot(t0 + 60_000, coolantC = 80, rpm = 2000, volts = 14.2))
        assertEquals(listOf(CarAlerts.Kind.ENGINE_WARM), warm.map { it.kind })
        assertTrue(a.checkOthers(CarSnapshot(t0 + 70_000, coolantC = 85, rpm = 2000, volts = 14.2)).isEmpty())  // said once
        assertTrue(a.checkOthers(CarSnapshot(t0 + 75_000, coolantC = 112, rpm = 2000)).isEmpty())       // one reading: wait
        val hot = a.checkOthers(CarSnapshot(t0 + 80_000, coolantC = 112, rpm = 2000))
        assertEquals(CarAlerts.Kind.OVERHEAT, hot.single().kind); assertTrue(hot.single().urgent)
        assertTrue(a.checkOthers(CarSnapshot(t0 + 90_000, coolantC = 113, rpm = 2000)).isEmpty())         // not every 5 s
        assertEquals(1, a.checkOthers(CarSnapshot(t0 + 80_000 + 6 * 60_000, coolantC = 113, rpm = 2000)).size)  // again after 5 min
    }

    @Test fun warmEngineAtStartIsNotAnnounced() {
        val a = CarAlerts()
        assertTrue(a.checkOthers(CarSnapshot(0, coolantC = 85, rpm = 900)).isEmpty())
        assertTrue(a.checkOthers(CarSnapshot(5_000, coolantC = 88, rpm = 900)).isEmpty())
    }

    @Test fun checkEngineOnlyForNewCodes() {
        val a = CarAlerts()
        val first = a.checkOthers(CarSnapshot(0, checkEngine = true, codes = listOf("P0420"), codeCount = 1))
        assertEquals(listOf("P0420"), first.single().codes)
        assertTrue(a.checkOthers(CarSnapshot(5_000, checkEngine = true, codes = listOf("P0420"), codeCount = 1)).isEmpty())
        assertEquals(listOf("P0133"), a.checkOthers(CarSnapshot(10_000, checkEngine = true, codes = listOf("P0420", "P0133"), codeCount = 2)).single().codes)
    }

    @Test fun batteryAndChargingAndFuel() {
        val a = CarAlerts()
        assertTrue(a.checkOthers(CarSnapshot(0, rpm = 0, volts = 11.7)).isEmpty())                        // one reading: wait
        assertEquals(CarAlerts.Kind.LOW_BATTERY, a.checkOthers(CarSnapshot(6_000, rpm = 0, volts = 11.6)).single().kind)
        assertTrue(a.checkOthers(CarSnapshot(5_000, rpm = 900, volts = 12.5)).isEmpty())          // low charge: wait a minute
        assertEquals(CarAlerts.Kind.WEAK_CHARGING, a.checkOthers(CarSnapshot(70_000, rpm = 900, volts = 12.5)).single().kind)
        assertTrue("one fuel read could be garbled: wait", a.checkOthers(CarSnapshot(80_000, rpm = 900, volts = 14.0, fuelPct = 9)).isEmpty())
        for (t in 85_000L..115_000L step 5_000L) a.checkOthers(CarSnapshot(t, rpm = 900, volts = 14.0, fuelPct = 9))
        assertEquals(CarAlerts.Kind.LOW_FUEL, a.checkOthers(CarSnapshot(120_000, rpm = 900, volts = 14.0, fuelPct = 9)).single().kind)
        assertTrue(a.checkOthers(CarSnapshot(170_000, rpm = 900, volts = 14.0, fuelPct = 8)).isEmpty())
        // A healthy alternator never triggers the charging warning.
        val b = CarAlerts()
        assertTrue((0..20).flatMap { b.checkOthers(CarSnapshot(it * 5_000L, rpm = 2000, volts = 14.1)) }.isEmpty())
    }

    @Test fun wordsTheVoiceUses() {
        val code = CarAlerts.Alert(CarAlerts.Kind.CHECK_ENGINE, true, codes = listOf("P0420"))
        val en = CarAlerts.message(code, dutch = false, butler = true)
        assertTrue(en, en.contains("P 0 4 2 0: the catalytic converter is underperforming"))
        assertTrue(CarAlerts.message(code, dutch = true, butler = false).contains("de katalysator werkt niet goed genoeg"))
        assertTrue(CarAlerts.message(CarAlerts.Alert(CarAlerts.Kind.LOW_BATTERY, false, 11.7), false, false).contains("11 point 7 volts"))
        assertEquals("a Mitsubishi-specific code: a fault in air and fuel metering", TroubleCodes.explain("P1234", false))
        val status = CarStatusText.spoken(CarSnapshot(0, coolantC = 90, volts = 14.1, fuelPct = 40, checkEngine = false), false, true)
        assertEquals("The car, sir: engine 90 degrees, battery 14 point 1 volts, fuel 40 percent. No warning lights.", status)
        assertNotNull(CarStatusText.spoken(null, true, false))
    }

    // ------------------------------------------------------------------ ready to drive (oil has circulated)

    private fun kinds(a: CarAlerts, s: CarSnapshot) = a.check(s).map { it.kind }

    @Test fun readyToDriveAfterTheIdleTimeUsingTheCarsOwnClock() {
        val a = CarAlerts(readySeconds = 30)
        // We connect 12 s after the engine started (Android Auto + adapter take a moment).
        assertTrue(CarAlerts.Kind.READY_TO_DRIVE !in kinds(a, CarSnapshot(0, coolantC = 15, rpm = 1100, speedKmh = 0, runtimeS = 12)))
        assertTrue(a.countingDown())
        assertTrue(CarAlerts.Kind.READY_TO_DRIVE !in kinds(a, CarSnapshot(10_000, coolantC = 16, rpm = 1050, speedKmh = 0, runtimeS = 28)))
        val ready = a.check(CarSnapshot(12_000, coolantC = 17, rpm = 1000, speedKmh = 0, runtimeS = 30)).single { it.kind == CarAlerts.Kind.READY_TO_DRIVE }
        assertTrue(ready.timely)
        assertTrue(CarAlerts.Kind.READY_TO_DRIVE !in kinds(a, CarSnapshot(20_000, coolantC = 18, rpm = 1000, runtimeS = 38)))  // once
        assertFalse(a.countingDown())
    }

    @Test fun withoutTheCarsClockItCountsFromTheFirstReading() {
        val a = CarAlerts(readySeconds = 30)
        assertTrue(a.check(CarSnapshot(1_000, coolantC = 15, rpm = 1100)).none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
        assertTrue(a.check(CarSnapshot(29_000, coolantC = 16, rpm = 1100)).none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
        assertTrue(a.check(CarSnapshot(31_000, coolantC = 16, rpm = 1100)).any { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
    }

    @Test fun longerInTheColdSkippedWhenWarmOrAlreadyDriving() {
        assertEquals(30, CarAlerts.waitFor(15, 12, 30))
        assertEquals(45, CarAlerts.waitFor(10, 1, 30))     // near freezing outside
        assertEquals(60, CarAlerts.waitFor(-8, -9, 30))    // proper winter
        assertEquals(90, CarAlerts.waitFor(-8, -9, 90))    // your own longer setting wins
        val warm = CarAlerts(30)  // short stop: engine still warm
        assertTrue((0..20).flatMap { warm.check(CarSnapshot(it * 2_000L, coolantC = 85, rpm = 900, runtimeS = it * 2)) }.none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
        val driving = CarAlerts(30)  // pulled away before the time was up
        driving.check(CarSnapshot(0, coolantC = 15, rpm = 1100, speedKmh = 0, runtimeS = 5))
        assertTrue(driving.check(CarSnapshot(15_000, coolantC = 16, rpm = 2000, speedKmh = 30, runtimeS = 20)).none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
        assertTrue(driving.check(CarSnapshot(30_000, coolantC = 17, rpm = 900, speedKmh = 0, runtimeS = 35)).none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
        val late = CarAlerts(30)  // connected long after starting: nothing to say
        assertTrue(late.check(CarSnapshot(0, coolantC = 40, rpm = 900, speedKmh = 0, runtimeS = 400)).none { it.kind == CarAlerts.Kind.READY_TO_DRIVE })
    }

    @Test fun readyToDriveWordsAndRuntimeDecoding() {
        val r = CarAlerts.Alert(CarAlerts.Kind.READY_TO_DRIVE, false, 30.0, timely = true)
        assertTrue(CarAlerts.message(r, dutch = false, butler = true).startsWith("The oil has had its moment, sir. You may proceed."))
        assertTrue(CarAlerts.message(r, dutch = true, butler = true).contains("U kunt rustig wegrijden"))
        assertEquals(300, Elm327.runtimeS(listOf(0x01, 0x2C)))
        // The session reads the engine's run time while it's young.
        val fake = FakeElm(FakeElm.ASX + mapOf("011F" to "411F0012\r\r"))  // 18 s
        val session = ObdSession(fake)
        assertNull(session.start())
        assertEquals(18, session.read(null, full = false, readCodes = false).runtimeS)
    }

    // ------------------------------------------------------------------ audit fixes

    @Test fun aGarbledReadingIsNotAnOverheat() {
        val a = CarAlerts()
        assertTrue(a.check(CarSnapshot(0, coolantC = 215, rpm = 2000)).none { it.kind == CarAlerts.Kind.OVERHEAT })   // 0xFF - 40
        assertTrue(a.check(CarSnapshot(5_000, coolantC = 215, rpm = 2000)).none { it.kind == CarAlerts.Kind.OVERHEAT })
    }

    @Test fun restartingAfterAStallIsNotAWeakBattery() {
        val a = CarAlerts()
        a.check(CarSnapshot(0, coolantC = 70, rpm = 800, volts = 14.1))           // driving
        a.check(CarSnapshot(5_000, coolantC = 70, rpm = 0, volts = 12.3))         // stalled
        a.check(CarSnapshot(7_000, coolantC = 70, rpm = 0, volts = 9.8))          // cranking dip
        assertTrue(a.check(CarSnapshot(13_000, coolantC = 70, rpm = 0, volts = 9.9)).none { it.kind == CarAlerts.Kind.LOW_BATTERY })
    }

    @Test fun codesTheCarsOwnCountDoesntBackNeedThreeGoodReadsInARow() {
        val a = CarAlerts()
        fun told(at: Long, read: Long, codes: List<String>, count: Int?) =
            a.check(CarSnapshot(at, checkEngine = true, codes = codes, codeCount = count, storedReadAt = read))
                .filter { it.kind == CarAlerts.Kind.CHECK_ENGINE }.flatMap { it.codes }
        // The car says 1 code but the answer lists 3: garbled - wait for repeats.
        assertTrue(told(0, 0, listOf("P0420", "U3DCC", "B1328"), 1).isEmpty())
        assertTrue(told(5_000, 0, listOf("P0420", "U3DCC", "B1328"), 1).isEmpty())          // same read kept: no count
        assertTrue(told(600_000, 600_000, listOf("P0420", "C26CC"), 1).isEmpty())
        assertEquals(listOf("P0420"), told(1_200_000, 1_200_000, listOf("P0420", "P16E4"), 1))  // 3 reads in a row
    }

    @Test fun aLightThatsBeenOnForWeeksIsNotNewsAtEveryStart() {
        val known = CarAlerts(toldRecently = setOf("P0420"))
        assertTrue(known.check(CarSnapshot(0, checkEngine = true, codes = listOf("P0420"), codeCount = 1)).none { it.kind == CarAlerts.Kind.CHECK_ENGINE })
        assertEquals(listOf("P0171"), known.check(CarSnapshot(5_000, checkEngine = true, codes = listOf("P0420", "P0171"), codeCount = 2))
            .single { it.kind == CarAlerts.Kind.CHECK_ENGINE }.codes)                // a NEW code is still told right away
        val lightOnly = CarAlerts(toldRecently = setOf(CarAlerts.MIL))
        assertTrue(lightOnly.check(CarSnapshot(0, checkEngine = true)).none { it.kind == CarAlerts.Kind.CHECK_ENGINE })
        assertTrue(CarAlerts().told().isEmpty())
    }
}
