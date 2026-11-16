package com.daand.drivemusic

/**
 * Everything that is waiting for you, in one list for the home screen's "Needs you" card (it used to be
 * separate warning rows in different places). Each item points at the grid icon that gets an orange dot.
 * Pure, unit tested.
 */
object NeedsYou {
    enum class Kind { MUSIC_APP, SETUP, ADAPTER, UPDATE, FAULT, FULL_SCREEN }

    data class State(
        val musicAppMissing: Boolean,
        val setupStepsLeft: Int,
        val adapterQuestion: Boolean,
        val updateVersion: String?,
        val faultCode: String?,
        val fullScreenMissing: Boolean,
    )

    data class Item(val kind: Kind, val text: String, val featureId: String?)

    /** Most urgent first; empty when nothing waits (then the card isn't shown at all). */
    fun items(s: State): List<Item> = buildList {
        if (s.musicAppMissing) add(Item(Kind.MUSIC_APP, "Music app not found - pick one", "music"))
        if (s.adapterQuestion) add(Item(Kind.ADAPTER, "OBD adapter still in the car?", "car_data"))
        s.faultCode?.let { add(Item(Kind.FAULT, "Fault code $it - what it means", "car_data")) }
        if (s.setupStepsLeft > 0) add(Item(Kind.SETUP,
            "Setup: ${s.setupStepsLeft} ${if (s.setupStepsLeft == 1) "step" else "steps"} left", "setup"))
        if (s.fullScreenMissing) add(Item(Kind.FULL_SCREEN, "Let Drive Mode open by itself", "drive_mode"))
        s.updateVersion?.let { add(Item(Kind.UPDATE, "Update $it ready to install", "updates")) }
    }

    /** The grid icons that get an orange dot. */
    fun dotted(items: List<Item>): Set<String> = items.mapNotNull { it.featureId }.toSet()
}
