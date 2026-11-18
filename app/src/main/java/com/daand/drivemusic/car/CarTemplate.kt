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
        val onPlayPause: () -> Unit,
        val onNext: () -> Unit,
        val onFuel: () -> Unit,
        val onNavigate: () -> Unit,
        val onCall: () -> Unit,
        val onMore: () -> Unit,
        val onWelcome: () -> Unit,
    )

    /**
     * The main car screen: what you use most within one tap, the rest one level down. Six rows is
     * Android Auto's limit while driving.
     */
    fun build(s: State, cb: Callbacks, callNames: String, placeNames: String): Template {
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle(if (s.playing) "⏸  Pause" else "▶  Play").addText(s.nowPlaying).setOnClickListener { cb.onPlayPause() }.build())
            .addItem(Row.Builder().setTitle("⏭  Next").setOnClickListener { cb.onNext() }.build())
            .addItem(Row.Builder().setTitle("⛽  Find fuel").addText("Nearest good price or cheapest - no motorway").setOnClickListener { cb.onFuel() }.setBrowsable(true).build())
            .addItem(Row.Builder().setTitle("🧭  Navigate").addText(placeNames).setOnClickListener { cb.onNavigate() }.setBrowsable(true).build())
            .addItem(Row.Builder().setTitle("📞  Call").addText(callNames).setOnClickListener { cb.onCall() }.setBrowsable(true).build())
            .addItem(Row.Builder().setTitle("⋯  More").addText("Previous, like, auto-resume, " + s.infoTitle.lowercase()).setOnClickListener { cb.onMore() }.setBrowsable(true).build())
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

    /** One row of a sub-screen: no [onClick] = just information. */
    class Item(val title: String, val text: String = "", val onClick: (() -> Unit)? = null)

    /** A sub-screen (Find fuel, Navigate, Call, More); [loading] while its content is being fetched. */
    fun list(title: String, items: List<Item>, loading: Boolean = false): Template {
        val b = ListTemplate.Builder().setTitle(title.take(60)).setHeaderAction(Action.BACK)
        if (loading) return b.setLoading(true).build()
        val list = ItemList.Builder()
        items.take(MAX_ROWS).forEach { i ->
            val r = Row.Builder().setTitle(i.title.ifBlank { " " }.take(80))
            if (i.text.isNotBlank()) r.addText(i.text.take(120))
            i.onClick?.let { r.setOnClickListener(it) }
            list.addItem(r.build())
        }
        if (items.isEmpty()) list.setNoItemsMessage("Nothing here")
        return b.setSingleList(list.build()).build()
    }

    /** The welcome briefing while it's being spoken (closes by itself when the greeting ends). */
    fun briefing(title: String, text: String, onClose: () -> Unit): Template =
        MessageTemplate.Builder(text.ifBlank { title }.ifBlank { "Welcome" }.take(1000))
            .setTitle(title.ifBlank { "Welcome" }.take(100))
            .setHeaderAction(Action.APP_ICON)
            .addAction(Action.Builder().setTitle("Close").setOnClickListener { onClose() }.build())
            .build()
}
