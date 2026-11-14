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

    @Test fun onlyAfterDrivesThatUsedTheAdapter() {
        assertTrue(AdapterReminder.shouldRemind(enabled = true, adapterUsedThisDrive = true))
        assertFalse("adapter never connected this drive", AdapterReminder.shouldRemind(true, false))
        assertFalse("switched off", AdapterReminder.shouldRemind(false, true))
    }

    @Test fun staysUntilAButtonIsTapped() {
        DriveLog.init(ctx)
        AdapterReminder.post(ctx)
        val n = nm.activeNotifications.single()
        assertTrue("can't be swiped away by accident", n.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, n.notification.actions.size)
        assertTrue(n.notification.actions[0].title.contains("Taken out"))
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_TAKEN_OUT))
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(DriveLog.readAll().contains("adapter taken out"))
        // "Leave it in" also clears it, and is logged as your choice.
        AdapterReminder.post(ctx)
        AdapterReminderReceiver().onReceive(ctx, Intent(AdapterReminder.ACTION_LEAVE_IN))
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(DriveLog.readAll().contains("adapter left in"))
    }
}
