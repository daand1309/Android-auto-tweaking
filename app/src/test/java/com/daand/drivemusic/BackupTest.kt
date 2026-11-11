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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesThatAreNotABackup() {
        Prefs(ctx).importJson("""{"something":"else"}""")
    }
}
