package com.daand.drivemusic.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Builds the Android Auto screen. Kept separate from [DriveScreen] so a unit test can build it with
 * the real car library: Android Auto validates templates strictly (e.g. a ListTemplate action strip
 * allows only ONE button with a custom title and no background colour), and breaking a rule makes
 * the car screen crash on open.
 */
object CarTemplate {
    /** Android Auto shows at most this many list rows while driving. */
    const val MAX_ROWS = 6

    data class State(
        val nowPlaying: String,
        val playing: Boolean,
        val autoResume: Boolean,
        val infoTitle: String,
        val infoText: String,
    )

    class Callbacks(
        val onPrevious: () -> Unit,
        val onPlayPause: () -> Unit,
        val onNext: () -> Unit,
        val onToggleResume: () -> Unit,
        val onWelcome: () -> Unit,
    )

    fun build(s: State, cb: Callbacks): Template {
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Now playing").addText(s.nowPlaying).build())
            .addItem(Row.Builder().setTitle("⏮  Previous").setOnClickListener { cb.onPrevious() }.build())
            .addItem(Row.Builder().setTitle(if (s.playing) "⏸  Pause" else "▶  Play").setOnClickListener { cb.onPlayPause() }.build())
            .addItem(Row.Builder().setTitle("⏭  Next").setOnClickListener { cb.onNext() }.build())
            .addItem(
                Row.Builder()
                    .setTitle(if (s.autoResume) "↻  Auto-resume: On" else "↻  Auto-resume: Off")
                    .addText("Tap to switch")
                    .setOnClickListener { cb.onToggleResume() }
                    .build()
            )
            .addItem(Row.Builder().setTitle(s.infoTitle).addText(s.infoText).build())
            .build()

        // Exactly one custom-titled action, no background colour (the action-strip rules).
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Welcome").setOnClickListener { cb.onWelcome() }.build())
            .build()

        return ListTemplate.Builder()
            .setTitle("Drive Music")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .setActionStrip(strip)
            .build()
    }

    /** The welcome briefing while it's being spoken (closes by itself when the greeting ends). */
    fun briefing(title: String, text: String, onClose: () -> Unit): Template =
        MessageTemplate.Builder(text.ifBlank { title }.ifBlank { "Welcome" }.take(1000))
            .setTitle(title.ifBlank { "Welcome" }.take(100))
            .setHeaderAction(Action.APP_ICON)
            .addAction(Action.Builder().setTitle("Close").setOnClickListener { onClose() }.build())
            .build()
}
