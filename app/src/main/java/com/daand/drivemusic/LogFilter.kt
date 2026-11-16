package com.daand.drivemusic

/**
 * What counts as a problem in the log ("Problems only" on the Logs screen, and the red lines).
 *
 * Kept pure and on its own so it can be tested against real log lines: the previous marker list was
 * built from words like "ERROR", which matched every YouTube Music state transition and missed the
 * lines that actually said something went wrong ("NOT shown", "timed out", "couldn't", "gave up").
 */
object LogFilter {

    /** Lines that describe something going wrong (a user reading the log can act on these). */
    private val PROBLEMS = listOf(
        "CRASH", "!!!",                                    // a crash, or a pause while driving
        "FAILED", "failed", "gave up", "couldn't", "could not", "can't ", "cannot ",
        "-> ERROR", "error=",                              // playback really failed (not just a state change)
        "NOT shown", "timed out", "speech failed", "nothing to hear", "unavailable", "no text-to-speech",
        "not installed", "destroyed", "stopped by the system", "disconnected from", "looks deliberate",
        // The car telling you something that needs attention. READY_TO_DRIVE / ENGINE_WARM / ICY_ROAD
        // are information, not faults, so they stay out (a text filter means naming the alert kinds).
        "OBD: alert OVERHEAT", "OBD: alert CHECK_ENGINE", "OBD: alert EARLY_CODE",
        "OBD: alert WEAK_CHARGING", "OBD: alert LOW_BATTERY", "OBD: alert THERMOSTAT",
        "OBD: alert LOW_FUEL", "early warning",
        "no position",                                     // couldn't do what was asked
    )

    /** Start/end of a drive: not a problem, but the context you need when reading one. */
    fun isDriveMark(line: String) = line.contains("=== CAR")

    fun isProblem(line: String): Boolean = PROBLEMS.any { line.contains(it) }

    /** What "Problems only" keeps. */
    fun keep(line: String, problemsOnly: Boolean) = !problemsOnly || isProblem(line) || isDriveMark(line)
}
