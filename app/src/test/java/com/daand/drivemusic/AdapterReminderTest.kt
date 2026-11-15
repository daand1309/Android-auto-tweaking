package com.daand.drivemusic

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.daand.drivemusic.obd.AdapterReminder
import com.daand.drivemusic.obd.AdapterReminderReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdapterReminderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    /** The car connecting should take over the screen out of the box - it's a driving app. */
    @Test fun driveModeOpensByItselfOutOfTheBox() {
        assertTrue("Drive Mode autostart must not need switching on", Prefs(ctx).autoOpenDriveMode)
    }

    @Test fun onlyAfterDrivesThatUsedTheAdapter() {
        assertTrue(AdapterReminder.shouldRemind(enabled = true, adapterUsedThisDrive = true))
        assertFalse("adapter never connected this drive", AdapterReminder.shouldRemind(true, false))
        assertFalse("switched off", AdapterReminder.shouldRemind(false, true))
    }

    @Test fun staysUntilAButtonIsTapped() {
        DriveLog.init(ctx)
        AdapterReminder.post(ctx)
        val n = nm.activeNotifications.single()
        assertTrue("pops up, not silently in the shade", nm.getNotificationChannel("adapter_reminder_v2").importance == NotificationManager.IMPORTANCE_HIGH)
        assertTrue(DriveLog.readAll().contains("adapter reminder shown (pops up)"))
        assertTrue("can't be swiped away by accident", n.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, n.notification.actions.size)
        assertTrue(n.notification.actions[0].title.contains("Taken out"))
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_TAKEN_OUT))
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(DriveLog.readAll().contains("adapter taken out"))
        assertEquals("remembered for the battery log", com.daand.drivemusic.obd.BatteryLog.ADAPTER_OUT, Prefs(ctx).adapterWhileParked)
        // "Leave it in" also clears it, and is logged as your choice.
        AdapterReminder.post(ctx)
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_LEAVE_IN))
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(DriveLog.readAll().contains("adapter left in"))
        assertEquals(com.daand.drivemusic.obd.BatteryLog.ADAPTER_IN, Prefs(ctx).adapterWhileParked)
        // Swiped away: in or out unknown (not guessed).
        AdapterReminder.post(ctx)
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_DISMISSED))
        assertEquals(com.daand.drivemusic.obd.BatteryLog.ADAPTER_UNKNOWN, Prefs(ctx).adapterWhileParked)
    }

    /** The same question is shown inside the app (home screen / Car data), so it can be answered later. */
    @Test fun alsoPendingInTheAppUntilAnswered() {
        DriveLog.init(ctx)
        AdapterReminder.post(ctx)
        assertTrue("the app should offer the same question", Prefs(ctx).adapterPromptPending)
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_TAKEN_OUT))
        assertFalse("answered: no card in the app either", Prefs(ctx).adapterPromptPending)
        // Driving again makes an old question pointless.
        AdapterReminder.post(ctx)
        AdapterReminder.clear(ctx)
        assertFalse(Prefs(ctx).adapterPromptPending)
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun saysSoWhenItCanNotBeSeen() {
        DriveLog.init(ctx)
        org.robolectric.Shadows.shadowOf(nm).setNotificationsEnabled(false)
        AdapterReminder.post(ctx)
        assertTrue(DriveLog.readAll().contains("NOT shown - notifications are off"))
    }
}
