package com.daand.drivemusic

/**
 * The first moments after the app starts the music. YouTube Music (the Morphe build) sometimes stops
 * itself a second or two after each play while it settles - four times in 30 s on the 17 Nov 18:14
 * drive, with nothing else playing. Those stops are not you. Pure, unit tested.
 */
object StartRules {
    /** How long after the first start the music counts as "still settling". */
    const val SETTLE_WINDOW_MS = 45_000L
    /** DriveFlowTest.musicStoppingRightAfterItStartedIsStartedAgainQuickly asserts "n of 3": change both. */
    const val MAX_QUICK_RESTARTS = 3
    /** Paused again right after an auto-resume only "looks deliberate" once this far into the music. */
    const val DELIBERATE_AFTER_MS = 60_000L

    /** Press play again in 1.5 s, not counted as a pause. */
    fun quickRestart(sinceFirstStartMs: Long, sinceLastStartMs: Long, restartsUsed: Int, bounceWindowMs: Long): Boolean =
        bounceWindowMs > 0 && sinceLastStartMs < bounceWindowMs && sinceFirstStartMs < SETTLE_WINDOW_MS && restartsUsed < MAX_QUICK_RESTARTS

    /** Turn auto-resume off for the rest of the drive because you clearly paused on purpose. */
    fun looksDeliberate(sinceResumeMs: Long, noClues: Boolean, sinceFirstStartMs: Long, afterMs: Long = DELIBERATE_AFTER_MS): Boolean =
        sinceResumeMs < 60_000 && noClues && sinceFirstStartMs >= afterMs
}
