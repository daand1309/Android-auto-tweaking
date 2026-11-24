package com.daand.drivemusic.obd

/**
 * How long the optional freeze-frame read may take.
 *
 * The freeze frame is a nice-to-have: the values the car saved when it stored a fault code. It runs on
 * the OBD poll thread (after the code has been announced), so every millisecond here is a millisecond
 * of live data standing still. The questions are 020200 plus one 02XX00 per value, and each one waits
 * its timeout *plus* the link's prompt grace (see [ObdLink.GRACE_MS]) - with the timeouts as they are
 * that is 3.2 s + 7 x 2.8 s = 22.8 s worst case for an adapter that stops prompting halfway.
 *
 * So the sequence gets one budget and every question has to fit inside what is left of it. A healthy
 * adapter answers each question in tens of milliseconds, so all seven values still come back; only a
 * silent adapter gets cut short, and then the log says so.
 *
 * Pure on purpose: this arithmetic is where the stall was, so it is tested with real numbers.
 */
internal object FreezeBudget {
    /** The whole freeze-frame read, from the code question to the last value. */
    const val TOTAL_MS = 6_000L

    /** One value question. The adapter gets this much beyond the grace; enough for a real one. */
    const val PID_MS = 800L

    /** The code question ("which freeze frame is stored"). It is the one question that matters. */
    const val CODE_MS = 1_200L

    /**
     * The timeout to give the next question after [elapsedMs], or null when there is no room left for
     * one more (asking would only push the live poll past [TOTAL_MS]).
     */
    fun slice(
        elapsedMs: Long,
        capMs: Long = PID_MS,
        totalMs: Long = TOTAL_MS,
        graceMs: Long = ObdLink.GRACE_MS,
    ): Long? {
        val left = totalMs - elapsedMs.coerceAtLeast(0) - graceMs
        if (left <= 0) return null
        return minOf(capMs, left)
    }
}
