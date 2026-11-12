package com.daand.drivemusic

import com.daand.drivemusic.obd.Elm327
import com.daand.drivemusic.obd.ObdLink
import com.daand.drivemusic.obd.ObdSession
import com.daand.drivemusic.obd.Pids
import com.daand.drivemusic.obd.TroubleCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Can the app keep up with all the data, and does anything new end up in the log? */
class ObdDataTest {
    /** A CAN car supporting [pids]; answers combined questions unless [combined] is false. */
    class Car(val pids: Set<Int>, val combined: Boolean = true, val value: Int = 0x40, val extra: Map<String, String> = emptyMap()) : ObdLink {
        val sent = mutableListOf<String>()
        private fun mask(base: Int): String {
            var bits = 0L
            for (i in 1..32) if ((base + i) in pids || (i == 32 && pids.any { it > base + 32 })) bits = bits or (1L shl (32 - i))
            return "%08X".format(bits)
        }
        override fun send(cmd: String, timeoutMs: Long): String {
            sent += cmd
            extra[cmd]?.let { return it }
            if (cmd == "ATDP") return "AUTO, ISO 15765-4 (CAN 11/500)\r"
            if (cmd == "ATI") return "ELM327 v2.3\r"
            if (cmd.startsWith("AT")) return "OK\r"
            if (cmd.length == 4 && cmd.startsWith("01")) {
                val pid = cmd.substring(2).toInt(16)
                if (pid % 0x20 == 0) return "41%02X".format(pid) + mask(pid) + "\r"
                if (pid !in pids) return "NO DATA\r"
                return "41%02X".format(pid) + "%02X".format(value).repeat(Pids.lengthOf(pid) ?: 1) + "\r"
            }
            if (cmd.startsWith("01")) {
                if (!combined) return "NO DATA\r"
                val ps = cmd.substring(2).chunked(2).map { it.toInt(16) }.filter { it in pids }
                return "41" + ps.joinToString("") { "%02X".format(it) + "%02X".format(value).repeat(Pids.lengthOf(it) ?: 1) } + "\r"
            }
            return "NO DATA\r"
        }
        override fun close() {}
    }

    private val all = Pids.ALL.map { it.pid }.toSet() + setOf(0x01, 0x1F)

    /** Values for which an answer of all-0x40 bytes is physically possible (others are rightly dropped). */
    private fun plausibleAt40(pred: (Pids.Info) -> Boolean = { true }) =
        Pids.ALL.filter(pred).count { it.read(List(it.len) { 0x40 }) != null }

    @Test fun combinedAnswersInEveryShape() {
        val len = { p: Int -> Pids.lengthOf(p) }
        assertEquals(mapOf(0x0C to listOf(0x1A, 0xF8), 0x0D to listOf(0x32), 0x05 to listOf(0x7B)),
            Elm327.multiPid(listOf("410C1AF80D32057B"), len))
        // Split over CAN frames, with the length header line.
        assertEquals(listOf(0x7B), Elm327.multiPid(listOf("00C", "0:410C1AF80D32", "1:057B11200B63"), len)[0x05])
        assertEquals(listOf(0x63), Elm327.multiPid(listOf("00C", "0:410C1AF80D32", "1:057B11200B63"), len)[0x0B])
        // Two control units answering: the first answer per value is kept, the second unit's extra value too.
        val two = Elm327.multiPid(listOf("410D32", "410D33057B"), len)
        assertEquals(listOf(0x32), two[0x0D]); assertEquals(listOf(0x7B), two[0x05])
        // Unknown value in the middle: stop safely rather than mis-splitting.
        assertEquals(setOf(0x0C), Elm327.multiPid(listOf("410C1AF8FF1234"), len).keys)
    }

    @Test fun combinedQuestionsKeepTheLiveTabFast() {
        val car = Car(all)
        val s = ObdSession(car)
        assertNull(s.start())
        assertTrue(s.batching)
        car.sent.clear()
        val snap = s.read(null, full = false, readCodes = false)
        assertEquals("quick read = 2 combined questions (was 10 one by one)", 2, car.sent.size)
        assertEquals(plausibleAt40 { it.fast }, Pids.ALL.count { it.fast && snap.extra.containsKey(it.pid) })
        car.sent.clear()
        s.read(snap, full = true, readCodes = true)
        assertTrue("full read in ${car.sent.size} questions (was 38)", car.sent.size <= 12)
    }

    @Test fun aCarThatRefusesCombinedQuestionsStillGetsEverything() {
        val logs = mutableListOf<String>()
        val car = Car(all, combined = false)
        val s = ObdSession(car) { logs += it }
        assertNull(s.start())
        val snap = s.read(null, full = true, readCodes = false)
        assertFalse("combined questions switched off", s.batching)
        assertTrue(logs.any { it.contains("asking one at a time") })
        assertEquals("every (plausible) value still read", plausibleAt40(), Pids.ALL.count { snap.extra.containsKey(it.pid) })
    }

    @Test fun unknownValuesTheCarSharesAreLoggedWithRawData() {
        val logs = mutableListOf<String>()
        val car = Car(setOf(0x05, 0x0C, 0x13, 0x1C, 0x51), extra = mapOf("0113" to "411303\r", "011C" to "411C06\r", "0151" to "415101\r"))
        assertNull(ObdSession(car) { logs += it }.start())
        val line = logs.single { it.contains("not shown yet") }
        assertTrue(line, line.contains("3 values") && line.contains("13=411303") && line.contains("1C=411C06") && line.contains("51=415101"))
        assertTrue(logs.any { it.contains("adapter 'ELM327 v2.3'") && it.contains("ISO 15765-4") })
    }

    @Test fun garbageIsLoggedOncePerValueWithTheRawBytes() {
        val logs = mutableListOf<String>()
        val car = Car(setOf(0x05, 0x0C, 0x0D), value = 0xFF)   // 0xFF everywhere: 215 °C, 255 km/h
        val s = ObdSession(car) { logs += it }
        assertNull(s.start())
        s.read(null, false, false); s.read(null, false, false)
        val dropped = logs.filter { it.contains("ignored an implausible") }
        assertEquals("once per value, not every read: coolant 215 °C, RPM 16383, speed 255", 3, dropped.size)
        assertTrue(dropped.joinToString(), dropped.any { it.contains("Speed (PID 0D): raw FF") })
    }

    @Test fun unknownCodesAreRecognisedAsSuch() {
        assertTrue(TroubleCodes.isKnown("P0420"))
        assertTrue(TroubleCodes.isKnown("P0303"))       // by rule
        assertFalse(TroubleCodes.isKnown("P1999"))      // only its area is known -> logged
    }
}
