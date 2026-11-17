package com.daand.drivemusic.obd

/**
 * Everything the app is keeping an eye on, for the "Possible issues" page behind the Fault box on
 * Home: confirmed fault codes, codes seen but not confirmed yet (one bad read is ignored), early
 * "pending" codes, and slow trends from your drives. Read-only: nothing here is ever cleared in the
 * car. Pure, unit tested.
 */
object WatchList {
    enum class Level { FAULT, CHECKING, EARLY, TREND }

    data class Issue(val level: Level, val code: String?, val title: String, val detail: String)

    fun build(stored: CodeTracker, pending: CodeTracker, carReportsNow: List<String>,
              trends: List<CarTrends.Finding>, dutch: Boolean): List<Issue> {
        fun what(c: String) = TroubleCodes.explanation(c, dutch).what
        val out = mutableListOf<Issue>()
        val faults = (carReportsNow + stored.codes().filter { stored.confirmed(it) }).distinct()
        faults.forEach { c ->
            out += Issue(Level.FAULT, c, "$c: ${what(c)}",
                if (dutch) "Bevestigde storing · ${TroubleCodes.explanation(c, true).advice}"
                else "Confirmed fault · ${TroubleCodes.explanation(c, false).advice}")
        }
        stored.codes().filter { it !in faults }.forEach { c ->
            val n = stored.hits(c)
            out += Issue(Level.CHECKING, c, "$c: ${what(c)}",
                if (dutch) "$n× gezien, nog niet bevestigd - één foute meting telt niet"
                else "Seen ${if (n == 1) "once" else "$n times"}, not confirmed yet - one bad read doesn't count")
        }
        pending.codes().filter { it !in faults }.forEach { c ->
            out += Issue(Level.EARLY, c, "$c: ${what(c)}",
                if (dutch) "Vroege waarschuwing: de auto twijfelt nog (nog geen lampje)"
                else "Early warning: the car isn't sure yet (no warning light)")
        }
        trends.forEach { t ->
            out += Issue(Level.TREND, null, CarTrends.message(t, dutch, butler = false),
                if (dutch) "Langzame verandering t.o.v. je eerdere ritten" else "A slow change compared with your earlier drives")
        }
        return out
    }
}
