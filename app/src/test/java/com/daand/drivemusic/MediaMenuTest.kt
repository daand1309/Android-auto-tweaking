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
        assertEquals("The weather, sir: 4 degrees, overcast, 20% rain.", MediaMenu.weatherLine("4° · overcast · 20% rain", false, true))
        assertEquals("Het weer: -2 graden, sneeuw.", MediaMenu.weatherLine("-2° · sneeuw", true, false))
        assertTrue(MediaMenu.noNewsLine(false, true).contains("sir"))
    }

    // ------------------------------------------------------------------ red team

    @Test fun voiceSearchPassesOnlyCleanTextHints() {
        val evil = android.os.Bundle().apply {
            putString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, "Queen\n<speak>x</speak>")
            putString("evil.key", "x".repeat(100_000))
            putParcelable("evil.parcel", android.content.Intent("boom"))
        }
        val clean = MediaMenu.cleanSearchExtras(evil)
        assertEquals(setOf(android.provider.MediaStore.EXTRA_MEDIA_ARTIST), clean.keySet())
        assertEquals("Queen speak x /speak", clean.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST))
        assertTrue(MediaMenu.cleanSearchExtras(null).isEmpty)
    }

    @Test fun oneSpokenMenuItemAtATime() {
        assertTrue(MediaMenu.claimVoice())
        assertFalse("second tap while speaking is ignored", MediaMenu.claimVoice())
        MediaMenu.releaseVoice()
        assertTrue(MediaMenu.claimVoice())
        MediaMenu.releaseVoice()
    }
}
