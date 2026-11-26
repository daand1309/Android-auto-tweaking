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
}
