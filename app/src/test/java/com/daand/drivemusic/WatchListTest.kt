package com.daand.drivemusic

import com.daand.drivemusic.obd.CarTrends
import com.daand.drivemusic.obd.CodeTracker
import com.daand.drivemusic.obd.WatchList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchListTest {
    private fun tracker(vararg reads: List<String>) = CodeTracker().apply {
        reads.forEachIndexed { i, codes -> update(1_000L * (i + 1), codes) }
    }

    @Test fun nothingTrackedIsEmpty() {
        assertTrue(WatchList.build(CodeTracker(), CodeTracker(), emptyList(), emptyList(), dutch = false).isEmpty())
    }

    @Test fun aCodeSeenEnoughIsAFaultAndOnceIsBeingChecked() {
        val stored = tracker(listOf("P0420", "P0300"), listOf("P0420"), listOf("P0420"))
        val list = WatchList.build(stored, CodeTracker(), emptyList(), emptyList(), dutch = false)
        assertEquals(listOf("P0420" to WatchList.Level.FAULT, "P0300" to WatchList.Level.CHECKING), list.map { it.code to it.level })
    }

    @Test fun aCodeTheCarReportsNowCountsAsAFault() {
        val list = WatchList.build(CodeTracker(), CodeTracker(), listOf("P0171"), emptyList(), dutch = false)
        assertEquals(WatchList.Level.FAULT, list.single().level)
    }

    @Test fun pendingCodesAndTrendsAreEarlyWarnings() {
        val pending = tracker(listOf("P0133"))
        val trend = CarTrends.Finding(CarTrends.Kind.CHARGING, 13.1, 13.8)
        val list = WatchList.build(CodeTracker(), pending, emptyList(), listOf(trend), dutch = false)
        assertEquals(listOf(WatchList.Level.EARLY, WatchList.Level.TREND), list.map { it.level })
        assertEquals("P0133", list[0].code)
    }

    @Test fun faultsComeFirst() {
        val stored = tracker(listOf("P0300"))
        val list = WatchList.build(stored, CodeTracker(), listOf("P0420"), emptyList(), dutch = false)
        assertEquals(WatchList.Level.FAULT, list.first().level)
    }
}
