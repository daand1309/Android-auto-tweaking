package com.daand.drivemusic

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Big-button, full-screen dashboard for the phone in its holder. Large touch targets, high
 * contrast, stays awake, hides the system bars. Controls the music via the running service.
 */
@SuppressLint("SetTextI18n")
class DriveModeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nowPlaying: TextView
    /** The song's cover (from the music app itself - see CoverArt); hidden when it has none. */
    private lateinit var cover: android.widget.ImageView
    private var coverShown: android.graphics.Bitmap? = null
    private lateinit var status: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var playBtn: android.widget.ImageButton
    private lateinit var resumeBtn: Button
    private lateinit var rootFrame: android.widget.FrameLayout
    private var promptView: View? = null
    /** The bottom-left button: plays the welcome - and cuts it short while it's talking. */
    private lateinit var welcomeBtn: Button

    /** "There is an update" -> big green / red buttons over the whole screen until you answer. */
    private val promptChanged: () -> Unit = {
        val q = UpdatePrompt.question
        if (q == null) {
            promptView?.let { rootFrame.removeView(it) }
            promptView = null
        } else if (promptView == null) {
            promptView = buildPrompt(q).also { rootFrame.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        }
    }

    override fun onDestroy() {
        if (open?.get() === this) open = null
        UpdatePrompt.removeListener(promptChanged)
        RadioPlayer.removeListener(radioChanged)
        super.onDestroy()
    }

    private fun buildPrompt(question: String): View = LinearLayout(this).apply {
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Style.BG)  // solid: nothing shows through behind the question
        isClickable = true  // don't let taps fall through to the music buttons
        val pad = if (wide) dp(14) else dp(24)
        setPadding(pad, pad, pad, pad)
        val q = TextView(this@DriveModeActivity).apply {
            text = (if (Prefs(this@DriveModeActivity).butler) "🎩  " else "") + question
            setTextColor(Style.TEXT); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); fitText(14, 26, 8)
        }
        val buttons = LinearLayout(this@DriveModeActivity).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val no = answerButton(R.drawable.ic_answer_no, "No thanks", Style.DANGER) { UpdatePrompt.answer(this@DriveModeActivity, false) }
            .apply { contentDescription = "Not interested" }
        val yes = answerButton(R.drawable.ic_answer_yes, "Go ahead", 0xFF2E7D32.toInt()) { UpdatePrompt.answer(this@DriveModeActivity, true) }
        if (wide) {
            buttons.addView(yes, vLp(weight = 1f)); buttons.addView(no, vLp(weight = 1f))
            addView(q, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f).apply { marginEnd = dp(10) })
            addView(buttons, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            buttons.addView(no, hLp(1f)); buttons.addView(yes, hLp(1f))
            addView(q, vLp(weight = 1.2f))
            addView(buttons, vLp(weight = 2f))
        }
    }
    private fun answerButton(icon: Int, label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD); fitText(14, 26, 1)
        val d = getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(40), dp(40)) }
        setCompoundDrawablesRelative(null, d, null, null); compoundDrawableTintList = Style.tint(Color.WHITE)
        background = Style.button(color, dp(Style.R_BIG).toFloat()); stateListAnimator = null
        setOnClickListener { onClick() }
        Feel.press(this)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closeIfLeftCar()) return
            val onCar = ::pager.isInitialized && pager.current == TAB_CAR
            if (onCar) updateCarPage() else updateDynamic()
            updateStrip()
            // Live car data (car tab, or the numbers above the tabs): every second. Otherwise every 3 s.
            val live = (onCar || stripShown) && Prefs(this@DriveModeActivity).obdEnabled && com.daand.drivemusic.obd.ObdManager.live() != null
            handler.postDelayed(this, if (live) 1000L else 3000L)
        }
    }

    // ---------------------------------------------------------------- tabs

    private lateinit var pager: SwipePager
    private val tabLabels = mutableListOf<TextView>()

    private fun buildTabs(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Style.BG) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(2)) }
        // Big, solid tab buttons: easy to find and hit at a glance (64 dp tall; a bit less in landscape).
        val tabHeight = dp(if (wide) 52 else 64)
        listOf(R.drawable.ic_music to "Music", R.drawable.ic_navigate to "Go", R.drawable.ic_radio to "Radio", R.drawable.ic_gauge to "Car").forEachIndexed { i, (icon, label) ->
            val tv = TextView(this).apply {
                // Four tabs: one line each, shrinking a little on narrow phones rather than wrapping.
                text = label; textSize = if (wide) 18f else 19f; gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0)
                maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(12, if (wide) 18 else 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                tag = icon  // the icon goes inline, right next to the word (see highlightTab)
                setOnClickListener { pager.goTo(i) }
                Feel.press(this, 0.97f)
            }
            tabLabels += tv
            bar.addView(tv, LinearLayout.LayoutParams(0, tabHeight, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        column.addView(buildStrip(), LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(bar, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        pager = SwipePager(this)
        pager.addPage(buildUi())
        pager.addPage(buildGoPage())
        pager.addPage(buildRadioPage())
        pager.addPage(buildCarPage())
        pager.onPageChanged = { i ->
            highlightTab(i)
            com.daand.drivemusic.obd.ObdManager.liveView = i == TAB_CAR || stripShown
            if (i == TAB_GO) refreshGo()
            if (i == TAB_RADIO) refreshRadio()
            handler.removeCallbacks(tick); handler.post(tick)
        }
        column.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        highlightTab(0)
        return column
    }

    private fun highlightTab(i: Int) {
        tabLabels.forEachIndexed { idx, tv ->
            val on = idx == i
            // Selected tab: raised card, accent icon (the accent is kept for what matters most).
            tv.setTextColor(if (on) Style.TEXT else Style.TEXT2)
            val label = tv.text.toString().trim()
            val icon = dp(if (wide) 24 else 28)
            val d = getDrawable(tv.tag as Int)!!.mutate().apply {
                setBounds(0, 0, icon, icon); setTint(if (on) Style.PRIMARY else Style.TEXT2)
            }
            tv.text = android.text.SpannableString("  $label").apply {
                setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, 0)
            }
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            // Both are solid buttons; the selected one is raised with a red bar along its bottom.
            val r = dp(Style.R_CARD).toFloat()
            tv.background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(
                Style.rounded(Style.SURFACE2, r),
                android.graphics.drawable.GradientDrawable().apply { setColor(Style.PRIMARY); cornerRadius = dp(2).toFloat() },
            )).apply {
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(1, dp(4))
                setLayerInsetLeft(1, dp(18)); setLayerInsetRight(1, dp(18)); setLayerInsetBottom(1, dp(2))
            }
            else Style.rounded(Style.SURFACE, r)
        }
    }

    /** Test hook: which tab is showing / switch tab. */
    @androidx.annotation.VisibleForTesting internal fun currentTab() = pager.current
    @androidx.annotation.VisibleForTesting internal fun pagerForTest() = pager

    // ---------------------------------------------------------------- live numbers above the tabs

    private lateinit var strip: LinearLayout
    private val stripValues = mutableListOf<TextView>()
    private var stripShown = false

    /** Speed · range · engine · battery: visible on every tab while the adapter gives data; tap = Car tab. */
    private fun buildStrip(): View {
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), 0); visibility = View.GONE
            setOnClickListener { pager.goTo(TAB_CAR) }
            contentDescription = "Live car numbers. Tap for all car data"
        }
        for (unit in listOf("km/h", "km range", "engine", "volt")) {
            val v = TextView(this).apply {
                setTextColor(Style.TEXT); setTypeface(typeface, Typeface.BOLD); textSize = if (wide) 18f else 22f
                gravity = Gravity.CENTER; fontFeatureSettings = Style.TABULAR; maxLines = 1; text = "—"
            }
            stripValues += v
            strip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = Style.rounded(Style.SURFACE, dp(Style.R_CARD).toFloat()); setPadding(dp(4), dp(6), dp(4), dp(6))
                isDuplicateParentStateEnabled = true
                setOnClickListener { pager.goTo(TAB_CAR) }
                addView(v)
                addView(TextView(this@DriveModeActivity).apply { text = unit; textSize = 11f; setTextColor(Style.TEXT2); gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        return strip
    }

    private fun updateStrip() {
        if (!::strip.isInitialized) return
        val p = Prefs(this)
        val obd = com.daand.drivemusic.obd.ObdManager
        val s = if (p.obdEnabled) obd.live() else null
        // On the car tab the big tiles say it all; without data the strip would only show dashes.
        val show = s != null && pager.current != TAB_CAR
        if (show != stripShown) {
            stripShown = show
            strip.visibility = if (show) View.VISIBLE else View.GONE
            obd.liveView = pager.current == TAB_CAR || show
        }
        if (!show) return
        val v = CarDash.values(s, obd.trip, p.greetingDutch, obd.rangeKm(s))
        val texts = listOf(
            s!!.speedKmh?.toString() ?: "—",
            obd.rangeKm(s)?.toString() ?: "—",
            s.coolantC?.let { "$it°" } ?: "—",
            s.volts?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "—",
        )
        val colors = listOf(null, null, v["coolant"]?.second, v["battery"]?.second)
        texts.forEachIndexed { i, t ->
            val tv = stripValues[i]
            if (tv.text != t) tv.text = t
            val c = colors[i] ?: Style.TEXT
            if (tv.currentTextColor != c) tv.setTextColor(c)
        }
    }

    // ---------------------------------------------------------------- car tab (live OBD data)

    private val carValues = HashMap<String, TextView>()
    private val carSubs = HashMap<String, TextView>()
    private val carLabels = HashMap<String, TextView>()
    private lateinit var carWarning: TextView
    private lateinit var carStatus: TextView
    /** The tile rows: hidden while the adapter isn't set up (eight empty "—" tiles said nothing). */
    private val tileRows = mutableListOf<View>()

    private fun buildCarPage(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Style.BG); setPadding(dp(14), dp(8), dp(14), dp(14)) }
        carWarning = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            background = Style.rounded(0xFFB71C1C.toInt(), dp(12).toFloat()); setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        root.addView(carWarning, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val all = listOf(
            "speed" to "Speed", "rpm" to "RPM", "coolant" to "Engine", "fuel" to "Fuel now",
            "trip" to "This trip", "battery" to "Battery", "load" to "Throttle · load", "temps" to "Outside · intake",
        )
        // Portrait: 4 rows of 2. Landscape: 2 rows of 4, so every tile stays tall enough to read.
        val tiles = all.chunked(if (wide) 4 else 2)
        for (row in tiles) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((key, label) in row) r.addView(carTile(key, label), hLp(1f))
            root.addView(r, vLp(weight = 1f))
            tileRows += r
        }
        carStatus = TextView(this).apply { setTextColor(Style.TEXT2); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        root.addView(carStatus, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun carTile(key: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Style.rounded(Style.SURFACE, dp(Style.R_CARD).toFloat())
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(TextView(this@DriveModeActivity).apply { text = label; textSize = 14f; setTextColor(Style.TEXT2); gravity = Gravity.CENTER }
            .also { carLabels[key] = it })
        val value = TextView(this@DriveModeActivity).apply {
            text = "—"; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT); gravity = Gravity.CENTER
            fontFeatureSettings = Style.TABULAR
            maxLines = 1
            // Fill the tile as large as the text allows, on any screen and in portrait or landscape.
            setAutoSizeTextTypeUniformWithConfiguration(12, 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        carValues[key] = value
        addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Optional second, smaller line (e.g. the trip's average consumption under its distance).
        val sub = TextView(this@DriveModeActivity).apply {
            setTextColor(Style.TEXT2); textSize = 15f; gravity = Gravity.CENTER; maxLines = 1
            fontFeatureSettings = Style.TABULAR; visibility = View.GONE
        }
        carSubs[key] = sub
        addView(sub)
    }

    private fun updateCarPage() {
        val p = Prefs(this)
        val obd = com.daand.drivemusic.obd.ObdManager
        val s = if (p.obdEnabled) obd.live() else null
        val range = if (s != null) obd.rangeKm(s) else null
        val v = CarDash.values(s, obd.trip, p.greetingDutch, range)
        carLabels["load"]?.let { val l = CarDash.loadLabel(range, p.greetingDutch); if (it.text != l) it.text = l }
        for ((key, sub) in carSubs) {
            // Landscape tiles are too short for a second line: it would shrink the main number.
            val t = if (wide) "" else v["${key}_sub"]?.first ?: ""
            if (sub.text != t) sub.text = t
            val vis = if (t.isEmpty()) View.GONE else View.VISIBLE
            if (sub.visibility != vis) sub.visibility = vis
        }
        for ((key, tv) in carValues) {
            val (text, color) = v[key] ?: ("—" to null)
            // First value after "—": count up like a dashboard waking up; after that just update.
            if (Motion.target(tv) != text) {
                if (tv.text == "—" && text != "—") Motion.countUp(tv, text) else { Motion.stop(tv); tv.text = text }
            }
            val c = color ?: Style.TEXT
            if (tv.currentTextColor != c) tv.setTextColor(c)
        }
        val warning = CarDash.warning(s, p.greetingDutch)
        carWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
        if (warning != null && carWarning.text != warning) carWarning.text = warning
        // Red only for "stop / be careful"; amber with dark text for "get it checked".
        val severe = CarDash.warningIsSevere(s)
        if (warning != null && carWarning.tag != severe) {
            carWarning.tag = severe
            carWarning.background = Style.rounded(if (severe) Style.DANGER else Style.AMBER, dp(Style.R_SMALL).toFloat())
            carWarning.setTextColor(if (severe) Color.WHITE else Style.ON_BRIGHT)
        }
        val statusText = when {
            !p.obdEnabled -> "Set up \"Car data (OBD)\" in the app to see live engine data here."
            s == null -> "Waiting for the car… (${obd.status})"
            else -> ""  // live: the numbers say it all
        }
        if (carStatus.text != statusText) carStatus.text = statusText
        carStatus.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE
        // Not set up: the explanation takes the tiles' place (Find fuel works without the adapter).
        val tilesVis = if (p.obdEnabled) View.VISIBLE else View.GONE
        tileRows.forEach { if (it.visibility != tilesVis) it.visibility = tilesVis }
        val statusLp = carStatus.layoutParams as LinearLayout.LayoutParams
        val wantWeight = if (p.obdEnabled) 0f else 1f
        if (statusLp.weight != wantWeight) {
            statusLp.weight = wantWeight; statusLp.height = if (p.obdEnabled) LinearLayout.LayoutParams.WRAP_CONTENT else 0
            carStatus.textSize = if (p.obdEnabled) 14f else 20f
            carStatus.layoutParams = statusLp
        }
    }

    // ---------------------------------------------------------------- Go tab: fuel, places, calls

    private lateinit var goList: LinearLayout
    /** Find fuel's answer: a Choice, a message, or null while looking / not looked yet. */
    private var fuelResult: Any? = null
    private var fuelAt = 0L
    private var fuelLooking = false

    private fun buildGoPage(): View {
        goList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(14)) }
        return android.widget.ScrollView(this).apply {
            setBackgroundColor(Style.BG); isFillViewport = true
            addView(goList, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }

    /** One big row: icon, title, a smaller line under it. No [onClick] = just information. */
    private fun goRow(icon: String, title: String, sub: String, accent: Int? = null, onClick: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = if (onClick != null) Style.button(Style.SURFACE, dp(Style.R_BIG).toFloat()) else Style.rounded(Style.SURFACE, dp(Style.R_BIG).toFloat())
        addView(TextView(this@DriveModeActivity).apply { text = icon; textSize = if (wide) 26f else 32f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(if (wide) 46 else 56), -2))
        addView(LinearLayout(this@DriveModeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DriveModeActivity).apply {
                text = title; setTextColor(accent ?: Style.TEXT); setTypeface(typeface, Typeface.BOLD)
                textSize = if (wide) 18f else 21f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (sub.isNotBlank()) addView(TextView(this@DriveModeActivity).apply {
                text = sub; setTextColor(Style.TEXT2); textSize = if (wide) 13f else 15f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; fontFeatureSettings = Style.TABULAR
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        if (onClick != null) { setOnClickListener { onClick() }; Feel.press(this, 0.98f) }
        contentDescription = "$title. $sub"
    }

    /** Fills the Go tab; the fuel search starts by itself the first time (and again after 10 minutes). */
    private fun refreshGo() {
        if (!::goList.isInitialized) return
        val p = Prefs(this)
        val dutch = p.greetingDutch
        if (!fuelLooking && (fuelResult == null || System.currentTimeMillis() - fuelAt > 10 * 60_000L)) lookForFuel()
        goList.removeAllViews()
        val h = dp(if (wide) 72 else 88)
        fun add(v: View) = goList.addView(v, LinearLayout.LayoutParams(-1, h).apply { bottomMargin = dp(10) })

        when (val r = fuelResult) {
            is FuelStops.Choice -> {
                fun line(st: FuelStops.Stop) = "%s · €%.3f · %.1f km".format(java.util.Locale.ROOT, st.station.name, st.price, st.km)
                val good = if (dutch) "Dichtbij, goede prijs" else "Nearest good price"
                val cheap = if (dutch) "Goedkoopste" else "Cheapest"
                if (r.nearestGood == r.cheapest) add(goRow("⛽", "$good · $cheap", line(r.cheapest)) { navigate(r.cheapest, "$good · $cheap") })
                else {
                    add(goRow("⛽", good, line(r.nearestGood)) { navigate(r.nearestGood, good) })
                    add(goRow("💶", cheap, line(r.cheapest)) { navigate(r.cheapest, cheap) })
                }
            }
            is String -> add(goRow("⛽", if (dutch) "Tanken" else "Find fuel", r + (if (dutch) " Tik om opnieuw te zoeken." else " Tap to try again.")) {
                fuelResult = null; refreshGo() })
            else -> add(goRow("⛽", if (dutch) "Tanken" else "Find fuel", if (dutch) "Tankstations zoeken…" else "Looking for fuel near you…", onClick = null))
        }

        for ((label, enc) in listOf((if (dutch) "Naar huis" else "Home") to p.placeHome, (if (dutch) "Naar werk" else "Work") to p.placeWork)) {
            val icon = if (enc == p.placeHome) "🏠" else "💼"
            val place = Shortcuts.decodePlace(enc)
            if (place == null) add(goRow(icon, label, if (dutch) "Nog niet ingesteld: app → Car screen" else "Not set yet: in the app → Car screen", onClick = null))
            else add(goRow(icon, label, if (dutch) "Waze op het autoscherm" else "Waze on the car screen") {
                navigate(place.lat, place.lon, "NAVIGATE: to $label")   // never where it is
            })
        }

        val favs = Shortcuts.decodeFavourites(p.favourites)
        if (favs.isEmpty()) add(goRow("📞", if (dutch) "Bellen" else "Call", if (dutch) "Kies tot 3 favorieten in de app → Car screen" else "Pick up to 3 favourites in the app → Car screen", onClick = null))
        favs.forEachIndexed { i, fav -> add(goRow("📞", fav.name, if (dutch) "Bellen, handsfree via de auto" else "Call, hands-free through the car") { call(fav, i) }) }
    }

    private fun lookForFuel() {
        fuelLooking = true
        val dutch = Prefs(this).greetingDutch
        val fuel = FuelPrices.Fuel.of(Prefs(this).fuelType)
        Thread({
            val here = currentPosition()
            val result: Any = when {
                here == null -> if (dutch) "Je locatie is niet bekend. Sta locatie toe voor Drive Music." else "Your position isn't known. Allow location for Drive Music."
                else -> try {
                    FuelStops.choose(FuelPrices.stationsAround(here.first, here.second), here.first, here.second, fuel)
                        ?: (if (dutch) "Geen tankstation met ${fuel.nl} binnen 15 km." else "No station selling ${fuel.en} within 15 km.")
                } catch (e: Exception) {
                    DriveLog.log("FUEL: station lookup failed (${e.javaClass.simpleName}: ${e.message})")
                    if (dutch) "De prijzen zijn nu niet op te halen (geen bereik?)." else "Couldn't get the prices right now (no signal?)."
                }
            }
            runOnUiThread {
                fuelLooking = false
                fuelResult = result; fuelAt = System.currentTimeMillis()
                if (!isFinishing) refreshGo()
            }
        }, "find-fuel").start()
    }

    /** Your position for the fuel search, on the phone only: a recent fix, else a fresh one (≤ 10 s). */
    private fun currentPosition(): Pair<Double, Double>? = Here.position(this)

    private fun navigate(stop: FuelStops.Stop, why: String) = navigate(stop.station.lat, stop.station.lon,
        "FUEL: navigating to ${stop.station.name}", " ($why, €%.3f, %.1f km)".format(java.util.Locale.ROOT, stop.price, stop.km))

    /** Turn-by-turn in Waze (shows on the car screen too); Google Maps, then any maps app, if it isn't there. */
    private fun navigate(lat: Double, lon: Double, what: String, detail: String = "") {
        // Best: Android Auto opens your navigation app (Waze) on the car screen; the phone stays here.
        if (com.daand.drivemusic.car.CarLink.navigate(lat, lon)) {
            DriveLog.log("$what on the car screen$detail")
            return
        }
        val tries = listOf(
            "Waze" to android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(FuelStops.wazeUri(lat, lon))).setPackage("com.waze"),
            "Google Maps" to android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(FuelStops.navUri(lat, lon))).setPackage("com.google.android.apps.maps"),
            "a maps app" to android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")),
        )
        for ((app, intent) in tries) {
            try {
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                // The route reaches Waze (also on the car screen when it runs there); Drive Mode goes straight
                // back on top of the phone - same moment, while we're still in front (that's what allows it).
                startActivity(android.content.Intent(this, DriveModeActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                DriveLog.log("$what with $app, Drive Mode kept in front$detail")
                return
            } catch (e: android.content.ActivityNotFoundException) { /* next one */ }
        }
        DriveLog.log("$what: no maps app to navigate with")
    }

    /** A favourite: through the car's own phone screen when Drive Music runs there, else straight from the phone (audio goes to the car). */
    private fun call(f: Shortcuts.Favourite, index: Int) {
        val where = when {
            com.daand.drivemusic.car.CarLink.call(f.number) -> "on the car screen"
            else -> {
                val direct = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val uri = android.net.Uri.parse(Shortcuts.telUri(f.number))
                try {
                    startActivity(android.content.Intent(if (direct) android.content.Intent.ACTION_CALL else android.content.Intent.ACTION_DIAL, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    if (direct) "from the phone" else "dialer opened (allow calls in the app for one tap)"
                } catch (e: Exception) { "failed (${e.javaClass.simpleName})" }
            }
        }
        DriveLog.log("CALL: favourite ${index + 1} from Drive Mode - $where")   // no name, no number
    }

    // ---------------------------------------------------------------- Radio tab

    private lateinit var radioList: LinearLayout
    private val radioChanged: () -> Unit = { refreshRadio(); lastShown = ""; updateDynamic() }

    private fun buildRadioPage(): View {
        radioList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(14)) }
        RadioPlayer.addListener(radioChanged)
        return android.widget.ScrollView(this).apply {
            setBackgroundColor(Style.BG); isFillViewport = true
            addView(radioList, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }

    /** What's on (with a big Stop), then your stations: tap one to tune in, tap the one playing to stop. */
    private fun refreshRadio() {
        if (!::radioList.isInitialized) return
        val dutch = Prefs(this).greetingDutch
        radioList.removeAllViews()
        val h = dp(if (wide) 64 else 76)
        val st = RadioPlayer.station
        val state = RadioPlayer.state
        if (st != null && state != RadioPlayer.State.OFF) {
            val (line, color) = when (state) {
                RadioPlayer.State.CONNECTING -> (if (dutch) "Verbinden…" else "Tuning in…") to Style.TEXT2
                RadioPlayer.State.PLAYING -> (if (dutch) "Speelt nu - tik om te stoppen" else "Playing - tap to stop") to Style.GREEN
                else -> ((if (dutch) "Lukt niet: " else "Didn't work: ") + RadioPlayer.problem + (if (dutch) ". Tik om opnieuw te proberen." else ". Tap to try again.")) to Style.AMBER
            }
            radioList.addView(goRow(if (state == RadioPlayer.State.PLAYING) "⏹" else "📻", st.name, line,
                accent = if (state == RadioPlayer.State.ERROR) Style.AMBER else color) {
                if (state == RadioPlayer.State.ERROR) RadioPlayer.play(this, st) else RadioPlayer.stop("you pressed stop")
            }, LinearLayout.LayoutParams(-1, dp(if (wide) 72 else 92)).apply { bottomMargin = dp(14) })
        }
        for (s in Radio.decode(Prefs(this).radioStations)) {
            val playing = s == st && RadioPlayer.on
            radioList.addView(goRow("📻", s.name, if (playing) (if (dutch) "Speelt nu" else "On now") else "",
                accent = if (playing) Style.GREEN else null) { RadioPlayer.toggle(this, s) },
                LinearLayout.LayoutParams(-1, h).apply { bottomMargin = dp(10) })
        }
        radioList.addView(TextView(this).apply {
            text = if (dutch) "Stations toevoegen of weghalen: in de app → Music & driving → Radio. Radio gebruikt ~60 MB per uur."
                else "Add or remove stations in the app: Music & driving → Radio. Radio uses about 60 MB an hour."
            setTextColor(Style.TEXT2); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4))
        })
    }

    /** True once the car has been connected while this screen was open. */
    private var sawCarConnected = false

    /**
     * Closes Drive Mode when you leave the car - otherwise it would keep the screen on (even over the
     * lock screen) in your pocket. Opened by hand while parked? Then it stays until you close it.
     */
    private fun closeIfLeftCar(): Boolean {
        // "Reconnecting" (stall + restart rebooted the radio) still counts as being in the car.
        val connected = Prefs(this).lastCarState.let { it == "Android Auto connected" || it == "Android Automotive" || it == DriveHold.STATE }
        if (connected) { sawCarConnected = true; return false }
        if (!sawCarConnected) return false
        DriveLog.log("Drive Mode: closed - you left the car")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open = java.lang.ref.WeakReference(this)
        // Opened for a drive (the car is already connected): that counts as having seen the car, so
        // the end of the drive closes it even if it never got a refresh in front.
        if (Prefs(this).lastCarState.let { it == "Android Auto connected" || it == "Android Automotive" || it == DriveHold.STATE }) sawCarConnected = true
        Motion.enabled = Prefs(this).animations
        DriveLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Opened: the "tap to open" banner isn't needed any more.
        getSystemService(android.app.NotificationManager::class.java).cancel(DriveMonitorService.DRIVE_MODE_NOTIFICATION)
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        DriveLog.log("Drive Mode: opened" + if (locked) " over the lock screen" else "")
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(Style.BG) }
        // Tabs: music controls and live car data, swipe between them (or tap the tab names).
        // MATCH_PARENT so the weighted button rows get real height (not collapsed to zero).
        frame.addView(buildTabs(), android.widget.FrameLayout.LayoutParams(-1, -1))
        rootFrame = frame
        UpdatePrompt.addListener(promptChanged)
        promptChanged()
        setContentView(frame)
        if (Prefs(this).showSplash) playIntro(frame)
    }

    /** Original animated intro: the car name scales in with an accent sweep, then fades away.
     *  Deliberately not a manufacturer emblem - just a clean custom motion piece. */
    private fun playIntro(frame: android.widget.FrameLayout) {
        val name = Prefs(this).carName.ifBlank { "Drive" }
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Style.BG)
            isClickable = true
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val sub = TextView(this).apply {
            text = "DRIVE MODE"; setTextColor(Style.TEXT2); textSize = 14f
            letterSpacing = 0.4f; alpha = 0f; setPadding(0, dp(14), 0, 0); gravity = Gravity.CENTER
        }
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.72).toInt()
        // Your own intro image (e.g. the car badge) replaces the gauge when one is set.
        val logo = IntroImage.load(this)
        val hero: View
        var gauge: GaugeSweepView? = null
        if (logo != null) {
            hero = android.widget.ImageView(this).apply {
                setImageBitmap(logo); adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                alpha = 0f; scaleX = 0.8f; scaleY = 0.8f; maxHeight = side
            }
            column.addView(hero, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        } else {
            // The power-up: a rev counter self-test around your car's name.
            val dial = android.widget.FrameLayout(this)
            gauge = GaugeSweepView(this, Style.PRIMARY, Style.SURFACE2)
            dial.addView(gauge, android.widget.FrameLayout.LayoutParams(-1, -1))
            hero = TextView(this).apply {
                text = name.uppercase(); setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.15f; gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(18, 56, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            }
            dial.addView(hero, android.widget.FrameLayout.LayoutParams((side * 0.62).toInt(), (side * 0.3).toInt(), Gravity.CENTER))
            column.addView(dial, LinearLayout.LayoutParams(side, side).apply { gravity = Gravity.CENTER })
        }
        column.addView(sub, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        overlay.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))

        val remove = Runnable {
            Motion.play(350, update = { f -> overlay.alpha = 1 - f; overlay.scaleX = 1 + 0.06f * f; overlay.scaleY = overlay.scaleX },
                end = { (overlay.parent as? android.widget.FrameLayout)?.removeView(overlay) })
        }
        // Skip on tap - only cancel the intro's own timer, not the screen's refresh.
        overlay.setOnClickListener { handler.removeCallbacks(remove); remove.run() }

        val heroFrom = hero.scaleX
        Motion.play(if (logo != null) 650 else 500, interp = android.view.animation.OvershootInterpolator(1.4f), update = { f ->
            hero.alpha = f.coerceIn(0f, 1f); hero.scaleX = heroFrom + (1 - heroFrom) * f; hero.scaleY = hero.scaleX
        })
        Motion.play(400, delayMs = 350, update = { sub.alpha = it })
        gauge?.play()
        handler.postDelayed(remove, if (logo != null) 2300 else 1900)
    }


    override fun onResume() {
        super.onResume()
        onScreen = true
        // You've seen it: the monitor stops pushing Drive Mode up for the rest of this drive.
        DriveMonitorService.instance?.noteDriveModeOnScreen()
        if (::pager.isInitialized) com.daand.drivemusic.obd.ObdManager.liveView = pager.current == TAB_CAR
        goImmersive()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        onScreen = false
        handler.removeCallbacks(tick)
        com.daand.drivemusic.obd.ObdManager.liveView = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun svc() = DriveMonitorService.instance

    private fun buildUi(): View {
        nowPlaying = TextView(this).apply {
            setTextColor(Style.TEXT); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; fitText(16, 30, 4)
        }
        cover = SquareImage(this, if (wide) 1f else 0.42f).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = Style.rounded(Style.SURFACE, dp(Style.R_BIG).toFloat()); clipToOutline = true
            contentDescription = "Cover"; visibility = View.GONE
        }
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (up in listOf(true, false)) thumbs.addView(Button(this).apply {
            text = if (up) "👍" else "👎"; textSize = if (wide) 18f else 22f; isAllCaps = false
            contentDescription = if (up) "Like this song" else "Dislike this song and skip it"
            background = Style.button(Style.SURFACE2, dp(Style.R_CARD).toFloat()); stateListAnimator = null
            setOnClickListener {
                val ok = svc()?.rate(up, "Drive Mode") == true
                status.text = when { !ok -> "YouTube Music didn't take the ${if (up) "like" else "dislike"}"; up -> "👍 Liked"; else -> "👎 Disliked - skipping" }
                lastShown = ""; handler.postDelayed({ updateDynamic() }, 2_500)
            }
            Feel.press(this)
        }, LinearLayout.LayoutParams(0, dp(if (wide) 44 else 52), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        status = TextView(this).apply {
            setTextColor(Style.TEXT2); textSize = 15f; gravity = Gravity.CENTER; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Big transport row (horizontal: weight drives WIDTH, height fills the row).
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(bigButton(R.drawable.ic_prev, "Previous song", accent = false) { svc()?.skipPrevious(); flash() }, hLp(1f))
        playBtn = bigButton(R.drawable.ic_play, "Play", accent = true) { svc()?.playPause(); handler.postDelayed({ updateDynamic() }, 300) }
        controls.addView(playBtn, hLp(1.3f))
        controls.addView(bigButton(R.drawable.ic_next, "Next song", accent = false) { svc()?.skipNext(); flash() }, hLp(1f))

        // Bottom row: icon on top, one short word underneath.
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bottom.addView(smallButton(R.drawable.ic_volume, "Welcome") {
            if (Voice.speaking) Voice.stop("skip button in Drive Mode")
            else WelcomeAnnouncer.run(this, svc()?.currentSong(), recordAsGreeted = false)
            updateDynamic()
        }.also { welcomeBtn = it }, hLp(1f))
        resumeBtn = smallButton(R.drawable.ic_repeat, "") {
            ResumeToggle.tapNow(this, "Drive Mode")   // for this drive only (see ResumeToggle)
            updateDynamic()
        }
        bottom.addView(resumeBtn, hLp(1f))
        bottom.addView(smallButton(R.drawable.ic_answer_no, "Close") { finish() }, hLp(0.8f))

        val pad = if (wide) dp(12) else dp(20)
        if (wide) {
            // Landscape: song on the left, buttons on the right - nothing gets squashed.
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Style.BG); setPadding(pad, pad, pad, pad)
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            left.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1.3f).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(dp(6), dp(6), dp(6), dp(2)) })
            left.addView(nowPlaying, vLp(weight = 1f))
            left.addView(thumbs, vLp())
            left.addView(status, vLp())
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(controls, vLp(weight = 2f))
            right.addView(bottom, vLp(weight = 1f))
            root.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f))
            return root
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            setPadding(pad, pad, pad, pad)
        }
        // Portrait: the cover beside the song (it hides itself when the music app gives none).
        val songRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        songRow.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            .apply { marginEnd = dp(14) })
        songRow.addView(nowPlaying, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(songRow, vLp(weight = 2f))
        root.addView(thumbs, vLp())
        root.addView(status, vLp())
        root.addView(controls, vLp(weight = 3f))
        root.addView(bottom, vLp(weight = 1.4f))
        return root
    }

    private var lastShown = ""

    companion object {
        /** True while Drive Mode is in front - phone pop-ups would only cover its own buttons. */
        @Volatile var onScreen = false
        @Volatile private var open: java.lang.ref.WeakReference<DriveModeActivity>? = null

        /**
         * The drive ended: close Drive Mode now, even when it isn't in front. It only checked while on
         * screen, so after a drive it waited in the background and flashed up hours later when the
         * phone was unlocked. Opened by hand while parked (no car seen yet): left alone.
         */
        fun closeBecauseDriveEnded() {
            val a = open?.get() ?: return
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (a.sawCarConnected) { DriveLog.log("Drive Mode: closed - you left the car"); a.finish() }
            }
        }
        private const val TAB_MUSIC = 0
        private const val TAB_GO = 1
        private const val TAB_RADIO = 2
        private const val TAB_CAR = 3
    }

    /** Refreshes song / status / buttons - only touching the views when something changed (saves redraws). */
    private fun updateDynamic() {
        val s = svc()
        val prefs = Prefs(this)
        val song = s?.currentSong()
        val songText = song?.let { "${it.title}${if (it.artist.isNotBlank()) "\n${it.artist}" else ""}" }
            ?: if (s == null) "Monitor not running" else "Nothing playing"
        val statusText = if (s == null) "Open the app and grant Notification access" else when (prefs.lastCarState) {
            "not connected", "" -> "Not connected to the car"
            DriveHold.STATE -> "Android Auto reconnecting…"
            else -> prefs.lastCarState
        }
        val radio = RadioPlayer.station?.takeIf { RadioPlayer.on }
        val statusText2 = if (radio != null) "📻 ${radio.name} is on - press play for YouTube Music" else statusText
        val playing = s?.isPlaying() == true
        val resumeOn = ResumeToggle.isOnNow(this)
        val speaking = Voice.speaking
        val art = s?.currentArt()
        val key = "$songText|$statusText2|$playing|$resumeOn|$speaking|${System.identityHashCode(art)}"
        if (key == lastShown) return
        lastShown = key
        // While something is being said, this button cuts it short rather than starting another one.
        if (::welcomeBtn.isInitialized) {
            welcomeBtn.text = if (speaking) "Skip" else "Welcome"
            val d = getDrawable(if (speaking) R.drawable.ic_answer_no else R.drawable.ic_volume)!!.mutate()
                .apply { setBounds(0, 0, dp(26), dp(26)) }
            // Same place as when it was built: beside the label in landscape, above it in portrait.
            if (wide) welcomeBtn.setCompoundDrawablesRelative(d, null, null, null) else welcomeBtn.setCompoundDrawablesRelative(null, d, null, null)
            welcomeBtn.contentDescription = if (speaking) "Skip the voice" else "Play the welcome"
        }
        Motion.swapText(nowPlaying, songText)
        if (art !== coverShown) {
            coverShown = art
            cover.setImageBitmap(art)
            cover.visibility = if (art == null) View.GONE else View.VISIBLE
            if (art != null) Motion.fadeIn(cover)
        }
        status.text = statusText2
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        if (playBtn.tag != icon) { if (playBtn.tag != null) Motion.pop(playBtn); playBtn.tag = icon }
        playBtn.setImageResource(icon)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        resumeBtn.text = if (resumeOn) "Resume on" else "Resume off"
        resumeBtn.contentDescription = if (resumeOn) "Auto-resume is on. Tap to turn it off for this drive" else "Auto-resume is off. Tap to turn it on"
        // Bright green needs dark text and icon to stay readable.
        val fg = if (resumeOn) Style.ON_BRIGHT else Style.TEXT
        resumeBtn.setTextColor(fg); resumeBtn.compoundDrawableTintList = Style.tint(fg)
        resumeBtn.background = Style.button(if (resumeOn) Style.GREEN else Style.SURFACE2, dp(Style.R_CARD).toFloat())
    }

    private fun flash() = handler.postDelayed({ updateDynamic() }, 400)

    /** Big icon-only transport button. Only play gets the accent colour; the rest stay calm. */
    private fun bigButton(icon: Int, desc: String, accent: Boolean, onClick: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageResource(icon); contentDescription = desc
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageTintList = Style.tint(Color.WHITE)
        background = Style.button(if (accent) Style.PRIMARY else Style.SURFACE2, dp(Style.R_BIG).toFloat())
        // Icon about half the button, whatever its size (portrait, landscape, small phones).
        addOnLayoutChangeListener { v, l, t, r, btm, _, _, _, _ ->
            val pad = (minOf(r - l, btm - t) * 0.27f).toInt()
            // Applied on the next frame: changing padding in the middle of a layout pass is ignored/warned about.
            if (v.paddingLeft != pad) v.post { v.setPadding(pad, pad, pad, pad) }
        }
        setOnClickListener { onClick() }
        Feel.press(this)
    }

    /** Smaller button: icon on top, one short label underneath (shrinks to fit, never split mid-word). */
    private fun smallButton(icon: Int, label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        // Landscape rows are short: icon beside the label (not above it), so the label can stay readable.
        // One line, shrinking to fit: with two lines allowed, auto-size happily broke a word in half
        // ("Welcom/e", "Auto-resu/me on" in the screenshots).
        // Landscape caps the size lower, so the three labels come out (nearly) the same size.
        fitText(9, if (wide) 14 else 17, 1); setPadding(dp(6), dp(6), dp(6), dp(6))
        val d = getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(26), dp(26)) }
        if (wide) setCompoundDrawablesRelative(d, null, null, null) else setCompoundDrawablesRelative(null, d, null, null)
        compoundDrawablePadding = dp(if (wide) 8 else 2)
        compoundDrawableTintList = Style.tint(Style.TEXT)
        setTextColor(Style.TEXT); background = Style.button(Style.SURFACE2, dp(Style.R_CARD).toFloat()); stateListAnimator = null
        setOnClickListener { onClick() }
        Feel.press(this)
    }

    /**
     * A square picture: as tall as its row in portrait (but at most [maxShare] of the width, so the
     * song text keeps room), as large as its column allows in landscape.
     */
    private class SquareImage(ctx: android.content.Context, private val maxShare: Float) : android.widget.ImageView(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec); val h = MeasureSpec.getSize(heightSpec)
            val side = minOf(if (h > 0) h else w, (w * maxShare).toInt()).coerceAtLeast(0)
            setMeasuredDimension(side, side)
        }
    }

    /** Vertical child: full width, weighted height (0 so the weight controls it). */
    private fun vLp(weight: Float = 0f, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0) 0 else height, weight).apply {
            val m = dp(6); setMargins(m, m, m, m)
        }

    /** Horizontal child: weighted width (0 so the weight controls it), full row height. */
    private fun hLp(weight: Float) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            val m = dp(6); setMargins(m, m, m, m)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Wide screen (landscape phone / car mount): side-by-side layouts instead of stacked ones. */
    private val wide get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Shrinks the text until it fits the button - whole words only, never "Welco/me". */
    private fun TextView.fitText(minSp: Int, maxSp: Int, lines: Int) {
        maxLines = lines
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
}
