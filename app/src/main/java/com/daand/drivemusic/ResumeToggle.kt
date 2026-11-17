package com.daand.drivemusic

import android.content.Context

/**
 * Every auto-resume button (Drive Mode, the car screen's menu and screen, the Quick Settings tile):
 * during a drive a tap counts for this drive only - a button pressed in the car must not change what
 * the next drives do (they used to switch the setting off for good). Outside a drive there is no
 * "this drive", so a tap is the setting itself. All of them show what will really happen, including
 * the app turning resuming off for the rest of a drive when your pauses look deliberate.
 * The decision is pure and unit tested; [isOnNow] / [tapNow] wire it to the app.
 */
object ResumeToggle {
    /** [thisDrive]: this drive's own choice - on, off, or null = follow the setting. */
    data class State(val setting: Boolean, val thisDrive: Boolean?)

    fun isOn(setting: Boolean, thisDrive: Boolean?) = thisDrive ?: setting

    /** In a drive: flip this drive's choice (back to null when it matches the setting); the setting
     *  itself is untouched in both directions. Parked: flip the setting. */
    fun tap(s: State, inDrive: Boolean): State {
        if (!inDrive) return State(setting = !s.setting, thisDrive = null)
        val next = !isOn(s.setting, s.thisDrive)
        return State(s.setting, if (next == s.setting) null else next)
    }

    fun isOnNow(ctx: Context) = isOn(Prefs(ctx).autoResume, DriveMonitorService.instance?.resumeThisDrive)

    /** A tap on any auto-resume button; [source] goes in the log. Returns the new on/off. */
    fun tapNow(ctx: Context, source: String): Boolean {
        val p = Prefs(ctx)
        val svc = DriveMonitorService.instance
        val inDrive = svc?.inDrive == true
        val next = tap(State(p.autoResume, svc?.resumeThisDrive), inDrive)
        p.autoResume = next.setting
        svc?.resumeThisDrive = next.thisDrive
        val on = isOnNow(ctx)
        DriveLog.log("$source: auto-resume " + when {
            !inDrive -> if (on) "setting on" else "setting off"
            on -> "on"
            else -> "off for this drive"
        })
        return on
    }
}
