package com.daand.drivemusic.obd

/**
 * Is the car switched off? The car's computer only answers questions while the ignition is on, so an
 * adapter that we still talk to but that gets no answers for a while has a switched-off car behind it.
 *
 * This matters because Android Auto normally drops when you switch off, but not always: the head unit
 * can stay powered (accessory, door not opened yet, radio still on) and then nothing else would tell
 * us the drive is over. A stop/start engine at the lights is not a false positive: the computer keeps
 * answering with the ignition on, even with the engine stopped. Pure, unit tested.
 */
object Ignition {
    /**
     * The car's computer quiet this long while the adapter still answers = the ignition is off.
     * Long enough to ride out a slow read or a momentarily busy bus, short enough to notice quickly.
     */
    const val OFF_AFTER_MS = 60_000L

    /** [ecuSilentMs] = how long the car's computer hasn't answered (see [ObdManager.ecuSilentMs]). */
    fun off(adapterConnected: Boolean, ecuSilentMs: Long): Boolean =
        adapterConnected && ecuSilentMs >= OFF_AFTER_MS

    /** "Car switched off (3 min)" / "Auto uit (3 min)" - for the status lines. */
    fun text(silentMs: Long, dutch: Boolean): String {
        val mins = (silentMs / 60_000L).coerceAtLeast(1)
        return if (dutch) "Auto uit · al $mins min" else "Car switched off · $mins min"
    }
}
