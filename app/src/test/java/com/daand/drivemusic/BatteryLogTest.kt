package com.daand.drivemusic

import com.daand.drivemusic.obd.BatteryLog
import com.daand.drivemusic.obd.BatteryLog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLogTest {
    private fun e(v: Double, parkedH: Int?, adapter: Int) = Entry(0L, v, parkedH?.times(60), adapter)

    @Test fun showsWhetherTheAdapterDrainsTheBattery() {
        val noDrain = listOf(e(11.72, 14, BatteryLog.ADAPTER_IN), e(11.70, 20, BatteryLog.ADAPTER_IN),
            e(11.74, 15, BatteryLog.ADAPTER_OUT), e(11.71, 30, BatteryLog.ADAPTER_OUT))
        assertFalse(BatteryLog.compare(noDrain)!!.drainSuspected)
        val drain = listOf(e(11.45, 40, BatteryLog.ADAPTER_IN), e(11.52, 30, BatteryLog.ADAPTER_IN),
            e(11.72, 40, BatteryLog.ADAPTER_OUT), e(11.70, 30, BatteryLog.ADAPTER_OUT))
        assertTrue(BatteryLog.compare(drain)!!.drainSuspected)
        // Too little to judge: short stops don't count; unknown answers don't count; need 2 of each.
        assertNull(BatteryLog.compare(listOf(e(11.7, 1, BatteryLog.ADAPTER_IN), e(11.7, 2, BatteryLog.ADAPTER_IN),
            e(11.7, 1, BatteryLog.ADAPTER_OUT), e(11.7, 2, BatteryLog.ADAPTER_OUT))))
        assertNull(BatteryLog.compare(listOf(e(11.7, 20, BatteryLog.ADAPTER_UNKNOWN), e(11.7, 20, BatteryLog.ADAPTER_IN))))
    }

    @Test fun savedAndReadBackSafely() {
        val list = listOf(Entry(1_700_000_000_000L, 11.72, 845, BatteryLog.ADAPTER_IN), Entry(1_700_100_000_000L, 12.31, null, BatteryLog.ADAPTER_UNKNOWN))
        assertEquals(list, BatteryLog.decode(BatteryLog.encode(list)))
        assertTrue(BatteryLog.decode("garbage;1|x|y|z;2|99|5|1").isEmpty())   // nonsense and impossible volts dropped
        assertEquals(60, BatteryLog.encode(List(80) { Entry(it.toLong(), 12.0, 60, 1) }).split(';').size)
    }

    @Test fun readableLines() {
        assertEquals("parked 14 h", BatteryLog.parkedText(14 * 60 + 5, false))
        assertEquals("parked 35 min", BatteryLog.parkedText(35, false))
        assertEquals("parked 3 days", BatteryLog.parkedText(3 * 24 * 60 + 100, false))
        assertEquals("adapter left in", BatteryLog.adapterText(BatteryLog.ADAPTER_IN, false))
    }
}
