package com.daand.drivemusic

import com.daand.drivemusic.obd.Elm327
import com.daand.drivemusic.obd.ObdSession
import com.daand.drivemusic.obd.ReadOnly
import com.daand.drivemusic.obd.StreamLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The byte-level conversation with the adapter, as over Bluetooth: what we send, and answers that
 * come late, with echo, with stray zero bytes, too long, or never.
 */
class AdapterLinkTest {
    /** A pretend adapter: for each command, bytes that become readable after a delay. */
    private class Adapter(private val reply: (String) -> List<Pair<Long, String>>) {
        val sent = mutableListOf<String>()
        private val pending = ArrayDeque<Pair<Long, Int>>()   // (ready at, byte)
        private val cmd = StringBuilder()
        val input = object : InputStream() {
            override fun available(): Int = synchronized(pending) { pending.count { it.first <= System.currentTimeMillis() } }
            override fun read(): Int = synchronized(pending) {
                val b = pending.firstOrNull { it.first <= System.currentTimeMillis() } ?: return -1
                pending.remove(b); b.second
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (b == '\r'.code) {
                    val c = cmd.toString(); cmd.clear(); sent += c
                    var t = System.currentTimeMillis()
                    synchronized(pending) { for ((delay, text) in reply(c)) { t += delay; text.forEach { pending.addLast(t to it.code) } } }
                } else cmd.append(b.toChar())
            }
        }
        fun link() = StreamLink(input, output)
    }

    @Test fun onlyQuestionsCanEverBeSent() {
        for (ok in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0", "ATDP", "ATI", "ATRV",
            "0100", "010C", "010C0D05", "010C0D050F112F", "03", "07", "0A", "0902")) assertTrue(ok, ReadOnly.allowed(ok))
        // 04 clears fault codes; 08 controls systems; 2E/31/3B write and run routines (UDS); AT MA floods the bus.
        for (no in listOf("04", "0400", "08", "0801", "2E", "31", "3B", "1101", "27", "AT MA", "ATMA", "ATPP", "ATWS",
            "ATSH7E0", "ATCRA", "01", "010C0D050F112F33", "0C", "", "0100\r04", "010c")) assertFalse("'$no'", ReadOnly.allowed(no))
        val a = Adapter { listOf(0L to "OK\r\r>") }
        try { a.link().send("04", 500); fail("must refuse") } catch (e: IllegalArgumentException) { }
        assertTrue("nothing reached the adapter", a.sent.isEmpty())
    }

    @Test fun lateAnswerIsWaitedForAndNeverBleedsIntoTheNextQuestion() {
        val a = Adapter { c -> when (c) {
            "010C" -> listOf(300L to "410C1AF8\r\r>")          // 300 ms late for a 100 ms timeout
            "010D" -> listOf(10L to "410D32\r\r>")
            else -> listOf(0L to "NO DATA\r\r>")
        } }
        val l = a.link()
        assertEquals(listOf(0x1A, 0xF8), Elm327.pidData(Elm327.lines(l.send("010C", 100)), 0x0C))
        assertEquals(listOf(0x32), Elm327.pidData(Elm327.lines(l.send("010D", 100)), 0x0D))
    }

    @Test fun echoAndStrayZeroBytesAreCleanedUp() {
        val a = Adapter { c -> listOf(0L to "$c\r\u0000410C1AF8\u0000\r\r>") }
        assertEquals(listOf("410C1AF8"), Elm327.lines(a.link().send("010C", 500)))
    }

    @Test fun aStuckAdapterMeansReconnectNotGuesswork() {
        val silent = Adapter { emptyList() }
        try { silent.link().send("010C", 50); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("didn't answer")) }
        val halfway = Adapter { listOf(0L to "410C1A") }     // starts answering, never finishes
        try { halfway.link().send("010C", 50); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("mid-answer")) }
    }

    @Test fun aRunawayAnswerIsCutOffButStillReadToItsEnd() {
        val a = Adapter { c -> if (c == "03") listOf(0L to "4" + "3".repeat(10_000) + "\r>") else listOf(0L to "410D32\r\r>") }
        val l = a.link()
        assertTrue(l.send("03", 1_000).length <= StreamLink.MAX_ANSWER)
        assertEquals("next answer is clean", listOf(0x32), Elm327.pidData(Elm327.lines(l.send("010D", 500)), 0x0D))
    }

    @Test fun aWholeSessionOverTheByteLink() {
        val a = Adapter { c -> listOf(5L to ((FakeElm.ASX[c] ?: if (c.startsWith("AT")) "OK\r\r" else "NO DATA\r\r") + ">")) }
        val session = ObdSession(a.link(), 1.6)
        assertNull(session.start())
        val snap = session.read(null, full = true, readCodes = true)
        assertEquals(1726, snap.rpm); assertEquals(50, snap.speedKmh); assertEquals(83, snap.coolantC)
        assertEquals(listOf("P0133", "P0420", "P0171"), snap.codes)
        assertTrue("everything sent was a question: ${a.sent}", a.sent.all { ReadOnly.allowed(it) })
        assertFalse(a.sent.any { it == "04" || it.startsWith("08") })
    }

    @Test fun anAdapterThatNeverStopsTalkingDoesntFreezeTheApp() {
        val flood = object : InputStream() {
            override fun available() = 1_000
            override fun read() = 'A'.code
        }
        val l = StreamLink(flood, object : OutputStream() { override fun write(b: Int) {} })
        val started = System.currentTimeMillis()
        try { l.send("010C", 200); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("nobody asked")) }
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }
}
