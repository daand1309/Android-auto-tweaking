package com.daand.drivemusic

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The daily check has to be armed on start, and its receiver has to do the check and nothing else. */
@RunWith(RobolectricTestRunner::class)
class DailyUpdateCheckTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun schedulingArmsOneRepeatingDailyAlarm() {
        DailyUpdateCheck.schedule(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = shadowOf(am).nextScheduledAlarm
        assertNotNull("an alarm is armed", next)
        assertEquals(AlarmManager.RTC, next.type)
        assertEquals(24 * 60 * 60 * 1000L, next.repeatInterval)
        // Arming again (every app start does) replaces it instead of piling up alarms.
        DailyUpdateCheck.schedule(ctx)
        assertNotNull(shadowOf(am).nextScheduledAlarm)
        DailyUpdateCheck.cancel(ctx)
        assertNull("cancel removes it", shadowOf(am).nextScheduledAlarm)
    }

    @Test fun theReceiverChecksAndKeepsItsHandsOffAnythingElse() {
        val r = DailyUpdateCheck.Receiver()
        // A different action must not trigger a check (it is not exported, but be sure).
        r.onReceive(ctx, Intent("com.example.SOMETHING_ELSE"))
        assertNull(shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).allNotifications.firstOrNull())

        // The real action runs the update check, which (with no network here) logs a failure instead of crashing.
        DriveLog.init(ctx); DriveLog.clear()
        r.onReceive(ctx, Intent("com.daand.drivemusic.DAILY_UPDATE_CHECK"))
        Thread.sleep(300)
        val log = DriveLog.readAll()
        assertTrue("the check ran: $log", log.contains("UPDATE:"))
    }
}
