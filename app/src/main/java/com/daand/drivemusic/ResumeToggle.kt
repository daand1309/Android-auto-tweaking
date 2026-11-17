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
    data class State(val setting: Boolean, val offThisDrive: Boolean)

    fun isOn(setting: Boolean, offThisDrive: Boolean) = setting && !offThisDrive

    fun tap(s: State, inDrive: Boolean): State = when {
        !inDrive -> State(setting = !s.setting, offThisDrive = false)
        !s.setting -> State(setting = true, offThisDrive = false)        // you clearly want it: switch it on
        s.offThisDrive -> State(setting = true, offThisDrive = false)    // back on for this drive
        else -> State(setting = true, offThisDrive = true)               // off, for this drive only
    }

    fun isOnNow(ctx: Context) = isOn(Prefs(ctx).autoResume, DriveMonitorService.instance?.resumeOffThisDrive == true)

    /** A tap on any auto-resume button; [source] goes in the log. Returns the new on/off. */
    fun tapNow(ctx: Context, source: String): Boolean {
        val p = Prefs(ctx)
        val svc = DriveMonitorService.instance
        val inDrive = svc?.inDrive == true
        val next = tap(State(p.autoResume, svc?.resumeOffThisDrive == true), inDrive)
        p.autoResume = next.setting
        svc?.resumeOffThisDrive = next.offThisDrive
        val on = isOnNow(ctx)
        DriveLog.log("$source: auto-resume " + when {
            !inDrive -> if (on) "setting on" else "setting off"
            on -> "on"
            else -> "off for this drive"
        })
        return on
    }
}
