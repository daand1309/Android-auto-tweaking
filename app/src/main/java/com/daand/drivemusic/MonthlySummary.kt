package com.daand.drivemusic

import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Last month in one sentence: km, drives, fuel, money and the driving score, against the month before.
 * Said once, in the first greeting of a new month; also on the Trips & costs page. Pure, unit tested.
 */
object MonthlySummary {
    data class Month(val ym: YearMonth, val drives: Int, val km: Double, val litres: Double, val cost: Double, val score: Int?)

    fun month(trips: List<TripRecord>, ym: YearMonth, todaysPrice: Double, zone: ZoneId = ZoneId.systemDefault()): Month {
        val t = trips.filter { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) == ym }
        return Month(ym, t.size, t.sumOf { it.km }, t.sumOf { it.litres }, t.sumOf { it.cost(todaysPrice) }, TripHistory.averageScore(t))
    }

    private fun name(ym: YearMonth, dutch: Boolean) =
        ym.month.getDisplayName(java.time.format.TextStyle.FULL, if (dutch) java.util.Locale("nl") else java.util.Locale.ENGLISH)

    /** Null when there was (almost) nothing to tell. */
    fun text(m: Month, before: Month?, dutch: Boolean, butler: Boolean): String? {
        if (m.drives < 3 || m.km < 20) return null
        val km = Math.round(m.km)
        val parts = mutableListOf<String>()
        parts += if (dutch) "In ${name(m.ym, true)} reed je $km kilometer in ${m.drives} ritten" else
            (if (butler) "Your ${name(m.ym, false)}, sir: " else "Your ${name(m.ym, false)}: ") + "$km kilometres in ${m.drives} drives"
        if (m.litres >= 1) parts += if (dutch) "${Math.round(m.litres)} liter" + (if (m.cost >= 1) ", ongeveer ${Math.round(m.cost)} euro" else "")
            else "${Math.round(m.litres)} litres" + (if (m.cost >= 1) ", about ${Math.round(m.cost)} euros" else "")
        m.score?.let { s ->
            val diff = before?.score?.let { s - it }
            parts += (if (dutch) "rijstijl $s" else "driving score $s") + when {
                diff == null || diff == 0 -> ""
                diff > 0 -> if (dutch) ", $diff hoger dan ${name(before.ym, true)}" else ", up $diff on ${name(before.ym, false)}"
                else -> if (dutch) ", ${-diff} lager dan ${name(before.ym, true)}" else ", down ${-diff} on ${name(before.ym, false)}"
            }
        }
        before?.takeIf { it.km >= 20 }?.let { b ->
            val pct = Math.round((m.km - b.km) / b.km * 100).toInt()
            if (kotlin.math.abs(pct) >= 15) parts += if (dutch) (if (pct > 0) "$pct% meer kilometers dan de maand ervoor" else "${-pct}% minder kilometers dan de maand ervoor")
                else (if (pct > 0) "$pct% more driving than the month before" else "${-pct}% less driving than the month before")
        }
        return parts.joinToString(if (dutch) ", " else ", ") + "."
    }
}
