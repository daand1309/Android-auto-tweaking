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
    private lateinit var status: TextView
    private lateinit var playBtn: android.widget.ImageButton
    private lateinit var resumeBtn: Button
    private lateinit var rootFrame: android.widget.FrameLayout
    private var promptView: View? = null

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
        UpdatePrompt.removeListener(promptChanged)
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
            // Car tab with live data: every second. No data yet (or music tab): every 3 s.
            val live = onCar && Prefs(this@DriveModeActivity).obdEnabled && com.daand.drivemusic.obd.ObdManager.live() != null
            handler.postDelayed(this, if (live) 1000L else 3000L)
        }
    }

    // ---------------------------------------------------------------- tabs

    private lateinit var pager: SwipePager
    private val tabLabels = mutableListOf<TextView>()

    private fun buildTabs(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Style.BG) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), 0) }
        listOf(R.drawable.ic_music to "Music", R.drawable.ic_gauge to "Car").forEachIndexed { i, (icon, label) ->
            val tv = TextView(this).apply {
                text = label; textSize = 18f; gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
                tag = icon  // the icon goes inline, right next to the word (see highlightTab)
                setOnClickListener { pager.goTo(i) }
                Feel.press(this, 0.97f)
            }
            tabLabels += tv
            bar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        column.addView(bar, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        pager = SwipePager(this)
        pager.addPage(buildUi())
        pager.addPage(buildCarPage())
        pager.onPageChanged = { i ->
            highlightTab(i)
            com.daand.drivemusic.obd.ObdManager.liveView = i == TAB_CAR
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
            val d = getDrawable(tv.tag as Int)!!.mutate().apply {
                setBounds(0, 0, dp(22), dp(22)); setTint(if (on) Style.PRIMARY else Style.TEXT2)
            }
            tv.text = android.text.SpannableString("  $label").apply {
                setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, 0)
            }
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.background = if (on) Style.rounded(Style.SURFACE2, dp(Style.R_CARD).toFloat()) else null
        }
    }

    /** Test hook: which tab is showing / switch tab. */
    @androidx.annotation.VisibleForTesting internal fun currentTab() = pager.current
    @androidx.annotation.VisibleForTesting internal fun pagerForTest() = pager

    // ---------------------------------------------------------------- car tab (live OBD data)

    private val carValues = HashMap<String, TextView>()
    private val carSubs = HashMap<String, TextView>()
    private val carLabels = HashMap<String, TextView>()
    private lateinit var carWarning: TextView
    private lateinit var carStatus: TextView

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
            overlay.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(350).withEndAction {
                (overlay.parent as? android.widget.FrameLayout)?.removeView(overlay)
            }.start()
        }
        // Skip on tap - only cancel the intro's own timer, not the screen's refresh.
        overlay.setOnClickListener { handler.removeCallbacks(remove); remove.run() }

        hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (logo != null) 650 else 500)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        sub.animate().alpha(1f).setStartDelay(350).setDuration(400).start()
        gauge?.play()
        handler.postDelayed(remove, if (logo != null) 2300 else 1900)
    }


    override fun onResume() {
        super.onResume()
        onScreen = true
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
            WelcomeAnnouncer.run(this, svc()?.currentSong(), recordAsGreeted = false)
        }, hLp(1f))
        resumeBtn = smallButton(R.drawable.ic_repeat, "") {
            val p = Prefs(this)
            p.autoResume = !p.autoResume
            if (p.autoResume) svc()?.resumeOffThisDrive = false
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
            left.addView(nowPlaying, vLp(weight = 1f))
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
        root.addView(nowPlaying, vLp(weight = 2f))
        root.addView(status, vLp())
        root.addView(controls, vLp(weight = 3f))
        root.addView(bottom, vLp(weight = 1.4f))
        return root
    }

    private var lastShown = ""

    companion object {
        /** True while Drive Mode is in front - phone pop-ups would only cover its own buttons. */
        @Volatile var onScreen = false
        private const val TAB_MUSIC = 0
        private const val TAB_CAR = 1
    }

    /** Refreshes song / status / buttons - only touching the views when something changed (saves redraws). */
    private fun updateDynamic() {
        val s = svc()
        val prefs = Prefs(this)
        val song = s?.currentSong()
        val songText = song?.let { "${it.title}${if (it.artist.isNotBlank()) "\n${it.artist}" else ""}" }
            ?: if (s == null) "Monitor not running" else "Nothing playing"
        val statusText = if (s == null) "Open the app and grant Notification access" else prefs.lastCarState
        val playing = s?.isPlaying() == true
        val resumeOn = prefs.autoResume
        val key = "$songText|$statusText|$playing|$resumeOn"
        if (key == lastShown) return
        lastShown = key
        Motion.swapText(nowPlaying, songText)
        status.text = statusText
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        if (playBtn.tag != icon) { if (playBtn.tag != null) Motion.pop(playBtn); playBtn.tag = icon }
        playBtn.setImageResource(icon)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        resumeBtn.text = if (resumeOn) "Resume on" else "Resume off"
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
        fitText(10, 17, 1); setPadding(dp(4), dp(8), dp(4), dp(6))
        val d = getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(26), dp(26)) }
        setCompoundDrawablesRelative(null, d, null, null); compoundDrawablePadding = dp(2)
        compoundDrawableTintList = Style.tint(Style.TEXT)
        setTextColor(Style.TEXT); background = Style.button(Style.SURFACE2, dp(Style.R_CARD).toFloat()); stateListAnimator = null
        setOnClickListener { onClick() }
        Feel.press(this)
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
