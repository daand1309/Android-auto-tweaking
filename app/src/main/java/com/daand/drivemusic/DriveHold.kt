package com.daand.drivemusic

/**
 * Android Auto dropped - did you leave the car, or did the head unit just reboot (stall + restart:
 * the starter motor's voltage dip resets the radio)? Decides whether to keep the drive going.
 * Pure logic, unit tested.
 */
object DriveHold {
    /** What lastCarState says while we wait to see if Android Auto comes back. */
    const val STATE = "Android Auto reconnecting"

    /** With an OBD adapter: the car's computer silent this long = ignition off = you left. */
    const val IGNITION_OFF_AFTER_MS = 15_000L
    /** With an OBD adapter and the ignition still on: wait this long for the head unit to come back. */
    const val MAX_HOLD_WITH_OBD_MS = 5 * 60_000L
    /** No OBD adapter: no way to know, so wait this long. Tests set it lower. */
    @Volatile var holdWithoutObdMs = 90_000L

    sealed class Decision {
        object Hold : Decision()
        data class End(val reason: String) : Decision()
    }

    /**
     * [obdConnected]: the adapter is talking to us (it has its own Bluetooth link to the phone, so it
     * keeps working while the radio reboots). [ecuSilentMs]: how long the car's computer hasn't
     * answered - it only answers with the ignition on.
     */
    fun decide(sinceDropMs: Long, obdConnected: Boolean, ecuSilentMs: Long): Decision = when {
        obdConnected && ecuSilentMs >= IGNITION_OFF_AFTER_MS -> Decision.End("ignition is off")
        obdConnected && sinceDropMs >= MAX_HOLD_WITH_OBD_MS -> Decision.End("Android Auto didn't come back")
        obdConnected -> Decision.Hold
        sinceDropMs >= holdWithoutObdMs -> Decision.End("Android Auto didn't come back")
        else -> Decision.Hold
    }
}
