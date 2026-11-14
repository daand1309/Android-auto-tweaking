package com.daand.drivemusic.obd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daand.drivemusic.DriveLog
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.R

/**
 * After a drive with the OBD adapter: "Take the adapter out" - it stays until you tap a button, so
 * you're aware of it (the adapter draws a little power while the car is parked).
 */
object AdapterReminder {
    private const val CHANNEL = "adapter_reminder"
    private const val ID = 13
    const val ACTION_TAKEN_OUT = "com.daand.drivemusic.ADAPTER_TAKEN_OUT"
    const val ACTION_LEAVE_IN = "com.daand.drivemusic.ADAPTER_LEAVE_IN"

    /** Only after drives where the adapter was really connected, and only if the reminder is on. Pure. */
    fun shouldRemind(enabled: Boolean, adapterUsedThisDrive: Boolean) = enabled && adapterUsedThisDrive

    fun post(ctx: Context) {
        val p = Prefs(ctx)
        val dutch = p.greetingDutch
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, if (dutch) "Herinnering OBD-adapter" else "OBD adapter reminder",
            NotificationManager.IMPORTANCE_DEFAULT))
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, AdapterReminderReceiver::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (dutch) "Haal de OBD-adapter eruit" else "Take the OBD adapter out")
            .setContentText(if (dutch) "Hij gebruikt een beetje stroom terwijl de auto stilstaat. Tik op een knop."
                else "It draws a little power while the car is parked. Tap a button below.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (dutch)
                "De adapter gebruikt een beetje stroom terwijl de auto stilstaat. Haal hem eruit (zeker als de auto langer blijft staan) en tik op een knop."
                else "The adapter draws a little power while the car is parked. Unplug it (especially if the car will stand for a while), then tap a button."))
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_answer_yes, if (dutch) "Eruit gehaald ✓" else "Taken out ✓", action(ACTION_TAKEN_OUT, 31))
            .addAction(R.drawable.ic_answer_no, if (dutch) "Laat zitten" else "Leave it in", action(ACTION_LEAVE_IN, 32))
            // Swiped away anyway (Android 14+ allows it): counts as seen.
            .setDeleteIntent(action(ACTION_LEAVE_IN, 33))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
            DriveLog.log("OBD: reminder to take the adapter out shown")
        } catch (_: SecurityException) {
            DriveLog.log("OBD: can't show the adapter reminder - notifications are off for Drive Music")
        }
    }

    fun answered(ctx: Context, takenOut: Boolean) {
        NotificationManagerCompat.from(ctx).cancel(ID)
        DriveLog.log(if (takenOut) "OBD: adapter taken out (you confirmed)" else "OBD: adapter left in (you chose)")
    }

    /** A new drive started: an old reminder is out of date. */
    fun clear(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID)
}

class AdapterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DriveLog.init(context)
        when (intent.action) {
            AdapterReminder.ACTION_TAKEN_OUT -> AdapterReminder.answered(context, true)
            AdapterReminder.ACTION_LEAVE_IN -> AdapterReminder.answered(context, false)
        }
    }
}
