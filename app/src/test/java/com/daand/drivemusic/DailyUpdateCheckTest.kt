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

    @Test fun oneAlarmIsArmedAndCancelTakesItAway() {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = shadowOf(am)
        DailyUpdateCheck.cancel(ctx)
        assertTrue("nothing armed to start with", shadow.scheduledAlarms.isEmpty())
        DailyUpdateCheck.schedule(ctx)
        assertTrue("one daily alarm is armed", shadow.scheduledAlarms.isNotEmpty())
        DailyUpdateCheck.cancel(ctx)
        assertTrue("cancel removes it", shadow.scheduledAlarms.isEmpty())
    }

    @Test fun aDifferentActionDoesNotStartACheck() {
        Prefs(ctx).lastUpdateCheck = 1234L
        DailyUpdateCheck.Receiver().onReceive(ctx, Intent("com.example.SOMETHING_ELSE"))
        assertEquals("only our own alarm starts a check", 1234L, Prefs(ctx).lastUpdateCheck)
    }
}
