package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FmTipTest {
    private val txs by lazy { FmTip.parse(File("src/main/assets/fm_transmitters.tsv").readText()) }
    private val noordwijk = 52.24 to 4.44
    private val leiden = 52.16 to 4.49

    @Test fun frequenciesAreRead() {
        assertEquals(107.9, FmTip.parseMhz("107,9"))
        assertEquals(88.0, FmTip.parseMhz(" 88 "))
        assertNull("160 isn't FM", FmTip.parseMhz("160.00"))
        assertNull(FmTip.parseMhz("abc"))
    }

    @Test fun theShippedListIsComplete() {
        assertTrue("most of the ~780 Dutch transmitters: ${txs.size}", txs.size > 700)
        assertTrue(txs.all { it.lat in 50.7..53.7 && it.lon in 3.2..7.3 })   // all inside the Netherlands
    }

    @Test fun aLocalStationsFrequencyIsBusyAroundNoordwijk() {
        // Radio West Oegstgeest 88.0 and Bo Radio Noordwijk 105.3 are right there.
        val west = FmTip.verdict(88.0, listOf(noordwijk), txs)
        assertTrue("88.0 near Noordwijk: ${FmTip.describe(west, false)}", west.score >= 0.7)
        assertEquals("Radio West", west.clashes.first().first.station)
        assertTrue(FmTip.verdict(105.3, listOf(noordwijk), txs).score >= 0.7)
    }

    @Test fun theBestPicksAreQuietAndApart() {
        val picks = FmTip.best(listOf(noordwijk, leiden), txs)
        assertEquals(3, picks.size)
        assertTrue(picks.joinToString { FmTip.describe(it, false) }, picks.all { it.score < 0.5 })
        // ...and far quieter than the band's top end, which is busy here (a common "just pick 107.9").
        assertTrue(FmTip.verdict(107.9, listOf(noordwijk, leiden), txs).score > 4 * picks.last().score)
        for (a in picks) for (b in picks) if (a !== b) assertTrue(kotlin.math.abs(a.mhz - b.mhz) >= 0.35)
        // None of them is a frequency a station within 30 km uses.
        for (p in picks) assertTrue(txs.none { it.mhz == p.mhz && FuelPrices.distanceKm(noordwijk.first, noordwijk.second, it.lat, it.lon) < 30 })
    }
}
