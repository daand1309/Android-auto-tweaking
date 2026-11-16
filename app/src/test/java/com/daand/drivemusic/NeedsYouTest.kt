package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsYouTest {
    private val none = NeedsYou.State(musicAppMissing = false, setupStepsLeft = 0, adapterQuestion = false,
        updateVersion = null, faultCode = null, fullScreenMissing = false)
    private val all = NeedsYou.State(musicAppMissing = true, setupStepsLeft = 2, adapterQuestion = true,
        updateVersion = "1.220", faultCode = "P0420", fullScreenMissing = true)

    @Test fun nothingPendingMeansNoCard() {
        assertTrue(NeedsYou.items(none).isEmpty())
    }

    @Test fun orderIsMostUrgentFirst() {
        assertEquals(listOf(NeedsYou.Kind.MUSIC_APP, NeedsYou.Kind.ADAPTER, NeedsYou.Kind.FAULT, NeedsYou.Kind.SETUP,
            NeedsYou.Kind.FULL_SCREEN, NeedsYou.Kind.UPDATE), NeedsYou.items(all).map { it.kind })
    }

    @Test fun textSaysWhatToDo() {
        assertEquals("Setup: 1 step left", NeedsYou.items(none.copy(setupStepsLeft = 1)).single().text)
        assertEquals("Setup: 2 steps left", NeedsYou.items(none.copy(setupStepsLeft = 2)).single().text)
        assertTrue(NeedsYou.items(none.copy(faultCode = "P0420")).single().text.startsWith("Fault code P0420"))
        assertEquals("Update 1.220 ready to install", NeedsYou.items(none.copy(updateVersion = "1.220")).single().text)
    }

    @Test fun dotsFollowTheItems() {
        val items = NeedsYou.items(none.copy(adapterQuestion = true, updateVersion = "1.220"))
        assertEquals(setOf("car_data", "updates"), NeedsYou.dotted(items))
    }
}
