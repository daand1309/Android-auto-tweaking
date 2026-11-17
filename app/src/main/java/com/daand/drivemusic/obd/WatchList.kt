package com.daand.drivemusic.obd

/**
 * Everything the app is keeping an eye on, for the "Possible issues" page behind the Fault box on
 * Home: confirmed fault codes, codes seen but not confirmed yet (one bad read is ignored), early
 * "pending" codes, and slow trends from your drives. Read-only: nothing here is ever cleared in the
 * car. Pure, unit tested.
 */
object WatchList {
    enum class Level { FAULT, CHECKING, EARLY, TREND }

    /** The fewest sightings worth a line: one read can be garbled, and then the code is not real. */
    const val MIN_SHOWN_HITS = 2

    data class Issue(val level: Level, val code: String?, val title: String, val detail: String)

    /** The catalyst test this close to its limit is worth a line. */
    const val CATALYST_NEAR = 0.85

    /** [carCount] = how many codes the car itself says it has stored (PID 01), null if unknown. */
    fun build(stored: CodeTracker, pending: CodeTracker, carReportsNow: List<String>, carCount: Int?,
              trends: List<CarTrends.Finding>, dutch: Boolean,
              /** The catalytic converter's own self-test (mode 06), latest result. */
              catalyst: OnBoardTests.Catalyst? = null): List<Issue> {
        fun what(c: String) = TroubleCodes.explanation(c, dutch).what
        val out = mutableListOf<Issue>()
        // What the car reports right now is first-hand, but only a believable list counts as confirmed:
        // one garbled answer can carry six invented codes (CarAlerts.MAX_PLAUSIBLE_CODES). The voice takes
        // the same line, so the page and the greeting agree about what is real.
        // Exactly the voice's rule (CarAlerts.countAgrees): the car's own believable count must cover the
        // list. Without it a code is only confirmed by coming back in several reads (the tracker below).
        val countAgrees = carCount != null && carCount <= CarAlerts.MAX_PLAUSIBLE_CODES && carReportsNow.size <= carCount
        val believableNow = if (countAgrees) carReportsNow else emptyList()
        val faults = (believableNow + stored.codes().filter { stored.confirmed(it) }).distinct()
        faults.forEach { c ->
            out += Issue(Level.FAULT, c, "$c: ${what(c)}",
                if (dutch) "Bevestigde storing · ${TroubleCodes.explanation(c, true).advice}"
                else "Confirmed fault · ${TroubleCodes.explanation(c, false).advice}")
        }
        // A code one garbled answer invented has exactly one sighting. The line under it says "one bad
        // read doesn't count", so the page must not count it either: two sightings is the lowest honest
        // evidence, and below that the code is not shown at all.
        val listed = faults.toMutableSet()
        stored.codes().filter { it !in listed && stored.hits(it) >= MIN_SHOWN_HITS }.forEach { c ->
            val n = stored.hits(c)
            listed += c
            out += Issue(Level.CHECKING, c, "$c: ${what(c)}",
                if (dutch) "$n× gezien, nog niet bevestigd - één foute meting telt niet"
                else "Seen $n times, not confirmed yet - one bad read doesn't count")
        }
        // Not again when the same code is already listed (a code can show up in both reads), and a pending
        // code needs the two sightings the tracker asks for before it is worth a line.
        pending.codes().filter { it !in listed && pending.hits(it) >= MIN_SHOWN_HITS }.forEach { c ->
            listed += c
            out += Issue(Level.EARLY, c, "$c: ${what(c)}",
                if (dutch) "Vroege waarschuwing: de auto twijfelt nog (nog geen lampje)"
                else "Early warning: the car isn't sure yet (no warning light)")
        }
        // The car's own catalyst test, close to its limit (or over it): long before - or after - a P0420.
        catalyst?.takeIf { !it.passes || it.closeness >= CATALYST_NEAR }?.let { c ->
            val pct = Math.round(c.closeness * 100)
            out += Issue(Level.EARLY, null,
                when {
                    !c.passes && dutch -> "Katalysator: de eigen test van de auto faalt ($pct% van de grens)"
                    !c.passes -> "Catalytic converter: the car's own test fails ($pct% of its limit)"
                    dutch -> "Katalysator: de eigen test van de auto zit op $pct% van de grens"
                    else -> "Catalytic converter: the car's own test is at $pct% of its limit"
                },
                if (dutch) "Boven 100% slaat de auto P0420 op. Laat hem bij de volgende beurt bekijken."
                else "Above 100% the car stores P0420. Worth a look at the next service.")
        }
        trends.forEach { t ->
            out += Issue(Level.TREND, null, CarTrends.message(t, dutch, butler = false),
                if (dutch) "Langzame verandering t.o.v. je eerdere ritten" else "A slow change compared with your earlier drives")
        }
        return out
    }
}
