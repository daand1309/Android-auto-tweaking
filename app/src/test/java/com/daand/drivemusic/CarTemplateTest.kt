package com.daand.drivemusic

import androidx.car.app.model.ListTemplate
import com.daand.drivemusic.car.CarTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Builds the Android Auto screen with the real car library. The library enforces Android Auto's
 * template rules when building, so a rule violation (too many titled buttons, a coloured button,
 * too many rows...) fails here instead of crashing the car screen on the road.
 */
@RunWith(RobolectricTestRunner::class)
class CarTemplateTest {

    private val noop = CarTemplate.Callbacks({}, {}, {}, {}, {})

    private fun state(playing: Boolean, resume: Boolean) = CarTemplate.State(
        nowPlaying = "Freedom — Rage Against The Machine",
        playing = playing,
        autoResume = resume,
        infoTitle = "Weather",
        infoText = "Noordwijk: 4 degrees, overcast skies, rain 61%",
    )

    @Test fun buildsWithoutBreakingAndroidAutoRules() {
        for (playing in listOf(true, false)) for (resume in listOf(true, false)) {
            val t = CarTemplate.build(state(playing, resume), noop) as ListTemplate
            val rows = t.singleList!!.items.size
            assertTrue("$rows rows exceeds the driving limit", rows <= CarTemplate.MAX_ROWS)
            assertEquals(1, t.actionStrip!!.actions.size)
        }
    }

    /** Proves the rules are really checked here: the old layout (two titled buttons) must be rejected. */
    @Test(expected = IllegalArgumentException::class)
    fun oldTwoButtonLayoutIsRejected() {
        val strip = androidx.car.app.model.ActionStrip.Builder()
            .addAction(androidx.car.app.model.Action.Builder().setTitle("Welcome").setOnClickListener {}.build())
            .addAction(androidx.car.app.model.Action.Builder().setTitle("Resume: On").setOnClickListener {}.build())
            .build()
        val list = androidx.car.app.model.ItemList.Builder()
            .addItem(androidx.car.app.model.Row.Builder().setTitle("x").build()).build()
        ListTemplate.Builder().setTitle("t").setSingleList(list).setActionStrip(strip).build()
    }
}
