package com.daand.drivemusic

/**
 * The once-a-minute "is there music?" line in the drive log (see DriveMonitorService.musicMinute), so a
 * silence shows up - and why. 26 Nov 20:36: the music stopped and nothing brought it back for 14 minutes,
 * which the log only showed by what it *didn't* say. Pure, unit tested.
 */
object MusicStatus {
    const val EVERY_MS = 60_000L

    data class Facts(
        val radio: String? = null,            // the radio station playing, if the radio is on
        val ytState: String = "NONE",         // YouTube Music's state (PLAYING, PAUSED, ...)
        val song: String? = null,             // "title - artist", if it says
        val youPaused: Boolean = false,       // paused from the car screen / Drive Mode
        val autoResumeSetting: Boolean = true,
        val autoResumeThisDrive: Boolean? = null, // false = switched off for this drive
        val resumePending: Boolean = false,
        val checkingStop: Boolean = false,    // a stop is in its grace period
        val resumeLimitReached: Boolean = false,
        val otherSound: String? = null,       // e.g. "navigation voice"
        val carScreenGone: Boolean = false,   // Android Auto dropped, drive on hold
    )

    fun line(f: Facts): String {
        if (f.radio != null) return "MUSIC: radio on - ${f.radio}"
        if (f.ytState == "PLAYING") return "MUSIC: playing" + (f.song?.let { " - $it" } ?: "")
        val why = when {
            f.carScreenGone -> "Android Auto is reconnecting"
            f.youPaused -> "you paused it"
            f.checkingStop -> "it just stopped - checking whether it comes back by itself"
            f.resumePending -> "auto-resume is about to press play"
            f.autoResumeThisDrive == false -> "auto-resume is off for this drive"
            !f.autoResumeSetting -> "auto-resume is off in settings"
            f.resumeLimitReached -> "auto-resume reached its limit (4 in 10 min)"
            else -> "nothing is bringing it back"
        }
        return "MUSIC: SILENT (YouTube Music ${f.ytState}) - $why" + (f.otherSound?.let { "; also playing: $it" } ?: "")
    }

    // --- Watchdog: the safety net under every other rule ---------------------------------------------

    /** How often the watchdog looks, and how many silent looks in a row before it acts (~20 s). */
    const val WATCH_EVERY_MS = 10_000L
    const val WATCH_SILENT_LOOKS = 2
    /** At most this many watchdog plays per [WATCH_WINDOW_MS], so it can't fight forever. */
    const val WATCH_MAX = 5
    const val WATCH_WINDOW_MS = 10 * 60_000L

    /**
     * Press play? Only after [WATCH_SILENT_LOOKS] looks in a row of silence that nothing explains (see
     * [Facts] - the same reasons the minute line gives) and while under [WATCH_MAX] in the window.
     * 27 Nov 10:03:41: a stop during our own black-ice warning, then 5 silent minutes - every rule had a reason
     * to leave it, none brought it back.
     */
    fun watchdogShouldPlay(silentLooks: Int, recentPlays: Int): Boolean =
        silentLooks >= WATCH_SILENT_LOOKS && recentPlays < WATCH_MAX

    /** Silence that nothing explains: the "nothing is bringing it back" case of [line]. */
    fun unexplainedSilence(f: Facts): Boolean =
        f.radio == null && f.ytState != "PLAYING" && f.ytState != "BUFFERING" && f.ytState != "CONNECTING" &&
            !f.carScreenGone && !f.youPaused && !f.checkingStop && !f.resumePending &&
            f.autoResumeThisDrive != false && f.autoResumeSetting && f.otherSound == null
}
