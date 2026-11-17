package com.daand.drivemusic

/**
 * Drive Mode's auto-resume button. It is "for this drive", as the guide says: turning it off must not
 * switch the setting off for every future drive (it used to). And it must show what really happens:
 * the app itself turns resuming off for the rest of a drive when your pauses look deliberate.
 * Pure, unit tested.
 */
object ResumeToggle {
    data class State(val setting: Boolean, val offThisDrive: Boolean)

    fun isOn(setting: Boolean, offThisDrive: Boolean) = setting && !offThisDrive

    fun tap(s: State): State = when {
        !s.setting -> State(setting = true, offThisDrive = false)        // you clearly want it: switch it on
        s.offThisDrive -> State(setting = true, offThisDrive = false)    // back on for this drive
        else -> State(setting = true, offThisDrive = true)               // off, for this drive only
    }
}
