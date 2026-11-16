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
    // v2: the first channel was "default" importance - it landed silently in the shade and wasn't seen.
    // A channel's importance can't be raised once created, so this is a new one (high: pops up + vibrates).
    private const val CHANNEL = "adapter_reminder_v2"
    private const val OLD_CHANNEL = "adapter_reminder"
    private const val ID = 13
    const val ACTION_TAKEN_OUT = "com.daand.drivemusic.ADAPTER_TAKEN_OUT"
    const val ACTION_LEAVE_IN = "com.daand.drivemusic.ADAPTER_LEAVE_IN"
    const val ACTION_DISMISSED = "com.daand.drivemusic.ADAPTER_DISMISSED"

    /** Only after drives where the adapter was really connected, and only if the reminder is on. Pure. */
    fun shouldRemind(enabled: Boolean, adapterUsedThisDrive: Boolean) = enabled && adapterUsedThisDrive

    fun post(ctx: Context) {
        val p = Prefs(ctx)
        val dutch = p.greetingDutch
        // Also shown inside the app (home screen / Car data page): a notification can be swiped away
        // or arrive while the phone is in a pocket, and the adapter is still drawing power then.
        p.adapterPromptPending = true
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, if (dutch) "Herinnering OBD-adapter" else "OBD adapter reminder",
            NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 150, 250)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
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
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_answer_yes, if (dutch) "Eruit gehaald ✓" else "Taken out ✓", action(ACTION_TAKEN_OUT, 31))
            .addAction(R.drawable.ic_answer_no, if (dutch) "Laat zitten" else "Leave it in", action(ACTION_LEAVE_IN, 32))
            // Swiped away anyway (Android 14+ allows it): seen, but in or out is unknown.
            .setDeleteIntent(action(ACTION_DISMISSED, 33))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
        } catch (_: SecurityException) {}
        verifyLater(ctx)
    }

    /**
     * Android hands the notification to SystemUI asynchronously, so asking immediately says "not there"
     * on a fast phone - it did on 7 of 10 drives while the reminder was really on screen. Ask again a
     * moment later instead, so only a real problem ends up in the log.
     */
    internal fun verifyLater(ctx: Context, delayMs: Long = 1_200L) {
        val app = ctx.applicationContext
        Thread({
            Thread.sleep(delayMs)
            verifyNow(app)
        }, "adapter-reminder-check").start()
    }

    /** The check itself (no delay), so a test can call it directly. Writes a line only when it's missing. */
    internal fun verifyNow(ctx: Context) {
        val why = visibility(ctx, ctx.getSystemService(NotificationManager::class.java))
        if (why.startsWith("NOT")) DriveLog.log("OBD: adapter reminder $why")
    }

    /** "shown (pops up)" or why it isn't visible. */
    fun visibility(ctx: Context, nm: NotificationManager): String {
        val there = runCatching { nm.activeNotifications.any { it.id == ID } }.getOrDefault(false)
        val channel = nm.getNotificationChannel(CHANNEL)
        return when {
            !NotificationManagerCompat.from(ctx).areNotificationsEnabled() -> "NOT shown - notifications are off for Drive Music (Settings > Apps > Drive Music > Notifications)"
            channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE -> "NOT shown - the 'OBD adapter reminder' notifications are switched off"
            !there -> "NOT shown - Android didn't keep it"
            channel != null && channel.importance < NotificationManager.IMPORTANCE_HIGH -> "shown, but silently (the reminder's notification category was set to quiet)"
            else -> "shown (pops up)"
        }
    }

    fun answered(ctx: Context, takenOut: Boolean?) {
        NotificationManagerCompat.from(ctx).cancel(ID)
        val p = Prefs(ctx)
        p.adapterPromptPending = false
        p.adapterWhileParked = when (takenOut) { true -> BatteryLog.ADAPTER_OUT; false -> BatteryLog.ADAPTER_IN; null -> BatteryLog.ADAPTER_UNKNOWN }
        DriveLog.log(when (takenOut) {
            true -> "OBD: adapter taken out (you confirmed)"
            false -> "OBD: adapter left in (you chose)"
            null -> "OBD: adapter reminder swiped away (in or out unknown)"
        })
    }

    /** A new drive started: an old reminder is out of date. */
    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID)
        Prefs(ctx).adapterPromptPending = false
    }
}

class AdapterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DriveLog.init(context)
        when (intent.action) {
            AdapterReminder.ACTION_TAKEN_OUT -> AdapterReminder.answered(context, true)
            AdapterReminder.ACTION_LEAVE_IN -> AdapterReminder.answered(context, false)
            AdapterReminder.ACTION_DISMISSED -> AdapterReminder.answered(context, null)
        }
    }
}
