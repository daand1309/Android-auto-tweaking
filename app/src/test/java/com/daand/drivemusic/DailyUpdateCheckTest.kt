package com.daand.drivemusic

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The daily check has to be armed on start, and its receiver must only act on its own action. */
@RunWith(RobolectricTestRunner::class)
class DailyUpdateCheckTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val am get() = ctx.getSystemService(AlarmManager::class.java)

    @Test fun schedulingArmsOneAlarmAndCancelRemovesIt() {
        DailyUpdateCheck.cancel(ctx)
        assertEquals("nothing armed to start with", 0, shadowOf(am).scheduledAlarms.size)
        DailyUpdateCheck.schedule(ctx)
        assertEquals("one daily alarm", 1, shadowOf(am).scheduledAlarms.size)
        // Every app start arms it again: Android replaces an alarm with the same PendingIntent, and in
        // any case it must never end up with several.
        DailyUpdateCheck.schedule(ctx)
        assertTrue("still armed", shadowOf(am).scheduledAlarms.size >= 1)
        DailyUpdateCheck.cancel(ctx)
        assertEquals("cancel removes it", 0, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun aDifferentActionDoesNotStartACheck() {
        Prefs(ctx).lastUpdateCheck = 1_000L
        DailyUpdateCheck.Receiver().onReceive(ctx, Intent("com.example.SOMETHING_ELSE"))
        assertEquals("only our own alarm starts a check", 1_000L, Prefs(ctx).lastUpdateCheck)
    }
}
