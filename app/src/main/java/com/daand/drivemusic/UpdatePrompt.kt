package com.daand.drivemusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "I do apologise, sir, but there is an update" - asks before reading an update, with a green
 * (go ahead) and red (not interested) button: big ones in Drive Mode, and as notification buttons on
 * the phone and the car screen. No answer = go ahead (you're driving, you may not tap anything).
 */
object UpdatePrompt {
    private const val CHANNEL = "update_prompt"
    private const val CHANNEL_QUIET = "update_questions_quiet"
    private const val NOTIFICATION_ID = 9
    const val ACTION_YES = "com.daand.drivemusic.UPDATE_YES"
    const val ACTION_NO = "com.daand.drivemusic.UPDATE_NO"
    const val ANSWER_TIMEOUT_MS = 10_000L

    /** The question on screen right now, or null. Main thread. */
    var question: String? = null
        private set
    /** null = not answered yet. */
    private var answer: Boolean? = null
    private var waiting: ((Boolean) -> Unit)? = null
    private var askId = 0
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    /** Shows the buttons. Call [awaitAnswer] once the spoken question has finished. */
    fun open(ctx: Context, title: String, question: String) {
        askId++
        this.question = question
        answer = null
        waiting = null
        postButtons(ctx.applicationContext, title, question)
        changed()
    }

    /** Green/red tapped (Drive Mode, phone notification or car pop-up). Can come before [awaitAnswer]. */
    fun answer(ctx: Context, yes: Boolean) {
        if (question == null || answer != null) return
        answer = yes
        DriveLog.log("UPDATE: you tapped ${if (yes) "GO AHEAD" else "NOT INTERESTED"}")
        close(ctx)
        waiting?.let { cb -> waiting = null; cb(yes) }
    }

    /**
     * Calls [onAnswer] with your answer - straight away if you already tapped, else when you tap, or
     * with `true` after [ANSWER_TIMEOUT_MS] without an answer.
     */
    fun awaitAnswer(ctx: Context, onAnswer: (Boolean) -> Unit) {
        answer?.let { onAnswer(it); return }
        if (question == null) { onAnswer(true); return }
        waiting = onAnswer
        val id = askId
        main.postDelayed({
            if (id == askId && answer == null && question != null) {
                DriveLog.log("UPDATE: no answer - going ahead")
                answer = true
                close(ctx)
                waiting?.let { cb -> waiting = null; cb(true) }
            }
        }, ANSWER_TIMEOUT_MS)
    }

    /** Drops the question (e.g. the car disconnected). */
    fun cancel(ctx: Context) {
        if (question == null) return
        waiting = null
        close(ctx)
    }

    private fun close(ctx: Context) {
        question = null
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        changed()
    }

    private fun postButtons(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Heads-up so the buttons are right there; silent because the question is already spoken.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Update questions", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        // When Drive Mode is on the phone screen it already shows big buttons; a phone pop-up
        // would only cover them. The car screen still gets its own heads-up (CarAppExtender).
        nm.createNotificationChannel(NotificationChannel(CHANNEL_QUIET, "Update questions (Drive Mode open)", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        val quiet = DriveModeActivity.onScreen
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, UpdateAnswerReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val yes = pi(ACTION_YES, 1)
        val no = pi(ACTION_NO, 2)
        val car = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .addAction(R.drawable.ic_answer_yes, "Go ahead", yes)
            .addAction(R.drawable.ic_answer_no, "Not interested", no)
            .build()
        val n = NotificationCompat.Builder(ctx, if (quiet) CHANNEL_QUIET else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_answer_yes, "🟢  Go ahead", yes)
            .addAction(R.drawable.ic_answer_no, "🔴  Not interested", no)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60 * 1000L)
            .extend(car)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            DriveLog.log("UPDATE: can't show the buttons - notifications are off for Drive Music")
        }
    }
}

/** The green/red notification buttons. Not exported: only our own notification can send these. */
class UpdateAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DriveLog.init(context)
        when (intent.action) {
            UpdatePrompt.ACTION_YES -> UpdatePrompt.answer(context, true)
            UpdatePrompt.ACTION_NO -> UpdatePrompt.answer(context, false)
        }
    }
}
