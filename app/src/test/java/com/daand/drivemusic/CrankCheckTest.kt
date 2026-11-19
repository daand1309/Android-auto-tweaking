package com.daand.drivemusic

import com.daand.drivemusic.obd.CrankCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrankCheckTest {
    /** Samples every 150 ms: resting, then the starter's dip, then charging. */
    private fun start(resting: Double, dip: Double): CrankCheck.Result? {
        val c = CrankCheck(); var t = 0L; var r: CrankCheck.Result? = null
        fun s(v: Double, n: Int) = repeat(n) { t += 150; c.add(t, v)?.let { r = it } }
        s(resting, 20); s(dip + 0.4, 1); s(dip, 3); s(dip + 0.6, 2); s(11.9, 2); s(13.9, 5)
        return r
    }

    @Test fun aHealthyStart() {
        val r = start(12.45, 10.4)!!
        assertEquals(12.45, r.restingV, 0.01)
        assertEquals(10.4, r.minV, 0.01)
        assertEquals("good", CrankCheck.verdict(r.minV))
    }

    @Test fun aTiredBattery() {
        assertEquals("tired", CrankCheck.verdict(start(12.2, 9.8)!!.minV))
        assertEquals("replace soon", CrankCheck.verdict(start(12.0, 9.1)!!.minV))
    }

    @Test fun noStartNoResult() {
        val c = CrankCheck(); var t = 0L
        repeat(40) { t += 150; assertNull(c.add(t, 12.4)) }
        // Headlights on (a slow sag) and then charging without a starter dip: not a crank.
        repeat(10) { t += 150; c.add(t, 12.1) }
        repeat(5) { t += 150; assertNull(c.add(t, 13.8)) }
    }

    @Test fun weakerOverTheWeeks() {
        var log = ""
        listOf(10.6, 10.5, 10.6, 10.5, 10.1, 10.0).forEachIndexed { i, v -> log = CrankCheck.add(log, i.toLong(), v) }
        assertTrue(CrankCheck.gettingWeaker(CrankCheck.decode(log)))
        assertFalse(CrankCheck.gettingWeaker(CrankCheck.decode("1:10.5;2:10.4;3:10.6;4:10.5;5:10.4")))
    }
}
