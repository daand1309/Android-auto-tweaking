package com.daand.drivemusic

import com.daand.drivemusic.car.MediaMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaMenuTest {
    @Test fun onlyAndroidAutoTheSystemAndUsMayOpenTheMenu() {
        val me = 10123
        assertTrue(MediaMenu.trustedCaller("com.google.android.projection.gearhead", 10050, me))
        assertTrue(MediaMenu.trustedCaller("anything", me, me))                       // ourselves
        assertTrue(MediaMenu.trustedCaller("android", android.os.Process.SYSTEM_UID, me))
        assertFalse(MediaMenu.trustedCaller("com.random.app", 10777, me))            // other apps: no
    }

    @Test fun menuShowsControlsAndCurrentSettings() {
        val items = MediaMenu.items(monitorRunning = true, autoResume = false, weather = "4° · overcast · 20% rain", butler = true)
        assertEquals(listOf(MediaMenu.PLAY, MediaMenu.WELCOME, MediaMenu.RESUME, MediaMenu.WEATHER, MediaMenu.NEWS), items.map { it.first })
        assertEquals("↻  Auto-resume: Off", items.first { it.first == MediaMenu.RESUME }.second)
        assertEquals("4° · overcast · 20% rain", items.first { it.first == MediaMenu.WEATHER }.third)
        assertTrue(items.first { it.first == MediaMenu.WELCOME }.second.startsWith("🎩"))
    }

    @Test fun withoutTheMonitorItSaysWhatToDo() {
        val items = MediaMenu.items(monitorRunning = false, autoResume = true, weather = null, butler = false)
        assertEquals(1, items.size)
        assertTrue(items.single().second.contains("Open Drive Music on your phone"))
    }

    @Test fun butlerWordingInTheMenu() {
        assertEquals("The weather, sir: 4° · overcast.", MediaMenu.weatherLine("4° · overcast", false, true))
        assertTrue(MediaMenu.noNewsLine(false, true).contains("sir"))
    }
}
