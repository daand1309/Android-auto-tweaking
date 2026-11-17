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
        assertTrue(WatchList.build(CodeTracker(), CodeTracker(), emptyList(), carCount = null, trends = emptyList(), dutch = false).isEmpty())
    }

    @Test fun aCodeSeenEnoughIsAFaultAndOneSeenTwiceIsBeingChecked() {
        // Seen once is not listed at all (see aCodeSeenOnceIsNotAnIssueAtAll); twice is "being checked".
        val stored = tracker(listOf("P0420", "P0300"), listOf("P0420", "P0300"), listOf("P0420"))
        val list = WatchList.build(stored, CodeTracker(), emptyList(), carCount = null, trends = emptyList(), dutch = false)
        assertEquals(listOf("P0420" to WatchList.Level.FAULT, "P0300" to WatchList.Level.CHECKING), list.map { it.code to it.level })
    }

    @Test fun aCodeTheCarReportsNowCountsAsAFault() {
        val list = WatchList.build(CodeTracker(), CodeTracker(), listOf("P0171"), carCount = 1, trends = emptyList(), dutch = false)
        assertEquals(WatchList.Level.FAULT, list.single().level)
    }

    @Test fun pendingCodesAndTrendsAreEarlyWarnings() {
        val pending = tracker(listOf("P0133"), listOf("P0133"))
        val trend = CarTrends.Finding(CarTrends.Kind.CHARGING, 13.1, 13.8)
        val list = WatchList.build(CodeTracker(), pending, emptyList(), carCount = null, trends = listOf(trend), dutch = false)
        assertEquals(listOf(WatchList.Level.EARLY, WatchList.Level.TREND), list.map { it.level })
        assertEquals("P0133", list[0].code)
    }

    @Test fun faultsComeFirst() {
        val stored = tracker(listOf("P0300"))
        val list = WatchList.build(stored, CodeTracker(), listOf("P0420"), carCount = 1, trends = emptyList(), dutch = false)
        assertEquals(WatchList.Level.FAULT, list.first().level)
    }

    @Test fun aGarbledListIsNotAConfirmedFault() {
        // More codes than one real answer can carry (CarAlerts.MAX_PLAUSIBLE_CODES = 8): they must not
        // appear as "Confirmed fault" rows, while a code that really keeps coming back still does.
        val junk = listOf("P0AAA", "P1BBB", "P2CCC", "P3DDD", "U1111", "C2222", "B0123", "P0D4E", "U2F00", "P0A11")
        assertTrue(WatchList.build(CodeTracker(), CodeTracker(), junk, carCount = 10, trends = emptyList(), dutch = false).isEmpty())
        val stored = tracker(listOf("P0420"), listOf("P0420"), listOf("P0420"))
        val list = WatchList.build(stored, CodeTracker(), junk, carCount = 10, trends = emptyList(), dutch = false)
        assertEquals(listOf("P0420" to WatchList.Level.FAULT), list.map { it.code to it.level })
    }

    @Test fun aCodeSeenOnceIsNotAnIssueAtAll() {
        // One garbled answer invents codes; the page's own line says "one bad read doesn't count",
        // so a single sighting is not listed - not even as "being checked".
        val stored = tracker(listOf("P0420", "P0ABC", "P02FF"), listOf("P0420"), listOf("P0420"))
        val list = WatchList.build(stored, CodeTracker(), emptyList(), carCount = null, trends = emptyList(), dutch = false)
        assertEquals(listOf("P0420"), list.map { it.code })
        assertEquals(WatchList.Level.FAULT, list.single().level)
        val twice = tracker(listOf("P0442"), listOf("P0442"))
        val checking = WatchList.build(twice, CodeTracker(), emptyList(), carCount = null, trends = emptyList(), dutch = false).single()
        assertEquals(WatchList.Level.CHECKING, checking.level)
        assertTrue("no \"once\" any more", checking.detail.contains("Seen 2 times"))
    }

    @Test fun theSameCodeIsListedOnceEvenWhenBothReadsSawIt() {
        // A stored and a pending read can both carry the same code; it must not appear twice.
        val stored = tracker(listOf("P0442"), listOf("P0442"))
        val pending = tracker(listOf("P0442"), listOf("P0442"))
        val list = WatchList.build(stored, pending, emptyList(), carCount = null, trends = emptyList(), dutch = false)
        assertEquals(listOf("P0442" to WatchList.Level.CHECKING), list.map { it.code to it.level })
    }

    @Test fun aSinglePendingSightingIsNotAnEarlyWarning() {
        // The alert needs the code to come back before it is spoken; the page must agree.
        val once = tracker(listOf("P0133"))
        assertTrue(WatchList.build(CodeTracker(), once, emptyList(), carCount = null, trends = emptyList(), dutch = false).isEmpty())
        val twice = tracker(listOf("P0133"), listOf("P0133"))
        assertEquals(WatchList.Level.EARLY,
            WatchList.build(CodeTracker(), twice, emptyList(), carCount = null, trends = emptyList(), dutch = false).single().level)
    }

    @Test fun liveCodesNeedTheCarsOwnCountLikeTheVoice() {
        val garbage = listOf("P1234", "P2345", "P3456", "P0101", "P0202", "P0303")
        fun faults(count: Int?) = WatchList.build(CodeTracker(), CodeTracker(), garbage, carCount = count,
            trends = emptyList(), dutch = false).count { it.level == WatchList.Level.FAULT }
        assertEquals("a count of 127 backs nothing", 0, faults(127))
        assertEquals("no count, no backing", 0, faults(null))
        assertEquals("a count smaller than the list doesn't back it", 0, faults(2))
        assertEquals("the car's own matching count does", 6, faults(6))
    }
}
