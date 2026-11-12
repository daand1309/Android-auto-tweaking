package com.daand.drivemusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daand.drivemusic.car.DriveCarAppService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The spoken welcome briefing, shown on the car screen while it plays: as a pop-up (heads-up) over
 * whatever is on screen - normally YouTube Music - and in full if you open Drive Music on the car.
 * When the greeting ends the pop-up is removed and the car screen closes again, so you're back on
 * YouTube Music. (Android Auto doesn't let one app open another on the car screen, so we can't
 * "switch to" YouTube Music - we just get out of its way.)
 */
object Briefing {
    private const val CHANNEL = "briefing"
    private const val NOTIFICATION_ID = 8

    /** The briefing being spoken right now, or null. Read on the main thread. */
    var text: String? = null
        private set
    var title: String = ""
        private set

    /** Identifies the briefing on screen, so an earlier greeting finishing can't remove a newer one. */
    private var token = 0

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /**
     * Called when the greeting starts speaking. Only shows on the car while Android Auto is connected.
     * Returns a token for [end] (0 = nothing shown).
     */
    fun start(ctx: Context, title: String, text: String): Int {
        val inCar = Prefs(ctx).lastCarState.let { it == "Android Auto connected" || it == "Android Automotive" }
        if (!inCar) return 0
        token++
        this.title = title
        this.text = text
        postCarPopup(ctx.applicationContext, title, text)
        DriveLog.log("BRIEFING: shown on the car screen")
        notifyChanged()
        return token
    }

    /** Called when that greeting is done (or gave up). Ignored if a newer briefing replaced it. */
    fun end(ctx: Context, shownToken: Int) {
        if (shownToken == 0 || shownToken != token) return
        endNow(ctx)
    }

    /** Removes whatever briefing is showing (e.g. the car disconnected mid-greeting). */
    fun endNow(ctx: Context) {
        if (text == null) return
        text = null
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        DriveLog.log("BRIEFING: done - removed from the car screen")
        notifyChanged()
    }

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    /**
     * Splits the briefing into a short title (the first sentence) and the rest, for the pop-up.
     * Pure logic, unit tested.
     */
    fun split(full: String): Pair<String, String> {
        val clean = full.trim()
        // First real sentence end - not the dot in "Mr." / "Dr." / "dhr." etc.
        val cut = Regex("""[.!?](\s|$)""").findAll(clean).map { it.range.first }.firstOrNull { i ->
            val word = clean.substring(0, i).substringAfterLast(' ').lowercase()
            word !in ABBREVIATIONS
        }
        if (cut == null || cut + 1 >= clean.length) return clean to ""
        return clean.substring(0, cut + 1).trim() to clean.substring(cut + 1).trim()
    }

    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "sr", "jr", "dhr", "mevr", "mw", "ir", "ing", "drs", "mr.")

    /** The spoken text with the phonetic name swapped back to how you write it (whole words only). */
    fun forScreen(spoken: String, pronunciation: String, name: String): String =
        if (pronunciation.isBlank() || name.isBlank()) spoken
        else spoken.replace(Regex("""(?<!\w)${Regex.escape(pronunciation)}(?!\w)"""), Regex.escapeReplacement(name))

    private fun postCarPopup(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Quiet on the phone (it's already being spoken); the car shows it as a pop-up (see extender).
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Welcome briefing", NotificationManager.IMPORTANCE_LOW))
        val openOnCar = runCatching {
            CarPendingIntent.getCarApp(
                ctx, 0, Intent().setComponent(ComponentName(ctx, DriveCarAppService::class.java)), 0
            )
        }.getOrNull()
        val car = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(body.ifBlank { title })
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)  // heads-up on the car screen
            .apply { if (openOnCar != null) setContentIntent(openOnCar) }
            .build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(3 * 60 * 1000L)  // never left behind, even if something goes wrong
            .extend(car)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            DriveLog.log("BRIEFING: can't show it on the car - notifications are off for Drive Music")
        }
    }
}
