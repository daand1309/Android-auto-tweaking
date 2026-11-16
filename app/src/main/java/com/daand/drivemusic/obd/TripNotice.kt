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
    /** [multiline] puts the driving score on its own line: the Trips screen has a narrow column, and
     *  wrapped there the row began with "· smooth 86/100", which reads like a bullet. */
    fun text(t: TripRecord, price: Double, dutch: Boolean, multiline: Boolean = false): String {
        val head = listOfNotNull(
            "%.1f km".format(Locale.ROOT, t.km),
            t.per100?.let { "%.1f L/100".format(Locale.ROOT, it) },
            if (price > 0 && t.litres > 0) "€%.2f".format(Locale.ROOT, t.litres * price) else null,
            "${t.minutes} min",
        ).joinToString(" · ")
        val score = t.score?.let { "${Trip.scoreWord(it, dutch)} $it/100" } ?: return head
        return if (multiline) "$head\n$score" else "$head · $score"
    }

    fun post(ctx: Context, t: TripRecord, p: Prefs) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip summary", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (p.greetingDutch) "Rit voorbij" else "Trip done")
            .setContentText(text(t, p.fuelPriceForCosts, p.greetingDutch))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(ID, n) } catch (_: SecurityException) {}
    }

    /** "Early warning" after a drive: something is slowly changing (see CarTrends). */
    fun postEarlyWarning(ctx: Context, text: String, dutch: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(EARLY_CHANNEL, "Early warnings about the car", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, EARLY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (dutch) "Vroege waarschuwing voor de auto" else "Early warning about the car")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(EARLY_ID, n) } catch (_: SecurityException) {}
    }

    private const val EARLY_CHANNEL = "early_warning"
    private const val EARLY_ID = 12
}
