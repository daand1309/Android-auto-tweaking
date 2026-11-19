package com.daand.drivemusic

import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class MonthlySummaryTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun trip(ym: YearMonth, day: Int, km: Double, score: Int) =
        TripRecord(ym.atDay(day).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli(), 20, km, km * 0.08, score, 90, price = 2.05)

    @Test fun lastMonthInOneSentence() {
        val oct = YearMonth.of(2026, 10); val sep = YearMonth.of(2026, 9)
        val trips = (1..20).map { trip(oct, it, 30.0, 88) } + (1..10).map { trip(sep, it, 40.0, 84) }
        val m = MonthlySummary.month(trips, oct, 2.0, zone)
        val before = MonthlySummary.month(trips, sep, 2.0, zone)
        assertEquals("Your October, sir: 600 kilometres in 20 drives, 48 litres, about 98 euros, driving score 88, up 4 on September, 50% more driving than the month before.",
            MonthlySummary.text(m, before, dutch = false, butler = true))
        assertEquals("In oktober reed je 600 kilometer in 20 ritten, 48 liter, ongeveer 98 euro, rijstijl 88, 4 hoger dan september, 50% meer kilometers dan de maand ervoor.",
            MonthlySummary.text(m, before, dutch = true, butler = false))
    }

    @Test fun aQuietMonthSaysNothing() {
        val nov = YearMonth.of(2026, 11)
        assertNull(MonthlySummary.text(MonthlySummary.month(listOf(trip(nov, 3, 5.0, 90)), nov, 2.0, zone), null, false, false))
    }
}
