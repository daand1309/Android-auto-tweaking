package com.daand.drivemusic

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Asks for an update check once a day, whether or not the app is open.
 *
 * Until now the check ran from the monitor service and when the app was opened, so a week without
 * driving meant a week without hearing about a new version. This is a plain inexact alarm - no extra
 * permission, and Doze may shift it by a while, which is fine for "once a day".
 */
object DailyUpdateCheck {
    private const val ACTION = "com.daand.drivemusic.DAILY_UPDATE_CHECK"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Safe to call on every start: the same alarm is replaced, not duplicated. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + DAY_MS, DAY_MS, alarm(ctx))
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(alarm(ctx)) }
    }

    private fun alarm(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION) return
            // Knows its own limits: no check while driving, no more than once a day, and it logs the result.
            runCatching { Updater.maybeCheckInBackground(ctx.applicationContext, driving = false) }
                .onFailure { DriveLog.log("UPDATE: daily check couldn't start (${it.javaClass.simpleName})") }
        }
    }
}
