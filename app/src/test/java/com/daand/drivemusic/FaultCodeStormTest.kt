package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.ObdLink
import com.daand.drivemusic.obd.ObdSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Red team: an adapter that answers fault-code questions with 1000 garbage codes (plus broken
 * frames and error text), with 2 real codes hidden in there - P0420 (confirmed, warning light on)
 * and P0301 (pending). Only the real ones may ever be spoken.
 */
class FaultCodeStormTest {
    private var rnd = Random(1234)
    private val real = listOf(0x04 to 0x20)          // P0420, stored + light on
    private val realPending = listOf(0x03 to 0x01)   // P0301, pending

    /** A CAN multi-frame answer: [mode byte, count, code bytes...] split in ELM "0:" / "1:" frames. */
    private fun canAnswer(modeByte: Int, codes: List<Pair<Int, Int>>): String {
        val payload = listOf(modeByte, codes.size) + codes.flatMap { listOf(it.first, it.second) }
        if (payload.size <= 7) return payload.joinToString("") { "%02X".format(it) } + "\r\r"
        val sb = StringBuilder("%03X\r".format(payload.size))
        sb.append("0:").append(payload.take(6).joinToString("") { "%02X".format(it) }).append("\r")
        payload.drop(6).chunked(7).forEachIndexed { i, chunk ->
            sb.append("${(i + 1) % 16}:").append(chunk.joinToString("") { "%02X".format(it) }.padEnd(14, '0')).append("\r")
        }
        return sb.append("\r").toString()
    }

    private fun garbageCodes(n: Int) = List(n) { rnd.nextInt(0, 256) to rnd.nextInt(1, 256) }

    /** Sometimes the whole answer is broken in a different way. */
    private fun broken(): String = when (rnd.nextInt(6)) {
        0 -> "CAN ERROR\r\r"
        1 -> "STOPPED\r\r"
        2 -> "?\r\r"
        3 -> "0:43\r\r"                                                      // frame cut off
        4 -> List(3) { List(14) { "0123456789ABCDEFXZ"[rnd.nextInt(18)] }.joinToString("") }.joinToString("\r") + "\r\r"
        else -> "BUS INIT: ...ERROR\r\r"
    }

    private inner class StormElm : ObdLink {
        var read = 0
        override fun send(cmd: String, timeoutMs: Long): String = when (cmd) {
            "0101" -> "41018107E500\r\r"   // light ON, the car says: 1 stored code
            "03" -> if (rnd.nextInt(5) == 0) broken() else canAnswer(0x43, (real + garbageCodes(10)).shuffled(rnd))
            "07" -> if (rnd.nextInt(5) == 0) broken() else canAnswer(0x47, (realPending + garbageCodes(10)).shuffled(rnd))
            "0A" -> broken()
            else -> FakeElm.ASX[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }
        override fun close() {}
    }

    @Test fun onlyTheRealCodesAreEverSpoken() {
        // 20 different storms (20 x 1000 garbage codes): never a single fake one spoken.
        for (seed in 1..20) { rnd = Random(seed); storm(seed) }
    }

    private fun storm(seed: Int) {
        val link = StormElm()
        val session = ObdSession(link, 1.6)
        session.start()
        val alerts = CarAlerts(readySeconds = 0)
        val spokenUrgent = mutableListOf<String>()
        val spokenEarly = mutableListOf<String>()
        var prev: com.daand.drivemusic.obd.CarSnapshot? = null
        val fake = HashMap<Long, Long>()
        val started = System.nanoTime()
        // 50 code reads (one every 10 minutes of driving) x 20 garbage codes = 1000 garbage codes.
        for (i in 0 until 50) {
            val t = i * 600_000L
            Thread.sleep(2)  // every real read gets its own clock time
            val raw = session.read(prev, full = true, readCodes = true)
            prev = raw
            // Spread the reads 10 minutes apart; a read that failed keeps its old read time.
            val snap = raw.copy(at = t, codesReadAt = raw.codesReadAt?.let { fake.getOrPut(it) { t } },
                storedReadAt = raw.storedReadAt?.let { fake.getOrPut(it + 1) { t } })
            for (a in alerts.check(snap)) when (a.kind) {
                CarAlerts.Kind.CHECK_ENGINE -> spokenUrgent += a.codes
                CarAlerts.Kind.EARLY_CODE -> spokenEarly += a.codes
                else -> {}
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("Storm $seed: urgent=$spokenUrgent early=$spokenEarly in $ms ms")
        assertEquals("seed $seed: only the real stored code, once", listOf("P0420"), spokenUrgent)
        assertEquals("seed $seed: only the real pending code, once", listOf("P0301"), spokenEarly)
        assertTrue("fast enough ($ms ms)", ms < 5_000)
    }
}
