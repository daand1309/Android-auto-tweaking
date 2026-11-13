package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.CarStatusText
import com.daand.drivemusic.obd.ObdLink
import com.daand.drivemusic.obd.ObdSession
import com.daand.drivemusic.obd.Pids
import com.daand.drivemusic.obd.ReadOnly
import com.daand.drivemusic.obd.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Red team: an adapter (broken, clone, or deliberately hostile) that answers every question with
 * rubbish. The app may give up on it, but must never crash, never show an impossible value, never
 * say an alert out of rubbish, and never send anything but questions.
 */
class HostileAdapterTest {
    private class Hostile(private val rnd: Random) : ObdLink {
        val sent = mutableListOf<String>()
        private fun hex(n: Int) = List(n) { "%02X".format(rnd.nextInt(256)) }.joinToString("")
        override fun send(cmd: String, timeoutMs: Long): String {
            sent += cmd
            if (!ReadOnly.allowed(cmd)) throw IllegalArgumentException("refused")
            return when (rnd.nextInt(16)) {
                0 -> "41" + hex(rnd.nextInt(0, 12)) + "\r\r"                          // a random "answer"
                1 -> "41" + cmd.drop(2).take(2) + hex(rnd.nextInt(0, 6)) + "\r\r"    // right PID, random length
                2 -> "41" + "%02X".format(rnd.nextInt(256)) + hex(4) + "\r\r"          // wrong PID
                3 -> "%03X\r0:".format(rnd.nextInt(4096)) + hex(6) + "\r1:" + hex(7) + "\r9:" + hex(3) + "\r\r"  // fake multi-frame
                4 -> hex(rnd.nextInt(1, 300)) + "\r\r"                                  // a wall of hex
                5 -> List(rnd.nextInt(1, 40)) { "41" + hex(rnd.nextInt(1, 8)) }.joinToString("\r") + "\r\r"  // many "ECUs"
                6 -> "43" + hex(rnd.nextInt(0, 40)) + "\r\r"                            // codes-ish
                7 -> "49 02 01 " + List(20) { "%02X".format(rnd.nextInt(256)) }.joinToString(" ") + "\r\r"     // VIN-ish
                8 -> List(rnd.nextInt(1, 5)) { String(CharArray(rnd.nextInt(0, 60)) { (rnd.nextInt(32, 127)).toChar() }) }.joinToString("\r")
                9 -> "${rnd.nextInt(-99, 99)}.${rnd.nextInt(100)}V\r\r"                // voltage text
                10 -> "SEARCHING...\rUNABLE TO CONNECT\r\r"
                11 -> "NO DATA\r\r"
                12 -> "STOPPED\r\r"
                13 -> "?\r\r"
                14 -> if (rnd.nextInt(50) == 0) throw IOException("dropped") else "OK\r\r"
                else -> (FakeElm.ASX[cmd] ?: "NO DATA\r\r")                            // sometimes the truth
            }
        }
        override fun close() {}
    }

