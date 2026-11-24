package com.daand.drivemusic

import com.daand.drivemusic.obd.FreezeBudget
import com.daand.drivemusic.obd.ObdLink
import com.daand.drivemusic.obd.ObdSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The freeze frame runs on the OBD poll thread, so its cost is live data standing still. These tests
 * pin the budget, the clock it is measured on, and that a stop is not mistaken for a missing value.
 */
@RunWith(RobolectricTestRunner::class)
class FreezeBudgetTest {

    /** The normal ASX answers, plus a car that stores P0420 and answers the freeze frame at once. */
    private fun healthy(): Map<String, String> = FakeElm.ASX + mapOf(
        "020200" to "4202000420\r\r",      // the stored code: P0420
        "020D00" to "420D0032\r\r",        // speed 50 km/h
        "020C00" to "420C001AF8\r\r",      // 1726 rpm
        "020500" to "4205007B\r\r",        // 83 °C
        "020400" to "42040080\r\r",        // 50 % load
        "021100" to "42110064\r\r",        // 100 % throttle
        "020B00" to "420B0064\r\r",        // manifold pressure
        "020F00" to "420F0000\r\r",        // intake air 0 °C
    )

    private fun session(link: ObdLink, log: MutableList<String> = mutableListOf(), now: (() -> Long)? = null) =
        if (now == null) ObdSession(link, 1.6, log = { log += it })
        else ObdSession(link, 1.6, log = { log += it }, now = now)

    /** Read the live values once so a stored code marks the freeze frame as due, like the poll does. */
    private fun armFreeze(s: ObdSession) {
        assertNull(s.start())
        s.read(null, full = true, readCodes = true)
    }

    @Test fun theBudgetArithmeticMatchesTheTimeoutsItReplaced() {
        // Old worst case: 020200 at 1.2 s, then seven values at 0.8 s, each plus the 2 s grace.
        val oldWorst = (1_200L + ObdLink.GRACE_MS) + 7 * (800L + ObdLink.GRACE_MS)
        assertEquals("the number this replaced", 22_800L, oldWorst)
        assertTrue("and the budget is far below it", FreezeBudget.TOTAL_MS * 3 < oldWorst)
        // A question always fits inside what is left, grace included.
        assertEquals(FreezeBudget.CODE_MS, FreezeBudget.slice(0, FreezeBudget.CODE_MS))
        assertEquals(FreezeBudget.PID_MS, FreezeBudget.slice(0))
        assertNull("no room for another question", FreezeBudget.slice(FreezeBudget.TOTAL_MS))
        // A clock that went backwards cannot buy more than one question either.
        assertEquals(FreezeBudget.PID_MS, FreezeBudget.slice(-60_000))
    }

    @Test fun aHealthyAdapterStillGivesEveryValue() {
        val answers = healthy()
        val link = FakeElm(answers)
        val logs = mutableListOf<String>()
        val s = session(link, logs)
        armFreeze(s)
        assertTrue("a frame was read", s.readFreezeIfDue())
        val f = s.freeze
        assertNotNull("the freeze frame came back", f)
        assertEquals("P0420", f!!.code)
        assertEquals("and all seven values with it", 7, f.values.size)
        assertTrue("nothing was said about a missing budget", logs.none { it.contains("budget") })
    }

    @Test fun aSilentAdapterCannotStallTheLivePollForHalfAMinute() {
        val answers = healthy()
        val asked = mutableListOf<Pair<String, Long>>()
        val silent = object : ObdLink {
            override fun send(cmd: String, timeoutMs: Long): String {
                if (cmd == "020200") return answers.getValue(cmd)     // the code answers at once
                if (cmd.startsWith("02")) {
                    asked += cmd to timeoutMs
                    Thread.sleep(timeoutMs)                          // the values never answer
                    return "NO DATA\r\r"
                }
                return answers[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            }
            override fun close() {}
        }
        val logs = mutableListOf<String>()
        val s = session(silent, logs)
        armFreeze(s)
        val at = System.currentTimeMillis()
        s.readFreezeIfDue()
        val took = System.currentTimeMillis() - at
        assertTrue("bounded by the budget, not by eight timeouts: $took ms", took < FreezeBudget.TOTAL_MS + 2 * ObdLink.GRACE_MS)
        assertTrue("it did try the values", asked.isNotEmpty())
        assertTrue("every question got a slice of the budget", asked.all { it.second <= FreezeBudget.PID_MS })
        assertTrue("and the log says what happened:\n" + logs.joinToString("\n"), logs.any { it.contains("budget") })
    }

    /**
     * The poll thread is stopped by interrupting it. `runCatching` around the link call used to swallow
     * that and clear the flag, so a stop during a freeze read kept asking the adapter for more.
     */
    @Test fun stoppingThePollThreadAbortsTheFreezeRead() {
        val answers = healthy()
        val link = object : ObdLink {
            override fun send(cmd: String, timeoutMs: Long): String {
                if (cmd.startsWith("02") && cmd != "020200") throw InterruptedException("stop requested")
                return answers[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            }
            override fun close() {}
        }
        val s = session(link)
        armFreeze(s)
        try {
            s.readFreezeIfDue()
            fail("the interrupt must not be swallowed")
        } catch (e: InterruptedException) {
            assertTrue("and the flag is left set for the poll loop", Thread.interrupted())
        }
    }

    /** The budget is a duration, so it is measured on the monotonic clock - not on the wall clock. */
    @Test fun aClockJumpForwardStopsTheReadInsteadOfExtendingIt() {
        var t = 1_000L
        val answers = healthy()
        val link = object : ObdLink {
            override fun send(cmd: String, timeoutMs: Long): String {
                t += 3_500                       // the code question ate most of the budget
                return answers[cmd] ?: if (cmd.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            }
            override fun close() {}
        }
        val logs = mutableListOf<String>()
        val s = session(link, logs, now = { t })
        armFreeze(s)
        s.readFreezeIfDue()
        val values = s.freeze?.values?.size ?: 0
        assertTrue("the jump ends the read after a value or two, not after seven: $values", values <= 2)
        assertTrue("and the log says the budget ran out:\n" + logs.joinToString("\n"), logs.any { it.contains("budget") })
    }
}
