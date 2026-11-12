package com.daand.drivemusic.obd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daand.drivemusic.MainActivity
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.R
import java.util.Locale

/** "18.4 km · 6.3 L/100 · €2.31 · smooth 86/100" after you park. Quiet, never while driving. */
object TripNotice {
    private const val CHANNEL = "trip_summary"
    private const val ID = 11

    /** The summary line. Pure, unit tested. */
    fun text(t: TripRecord, price: Double, dutch: Boolean): String = listOfNotNull(
        "%.1f km".format(Locale.ROOT, t.km),
        t.per100?.let { "%.1f L/100".format(Locale.ROOT, it) },
        if (price > 0 && t.litres > 0) "€%.2f".format(Locale.ROOT, t.litres * price) else null,
        "${t.minutes} min",
        t.score?.let { "${Trip.scoreWord(it, dutch)} $it/100" },
    ).joinToString(" · ")

    fun post(ctx: Context, t: TripRecord, p: Prefs) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip summary", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (p.greetingDutch) "Rit voorbij" else "Trip done")
            .setContentText(text(t, p.fuelPrice, p.greetingDutch))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(ID, n) } catch (_: SecurityException) {}
    }
}
