package com.daand.drivemusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun backupRestoresEverySettingWithItsType() {
        val p = Prefs(ctx)
        p.greetingName = "Mr. Smith"
        p.greetingDutch = true
        p.resumeDelaySec = 14
        p.speechRate = 1.15f
        p.weatherLat = 52.1
        p.lastDrive = "should not be backed up"
        val json = p.exportJson()
        assertFalse("run-time state must not be in the backup", json.contains("should not be backed up"))

        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("", Prefs(ctx).greetingName)

        val restored = Prefs(ctx).importJson(json)
        assertTrue(restored >= 5)
        val r = Prefs(ctx)
        assertEquals("Mr. Smith", r.greetingName)
        assertEquals(true, r.greetingDutch)
        assertEquals(14, r.resumeDelaySec)
        assertEquals(1.15f, r.speechRate, 0.0001f)
        assertEquals(52.1, r.weatherLat, 0.001)
        assertEquals("", r.lastDrive)
    }

    @Test fun damagedBackupCannotCrashTheApp() {
        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        val json = """{"app":"drive-music","version":1,"settings":{
            "auto_start":{"t":"string","v":"oops"},
            "start_delay":{"t":"int","v":99999},
            "speech_rate":{"t":"float","v":50},
            "greeting":{"t":"bool","v":"not a bool"},
            "last_drive":{"t":"string","v":"runtime"},
            "something_new":{"t":"bool","v":true},
            "car_name":{"t":"string","v":"ASX"}
        }}"""
        Prefs(ctx).importJson(json)
        val r = Prefs(ctx)
        assertEquals(true, r.autoStart)           // wrong type ignored -> still the default, no crash
        assertEquals(30, r.startDelaySec)         // absurd number clamped to the maximum
        assertEquals(1.4f, r.speechRate, 0.0001f) // clamped
        assertEquals("", r.lastDrive)             // run-time state never restored
        assertEquals("ASX", r.carName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesThatAreNotABackup() {
        Prefs(ctx).importJson("""{"something":"else"}""")
    }
}