    @Test fun fiveThousandSessionsOfRubbish() {
        var crashes = 0; var gaveUp = 0; var sessions = 0; var reads = 0; var alerts = 0
        val crashKinds = LinkedHashMap<String, String>()
        for (seed in 1..5_000) {
            val adapter = Hostile(Random(seed))
            try {
                val session = ObdSession(adapter, 1.6)
                if (session.start() != null) { gaveUp++; continue }
                sessions++
                session.vin?.let { assertTrue("VIN '$it'", it.length == 17 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' }) }
                val a = CarAlerts(0); val trip = Trip()
                var prev: CarSnapshot? = null
                for (i in 0 until 6) {
                    val s = session.read(prev, full = i % 2 == 0, readCodes = i == 0).copy(at = i * 5_000L)
                    prev = s; reads++
                    check(s)
                    trip.add(s)
                    // Nothing urgent may be said off one rubbish read (codes must agree with the car's count).
                    for (al in a.check(s)) {
                        alerts++
                        if (al.kind == CarAlerts.Kind.CHECK_ENGINE && al.codes.isNotEmpty())
                            assertTrue("code alert without the car's count backing it: $s", s.codeCount != null && s.codes.size <= s.codeCount!!)
                        CarAlerts.message(al, false, true)
                    }
                    CarStatusText.report(s, false, trip)
                    CarDash.values(s, trip, false, null)
                }
                assertTrue("only questions sent", adapter.sent.all { ReadOnly.allowed(it) })
            } catch (e: IOException) {
                gaveUp++
            } catch (e: Throwable) {
                crashes++
                val where = e.stackTrace.firstOrNull { it.className.contains("drivemusic") && !it.className.contains("Test") }?.toString() ?: ""
                crashKinds.putIfAbsent((e.message ?: "").substringBefore(' ') + "@" + where, "seed $seed: $e at $where")
            }
        }
        println("Hostile: $sessions sessions, $reads reads, $gaveUp gave up, $alerts alerts, $crashes crashes")
        assertEquals("crashes: ${crashKinds.values.joinToString(" || ")}", 0, crashes)
    }

    /** Every value on screen must be one a real car can have. */
    private fun check(s: CarSnapshot) {
        for ((pid, v) in s.extra) {
            val info = Pids.ALL.firstOrNull { it.pid == pid } ?: continue
            assertTrue("PID %02X = $v".format(pid), v.isFinite() && v in info.plausible)
        }
        s.speedKmh?.let { assertTrue("speed $it", it in 0..250) }
        s.rpm?.let { assertTrue("rpm $it", it in 0..16_384) }
        s.fuelPct?.let { assertTrue("fuel $it", it in 0..100) }
        s.volts?.let { assertTrue("volts $it", it in 5.0..20.0) }
        s.codeCount?.let { assertTrue("count $it", it in 0..127) }
        (s.codes + s.pendingCodes + s.permanentCodes).forEach { assertTrue("code '$it'", Regex("^[PCBU][0-3][0-9A-F]{3}$").matches(it)) }
        s.litresPerHour?.let { assertTrue("lph $it", it.isFinite() && it in 0.0..100.0) }
    }

    @Test fun aSlowAdapterIsNotMistakenForTheIgnitionBeingOff() {
        // An older/slow adapter that won't take combined questions: every value asked one by one, 60 ms each.
        var session: ObdSession? = null
        var maxSilence = 0L
        var readStart = 0L
        val slow = object : ObdLink {
            override fun send(cmd: String, timeoutMs: Long): String {
                Thread.sleep(60)
                session?.let { ss -> if (readStart > 0 && ss.lastAnswerAt > 0) maxSilence = maxOf(maxSilence, System.currentTimeMillis() - maxOf(ss.lastAnswerAt, readStart)) }
                return when {
                    cmd == "ATDP" -> "ISO 9141-2\r\r"   // not CAN: no combined questions
                    cmd == "0100" -> "4100BE3FB813\r\r"
                    cmd.length == 4 && cmd.startsWith("01") -> "41" + cmd.drop(2) + "7B7B7B7B\r\r"
                    else -> FakeElm.ASX[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
                }
            }
            override fun close() {}
        }
        val ss = ObdSession(slow, 1.6); session = ss
        assertEquals(null, ss.start())
        readStart = System.currentTimeMillis()
        ss.read(null, full = true, readCodes = true)
        val took = System.currentTimeMillis() - readStart
        assertTrue("the read was slow ($took ms)", took > 1_000)
        assertTrue("but the car was heard from all along (max gap $maxSilence ms)", maxSilence < 400)
    }

    @Test fun aClockJumpCantRushAWarning() {
        // One garbled "0 °C outside" read, then the phone's clock jumps an hour ahead (network time).
        val a = CarAlerts(0)
        fun icy(at: Long, t: Int) = a.check(CarSnapshot(at, rpm = 900, coolantC = 85, ambientC = t)).any { it.kind == CarAlerts.Kind.ICY_ROAD }
        assertTrue(!icy(0, 0))
        assertTrue("clock jumped 1 h: still only one reading's worth", !icy(3_600_000, 0))
        assertTrue(!icy(3_605_000, 12))
        // Real cold keeps reading cold: told after 40 s of readings.
        val b = CarAlerts(0)
        val told = (0..10).map { i -> b.check(CarSnapshot(i * 5_000L, rpm = 900, coolantC = 85, ambientC = 1)).any { it.kind == CarAlerts.Kind.ICY_ROAD } }
        assertEquals(8, told.indexOf(true))
    }
}
