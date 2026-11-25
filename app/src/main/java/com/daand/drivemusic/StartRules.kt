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

    /** Kinds of sound (see DriveMonitorService.logOtherSound) that explain a stop: someone/something talking. */
    val VOICES = setOf("navigation voice", "assistant", "call")

    /** A stop confirmed this soon after the music came back by itself (its ~1 s stop + the 3 s grace). */
    const val AFTER_RECOVERY_MS = 6_000L
    fun afterSelfRecovery(sinceRecoveredMs: Long, recovered: Boolean): Boolean =
        recovered && sinceRecoveredMs in 0 until AFTER_RECOVERY_MS

    /** YouTube Music's "Upgrade to Music Premium..." error, in Dutch or English. */
    fun needsPremium(error: String?): Boolean = error != null && error.contains("Premium", ignoreCase = true)

    /** Start from that error with stop-then-play (once per drive) instead of a plain play. */
    fun premiumReset(inError: Boolean, alreadyTried: Boolean, error: String?): Boolean =
        inError && !alreadyTried && needsPremium(error)

    /**
     * A stop confirmed within this long after our own voice ended (the music had been playing before it):
     * YouTube Music's ~1 s stop after it comes back by itself, plus the 3 s grace before a stop counts.
     */
    const val AFTER_VOICE_MS = 6_000L
    fun afterOwnVoice(sinceVoiceEndedMs: Long, wasPlayingBefore: Boolean): Boolean =
        wasPlayingBefore && sinceVoiceEndedMs in 0 until AFTER_VOICE_MS

    /** A pause with no clues that plays on by itself this soon (no resume from us) was the music app's hiccup. */
    const val SELF_RECOVER_MS = 10_000L
    fun selfRecovered(sincePauseCountedMs: Long): Boolean = sincePauseCountedMs in 0 until SELF_RECOVER_MS

    /** Turn auto-resume off for the rest of the drive because you clearly paused on purpose. */
    fun looksDeliberate(sinceResumeMs: Long, noClues: Boolean, sinceFirstStartMs: Long, afterMs: Long = DELIBERATE_AFTER_MS): Boolean =
        sinceResumeMs < 60_000 && noClues && sinceFirstStartMs >= afterMs
}
