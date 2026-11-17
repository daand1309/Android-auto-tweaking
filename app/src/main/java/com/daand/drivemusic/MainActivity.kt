package com.daand.drivemusic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Setup checklist, settings and test button. Everything is built in code to keep the project small. */
@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var availableUpdate: Updater.Release? = null
    private var updateBusy = false
    /** A fuel-price lookup is running (one at a time). */
    private var fuelPriceFetching = false
    private var updateMessage = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen stays on while the app is open (it sits in a car holder); normal timeout again once you leave.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            // "Play <song> on Drive Music": pass the words to YouTube Music, then get out of the way.
            // Any app can send this, with any data attached: read defensively, pass on only clean text.
            val query = try { intent.getStringExtra(android.app.SearchManager.QUERY) } catch (e: Exception) { null }
            val extras = try { intent.extras } catch (e: Exception) { null }
            com.daand.drivemusic.car.MediaMenu.playFromSearch(DriveMonitorService.instance?.musicController(),
                DriveMonitorService.instance, query, extras)
            finish()
            return
        }
        DriveLog.init(this)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        // The home search box must not grab focus (and the keyboard, and scroll the page to it) on open.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        scroll = ScrollView(this).apply {
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(root)
            setBackgroundColor(Style.BG)
            isFillViewport = true
        }
        setContentView(scroll)
        animateNext = ENTER
        Motion.enabled = prefs.animations
        // Android 12+: the animated launch icon grows and fades into the home screen.
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { sv ->
            val icon = sv.iconView
            Motion.play(320, update = { f ->
                sv.alpha = 1 - f
                icon?.let { it.scaleX = 1 + 0.6f * f; it.scaleY = it.scaleX }
            }, end = { sv.remove() })
        }
        // With the phone's animations off Android shows no launch animation at all: play our own.
        if (savedInstanceState == null && prefs.animations && !android.animation.ValueAnimator.areAnimatorsEnabled()) playLaunchIntro()
        window.statusBarColor = Style.BG
        window.navigationBarColor = Style.BG
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        // Android 15+ draws apps edge-to-edge (behind the status/navigation bars): pad the content
        // by the real bar sizes so page titles don't slide under the clock/notch.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            // The keyboard too: without its height at the bottom the page can't scroll what's under it
            // into view (home search results sat hidden behind the keyboard).
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            root.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(40) + maxOf(bars.bottom, ime))
            if (ime > 0 && searchField?.hasFocus() == true) { scroll.removeCallbacks(searchToTop); scroll.post(searchToTop) }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, DriveMonitorService::class.java))
        }
        render()
        handler.removeCallbacks(homeTick)
        handler.postDelayed(homeTick, 5000)
        // Quietly look for an update when the app is opened (at most every 6 hours).
        if (prefs.autoUpdateCheck && System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
            checkForUpdate(silent = true)
        }
        // The price you pay for fuel, for the area you're in: refreshed when it's a few hours old.
        // (Counted from the last attempt too - a lookup that can't succeed must not retry on every open.)
        if (FuelPrices.dueForLookup(prefs.fuelPriceAuto, prefs.fuelPriceQuoteAt, prefs.fuelPriceTriedAt,
                System.currentTimeMillis())) {
            refreshFuelPrice("the app was opened")
        }
    }

    // ---------------------------------------------------------------- pages

    private enum class Page(val title: String) {
        HOME("Drive Music"), SETUP("Setup"), MUSIC("Music & driving"), GREETING("Welcome greeting"),
        VOICE("Voice"), DRIVE_MODE("Drive Mode"), UPDATES("Updates"), HELP("Help & privacy"),
        CAR("Car screen"), BACKUP("Backup & restore"), OBD("Car data (OBD)"), TRIPS("Trips & costs"), THEMES("Themes"), SERVICE("Service"), ISSUES("Possible issues")
    }

    private var page = Page.HOME
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Test hook: open a page by name ("VOICE", "SETUP", ...). */
    @androidx.annotation.VisibleForTesting
    internal fun openPageForTest(name: String) = go(Page.valueOf(name))

    @androidx.annotation.VisibleForTesting
    internal fun pageNamesForTest(): List<String> = Page.values().map { it.name }

    /** Test hook: scroll the current page to the top or bottom; returns true if the page is taller than the screen. */
    @androidx.annotation.VisibleForTesting
    internal fun scrollForTest(toBottom: Boolean): Boolean {
        if (toBottom) scroll.fullScroll(android.view.View.FOCUS_DOWN) else scroll.scrollTo(0, 0)
        return (scroll.getChildAt(0)?.height ?: 0) > scroll.height
    }

    /**
     * Test hook: text that the layout had to cut off with "…". A phone with a big font scale is the
     * usual reason, and a cut-off value ("6° · light ra…") is unreadable in a way a screenshot review
     * misses - so the large-text test fails on these instead.
     */
    @androidx.annotation.VisibleForTesting
    internal fun cutOffTextsForTest(): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v.visibility != android.view.View.VISIBLE) return
            if (v is TextView) {
                val l = v.layout
                if (l != null && (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) out += "${v.text}".take(48)
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** Test hook: tappable things smaller than a fingertip (44 dp), so a big font can't squeeze them. */
    @androidx.annotation.VisibleForTesting
    internal fun smallTargetsForTest(): List<String> {
        val out = mutableListOf<String>()
        val min = dp(44)
        fun walk(v: android.view.View) {
            if (v.visibility != android.view.View.VISIBLE) return
            if (v.isClickable && (v.height in 1 until min)) {
                val label = (v as? TextView)?.text?.toString() ?: v.contentDescription?.toString() ?: v.javaClass.simpleName
                out += "$label (${(v.height / resources.displayMetrics.density).toInt()}dp)"
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** How the next render appears: glide in (a new page / app start), fade (going back), or not at all (a refresh). */
    private var animateNext = 0

    private fun go(p: Page, back: Boolean = false, section: String? = null) {
        page = p
        animateNext = if (back) BACK else ENTER
        render()
        if (section == null) { scroll.post { scroll.scrollTo(0, 0) }; return }
        // A search result opens the page at its section: scroll to that heading once the new page is laid out.
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val s = section.uppercase()
                val target = (0 until root.childCount).map { root.getChildAt(it) }.firstOrNull { (it as? TextView)?.text?.toString() == s }
                scroll.scrollTo(0, target?.let { (it.top - dp(8)).coerceAtLeast(0) } ?: 0)
            }
        })
    }

    private fun handleBack() {
        when (page) {
            Page.HOME -> if (homeQuery.isNotBlank()) { homeQuery = ""; render() } else finish()
            Page.VOICE -> go(Page.GREETING, back = true)
            else -> go(Page.HOME, back = true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private class SetupItem(val label: String, val help: String, val ok: Boolean, val action: String, val onClick: () -> Unit)

    /** Everything the app needs from the phone, used both for the home-screen badge and the Setup page. */
    private fun setupItems(): List<SetupItem> {
        val pm = getSystemService(PowerManager::class.java)
        val music = prefs.musicPackage
        val items = mutableListOf(
            SetupItem("Notification access", "Required - lets the app control your music.",
                hasNotificationAccess(), "Open") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            SetupItem("Drive Music: battery Unrestricted",
                "Battery → Unrestricted. Samsung: also check Settings → Battery → Background usage limits - Drive Music must not be under Sleeping apps.",
                pm.isIgnoringBatteryOptimizations(packageName), "Open") { openAppSettings(packageName) },
        )
        // The music app's own battery step (skipped if it is this app, so it isn't listed twice).
        if (music != packageName) items += SetupItem("${MusicApps.label(this, music)}: battery Unrestricted", "Battery → Unrestricted.",
            pm.isIgnoringBatteryOptimizations(music), "Open") { openAppSettings(music) }
        items += listOf(
            SetupItem("Android Auto: battery Unrestricted", "Battery → Unrestricted.",
                pm.isIgnoringBatteryOptimizations(DriveMonitorService.ANDROID_AUTO), "Open") { openAppSettings(DriveMonitorService.ANDROID_AUTO) },
        )
        MusicApps.findMicroG(this)?.let { mg ->
            items += SetupItem("MicroG: battery Unrestricted", "Keeps patched YouTube Music signed in.",
                pm.isIgnoringBatteryOptimizations(mg), "Open") { openAppSettings(mg) }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // The app mostly runs without being opened; Android would otherwise take its permissions
            // away after a few months "unused" (Drive Mode pop-up, parking spot would stop working).
            items += SetupItem("Keep permissions when unused", "Turn off \"Remove permissions if app is unused\" / \"Pause app activity if unused\".",
                // Newer Android exempts apps with notification access by itself (the switch is then
                // greyed out), so that counts as done too.
                packageManager.isAutoRevokeWhitelisted || (Build.VERSION.SDK_INT >= 33 && hasNotificationAccess()), "Open") {
                try {
                    startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) { openAppSettings(packageName) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            items += SetupItem("Notifications", "For Drive Mode pop-up and update alerts.",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            items += SetupItem("Nearby devices (optional)", "Shows your car's Bluetooth name in the log.",
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
        return items
    }

    private fun render() {
        if (page != Page.VOICE) releaseVoiceEngine()
        val y = scroll.scrollY  // keep scroll position when re-drawing after a button tap
        root.removeAllViews()
        if (page != Page.HOME) searchField = null
        when (page) {
            Page.HOME -> renderHome()
            Page.SETUP -> renderSetup()
            Page.MUSIC -> renderMusic()
            Page.GREETING -> renderGreeting()
            Page.VOICE -> renderVoice()
            Page.DRIVE_MODE -> renderDriveMode()
            Page.UPDATES -> renderUpdates()
            Page.HELP -> renderHelp()
            Page.OBD -> renderObd()
            Page.TRIPS -> renderTrips()
            Page.CAR -> renderCar()
            Page.BACKUP -> renderBackup()
            Page.THEMES -> renderThemes()
            Page.SERVICE -> renderService()
            Page.ISSUES -> renderIssues()
        }
        when (animateNext) { ENTER -> Motion.enter(root); BACK -> Motion.fadeIn(root) }
        animateNext = 0
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** What's typed in the home search box ("" = show the grid). Kept across redraws. */
    private var homeQuery = ""

    /**
     * Home: your car (photo card), the big Drive Mode button, what needs you, then everything else as
     * icons - built from [HomeFeatures] so a new feature is one entry there - with a search box on top.
     */
    private fun renderHome() {
        lastHomeSignature = homeSignature()
        carCard()
        button("Open Drive Mode", accent = true) { startActivity(Intent(this, DriveModeActivity::class.java)) }
        val needs = NeedsYou.items(needsState())
        if (needs.isNotEmpty()) needsCard(needs)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBox(grid, results)
        root.addView(results)
        root.addView(grid)
        featureGrid(grid, NeedsYou.dotted(needs))
        showSearch(grid, results)
        carOffLine()
    }

    /** The Fault box: the first confirmed code, else how many things are being watched. */
    private fun faultBox(): String {
        val issues = watchIssues()
        issues.firstOrNull { it.level == com.daand.drivemusic.obd.WatchList.Level.FAULT }?.code?.let { return it }
        return when {
            issues.isNotEmpty() -> "${issues.size} to watch"
            !prefs.obdEnabled -> "–"
            else -> "None"
        }
    }

    private fun needsState(): NeedsYou.State {
        val car = if (prefs.obdEnabled) com.daand.drivemusic.obd.ObdManager.live() else null
        return NeedsYou.State(
            musicAppMissing = !MusicApps.isInstalled(packageManager, prefs.musicPackage),
            setupStepsLeft = setupItems().count { !it.ok && !it.label.contains("optional") },
            adapterQuestion = prefs.adapterPromptPending,
            updateVersion = availableUpdate?.versionName,
            faultCode = car?.codes?.firstOrNull(),
            fullScreenMissing = fullScreenMissing(),
        )
    }

    /** One card for everything waiting for you; each line is one tap to fix it. */
    private fun needsCard(items: List<NeedsYou.Item>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.rounded(Style.WARN_BG, dp(Style.R_CARD).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            addView(TextView(this@MainActivity).apply {
                text = "Needs you"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.AMBER)
            })
        }
        fun line(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = "$text  ›"; textSize = 15f; setTextColor(Style.TEXT)
            minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true; setOnClickListener { action() }; Feel.press(this, 0.98f)
        }
        items.forEach { item ->
            when (item.kind) {
                NeedsYou.Kind.ADAPTER -> {
                    card.addView(TextView(this).apply {
                        text = item.text; textSize = 15f; setTextColor(Style.TEXT); setPadding(0, dp(10), 0, dp(2))
                    })
                    card.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(quickBtn(if (prefs.greetingDutch) "✓ Eruit gehaald" else "✓ Taken out") {
                            com.daand.drivemusic.obd.AdapterReminder.answered(this@MainActivity, true); render()
                        })
                        addView(quickBtn(if (prefs.greetingDutch) "Laat zitten" else "Leave it in") {
                            com.daand.drivemusic.obd.AdapterReminder.answered(this@MainActivity, false); render()
                        })
                    })
                }
                NeedsYou.Kind.FULL_SCREEN -> card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(line(item.text) { openFullScreenSetting() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "Not now"; textSize = 13f; setTextColor(Style.TEXT2); minHeight = dp(48); minWidth = dp(64)
                        gravity = android.view.Gravity.CENTER
                        setOnClickListener { prefs.fullScreenNagDismissed = true; render() }
                    })
                })
                NeedsYou.Kind.MUSIC_APP -> card.addView(line(item.text) { pickMusicApp() })
                NeedsYou.Kind.SETUP -> card.addView(line(item.text) { go(Page.SETUP) })
                NeedsYou.Kind.FAULT -> card.addView(line(item.text) { go(Page.ISSUES) })
                NeedsYou.Kind.UPDATE -> card.addView(line(item.text) { go(Page.UPDATES) })
            }
        }
        root.addView(card)
    }

    /** The home search box, while Home is shown (for [searchToTop]). */
    private var searchField: View? = null

    /**
     * Puts the search box at the top of the screen. One runnable, so typing doesn't queue one scroll
     * per letter; run again when the keyboard has finished opening (the first try can be clamped
     * because the room for the keyboard isn't there yet). Works without that room too (Android 13/14,
     * where the page doesn't get the keyboard's height): the box is near the top of the page anyway.
     */
    private val searchToTop = Runnable {
        val f = searchField ?: return@Runnable
        if (page == Page.HOME && f.isAttachedToWindow) scroll.smoothScrollTo(0, (f.top - dp(8)).coerceAtLeast(0))
    }

    /** The search box above the grid: typing swaps the grid for the matching pages. */
    private fun searchBox(grid: LinearLayout, results: LinearLayout) {
        root.addView(android.widget.EditText(this).apply {
            hint = "🔍  Search settings…"; setSingleLine(); textSize = 15f
            setTextColor(Style.TEXT); setHintTextColor(Style.TEXT2)
            background = Style.rounded(Style.SURFACE2, dp(24).toFloat())
            setPadding(dp(18), dp(12), dp(18), dp(12)); minHeight = dp(48)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            setText(homeQuery)
            // Typing a search: put the box at the top of the screen, so the results show under it and
            // above the keyboard.
            searchField = this
            val toTop = { scroll.removeCallbacks(searchToTop); scroll.postDelayed(searchToTop, 250) }
            setOnFocusChangeListener { _, has -> if (has) toTop() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    homeQuery = e?.toString() ?: ""
                    showSearch(grid, results)
                    toTop()
                }
            })
        })
    }

    private fun showSearch(grid: LinearLayout, results: LinearLayout) {
        results.removeAllViews()
        if (homeQuery.isBlank()) { grid.visibility = View.VISIBLE; results.visibility = View.GONE; return }
        grid.visibility = View.GONE; results.visibility = View.VISIBLE
        val found = HomeFeatures.search(homeQuery)
        if (found.isEmpty()) results.addView(TextView(this).apply {
            text = "Nothing found for \u201C${homeQuery.trim()}\u201D"; textSize = 14f; setTextColor(Style.TEXT2)
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
        found.forEach { f ->
            results.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                background = Style.button(Style.SURFACE, dp(Style.R_CARD).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12)); minimumHeight = dp(56)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
                isClickable = true; setOnClickListener { openFeature(f.id) }; Feel.press(this, 0.98f)
                addView(TextView(this@MainActivity).apply { text = f.icon; textSize = 22f; setPadding(0, 0, dp(14), 0); setTextColor(Style.TEXT) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply { text = f.title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT) })
                    addView(TextView(this@MainActivity).apply { text = featureState(f.id); textSize = 13f; setTextColor(Style.TEXT2) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply { text = "›"; textSize = 24f; setTextColor(Style.TEXT2) })
            })
        }
    }

    /** The icon grid: a heading per group, three equal tiles per row. */
    private fun featureGrid(into: LinearLayout, dots: Set<String>) {
        HomeFeatures.byGroup().forEach { (group, list) ->
            into.addView(TextView(this).apply {
                text = group.en.uppercase(); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Style.TEXT2); letterSpacing = 0.08f; setPadding(dp(4), dp(18), 0, dp(4))
            })
            list.chunked(3).forEach { chunk ->
                into.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    chunk.forEach { addView(featureTile(it, it.id in dots)) }
                    repeat(3 - chunk.size) {
                        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                    }
                })
            }
        }
    }

    private fun featureTile(f: Feature, dot: Boolean): View = android.widget.FrameLayout(this).apply {
        background = Style.button(Style.SURFACE, dp(16).toFloat())
        minimumHeight = dp(96)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        isClickable = true; contentDescription = f.title
        setOnClickListener { openFeature(f.id) }
        Feel.press(this, 0.95f)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(12), dp(6), dp(10))
            addView(TextView(this@MainActivity).apply { text = f.icon; textSize = 24f; setTextColor(Style.TEXT); gravity = android.view.Gravity.CENTER })
            addView(TextView(this@MainActivity).apply {
                text = f.title; textSize = 13f; setTextColor(Style.TEXT); gravity = android.view.Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = featureState(f.id); textSize = 11f; setTextColor(Style.TEXT2); gravity = android.view.Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
        }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        if (dot) addView(View(this@MainActivity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Style.AMBER) }
        }, android.widget.FrameLayout.LayoutParams(dp(9), dp(9), android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
    }

    /** What a grid icon / search result opens. */
    private fun openFeature(id: String) {
        val section = HomeFeatures.all.firstOrNull { it.id == id }?.let { HomeFeatures.sectionFor(homeQuery, it) }
        homeQuery = ""
        if (section != null && id == "car_data") { go(Page.OBD, section = section); return }
        if (section != null && id == "voice") { go(Page.VOICE, section = section); return }
        when (id) {
            "music" -> go(Page.MUSIC)
            "greeting" -> go(Page.GREETING)
            "voice" -> go(Page.VOICE)
            "drive_mode" -> go(Page.DRIVE_MODE)
            "car_data" -> go(Page.OBD)
            "trips" -> go(Page.TRIPS)
            "service" -> go(Page.SERVICE)
            "car_screen" -> go(Page.CAR)
            "themes" -> go(Page.THEMES)
            "setup" -> go(Page.SETUP)
            "updates" -> go(Page.UPDATES)
            "backup" -> go(Page.BACKUP)
            "logs" -> startActivity(Intent(this, LogActivity::class.java))
            "help" -> go(Page.HELP)
        }
    }

    /** The one-line state under an icon, so you often don't need to open it. */
    private fun featureState(id: String): String = when (id) {
        "music" -> MusicApps.label(this, prefs.musicPackage)
        "greeting" -> if (prefs.greetingEnabled) "On" else "Off"
        "voice" -> if (prefs.butler) "Butler" else "Sassy"
        "drive_mode" -> if (prefs.autoOpenDriveMode) "Opens by itself" else "Manual"
        "car_data" -> if (prefs.obdEnabled) "On · ${prefs.obdDeviceName.ifBlank { "no adapter" }}" else "Off"
        "trips" -> if (!prefs.obdEnabled) "Needs the adapter"
            else com.daand.drivemusic.obd.TripHistory.load(this).size.let { if (it == 1) "1 trip" else "$it trips" }
        "service" -> serviceStateText()
        "car_screen" -> "Android Auto"
        "themes" -> Style.theme(prefs.theme).name
        "setup" -> setupItems().count { !it.ok && !it.label.contains("optional") }.let { if (it == 0) "All done ✓" else "$it left" }
        "updates" -> "Version ${packageManager.getPackageInfo(packageName, 0).versionName ?: "?"}"
        "backup" -> "Save / restore"
        "logs" -> "Your drives"
        "help" -> "Tips & privacy"
        else -> ""
    }

    private fun serviceStateText(): String {
        if (!prefs.serviceReminderOn) return "Off"
        val synced = prefs.serviceSyncAt > 0 && prefs.serviceSyncAt >= prefs.serviceDate
        if (!synced) return if (prefs.serviceDate > 0) "By date" else "Not set"
        val trips = com.daand.drivemusic.obd.TripHistory.load(this)
        val left = com.daand.drivemusic.obd.CarAlerts.serviceLeft(prefs.serviceSyncAt, prefs.serviceSyncKm, prefs.serviceSyncDays,
            com.daand.drivemusic.obd.TripHistory.kmSince(trips, prefs.serviceSyncAt), System.currentTimeMillis())
        return if (left.first <= 0 || left.second <= 0) "Due now" else "≈ %,d km left".format(java.util.Locale.ROOT, left.first)
    }

    /** "The app knows the car is off": said quietly at the bottom of the home screen. */
    private fun carOffLine() {
        val offMs = com.daand.drivemusic.obd.ObdManager.carOffForMs()
        if (offMs <= 0) return
        text(com.daand.drivemusic.obd.Ignition.text(offMs, prefs.greetingDutch) +
            if (prefs.greetingDutch) " · adapter zit er nog in" else " · adapter still plugged in")
    }

    /**
     * The same question the notification asks ("take the adapter out?"), so it can also be answered
     * when you open the app later - e.g. the notification was swiped away in the car.
     */
    private fun adapterPromptCard() {
        val mins = ((System.currentTimeMillis() - prefs.lastDriveEndAt) / 60_000L).toInt()
        val parked = if (prefs.lastDriveEndAt > 0 && mins in 0..(7 * 24 * 60))
            com.daand.drivemusic.obd.BatteryLog.parkedText(mins, prefs.greetingDutch) else null
        warnCard(if (prefs.greetingDutch)
            "OBD-adapter zit er nog in" + (parked?.let { " ($it)" } ?: "") + ". Haal hem eruit zodat hij geen stroom gebruikt."
        else
            "OBD adapter is still in the car" + (parked?.let { " ($it)" } ?: "") + ". Unplug it so it doesn't draw power while parked.")
        row(
            quickBtn(if (prefs.greetingDutch) "✓ Eruit gehaald" else "✓ Taken out") {
                com.daand.drivemusic.obd.AdapterReminder.answered(this, true); render()
            },
            quickBtn(if (prefs.greetingDutch) "Laat zitten" else "Leave it in") {
                com.daand.drivemusic.obd.AdapterReminder.answered(this, false); render()
            },
        )
    }

    /** May Drive Music take the whole screen when the car connects? (Denied by default on Android 14+.) */
    private fun fullScreenAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
    }

    private fun fullScreenMissing(): Boolean =
        prefs.autoOpenDriveMode && !prefs.fullScreenNagDismissed && !fullScreenAllowed()

    private fun openFullScreenSetting() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { openAppSettings(packageName) }
    }

    private fun renderSetup() {
        pageHeader()
        val items = setupItems()
        val left = items.count { !it.ok && !it.label.contains("optional") }
        if (left == 0) {
            text("Everything the app needs is in place ✓ — the checks below are here if something stops working.")
        } else {
            text(if (left == 1) "One step left. The app works best when all of these are done."
                else "$left steps left. The app works best when all of these are done.")
        }
        header("Checklist")
        items.forEach { check(it.label, it.help, it.ok, it.action, it.onClick) }
        if (!hasNotificationAccess()) {
            header("Switch greyed out?")
            text("Android hides 'Notification access' for sideloaded apps until you allow restricted settings for Drive Music.")
            button("Open app info") { openAppSettings(packageName) }
        }
    }

    private fun renderMusic() {
        pageHeader()
        val music = prefs.musicPackage
        val installed = MusicApps.isInstalled(packageManager, music)

        header("Music app")
        stepperLike((if (installed) "" else "❌ ") + "Playing from: ${MusicApps.label(this, music)}", "Change") { pickMusicApp() }
        if (!installed) warnCard("That app isn't installed on this phone - pick the one that plays your music.",
            action = "Choose app") { pickMusicApp() }

        header("When the car connects")
        switch("Start music automatically", prefs.autoStart) { prefs.autoStart = it; render() }
        if (prefs.autoStart) {
            stepper("Wait before pressing play", "${prefs.startDelaySec}s",
                { prefs.startDelaySec = (prefs.startDelaySec - 1).coerceAtLeast(0) },
                { prefs.startDelaySec = (prefs.startDelaySec + 1).coerceAtMost(30) })
        }
        switch("Set the media volume", prefs.setVolumeOnConnect) { prefs.setVolumeOnConnect = it; render() }
        if (prefs.setVolumeOnConnect) {
            stepper("Volume", "${prefs.connectVolumePct}%",
                { prefs.connectVolumePct = (prefs.connectVolumePct - 10).coerceAtLeast(10) },
                { prefs.connectVolumePct = (prefs.connectVolumePct + 10).coerceAtMost(100) })
        }

        header("When something interrupts it")
        switch("Put the music back on by itself", prefs.autoResume) { prefs.autoResume = it; render() }
        if (prefs.autoResume) {
            stepper("Wait before resuming", "${prefs.resumeDelaySec}s",
                { prefs.resumeDelaySec = (prefs.resumeDelaySec - 2).coerceAtLeast(2) },
                { prefs.resumeDelaySec = (prefs.resumeDelaySec + 2).coerceAtMost(60) })
            text("Never during calls, the Assistant, or while another app is playing.")
        }
        switch("Pause when you leave the car", prefs.pauseOnDisconnect) { prefs.pauseOnDisconnect = it }

        // This used to be its own "Sound" page, whose only advice was to come here.
        header("Sound")
        button("Open the equalizer") { openEqualizer() }
        button("Phone sound settings") {
            try { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (e: Exception) { toast("Open Settings → Sounds") }
        }
        text("Music too quiet? Set the media volume above.\n" +
            "Quiet calls? Turn on VoLTE (Settings → Connections → Mobile networks) and raise the volume during the call.")
    }

    private fun renderGreeting() {
        pageHeader()
        switch("Welcome me when the car connects", prefs.greetingEnabled) { prefs.greetingEnabled = it; render() }
        if (!prefs.greetingEnabled) {
            text("Off. When it's on, the car connecting is the cue: a short spoken welcome with the time of day, " +
                "the weather, and anything the car wants to tell you.")
            return
        }
        button(if (Voice.speaking) "\u23F9  Stop" else "Test greeting", accent = true) {
            if (Voice.speaking) Voice.stop("you pressed stop")
            else {
                stopVoiceSample()
                toast("Speaking...")
                WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false) {
                    refreshIfOn(Page.GREETING)
                }
            }
            refreshIfOn(Page.GREETING)
        }
        header("What it says")
        stepperLike("Your name: ${prefs.greetingName.ifBlank { "not set" }}", "Change") { askName() }
        stepperLike("Birthday: ${prefs.birthday.ifBlank { "none" }}", "Change") { askBirthday() }
        switch("Short greeting (about 20 seconds)", prefs.greetingShort) { prefs.greetingShort = it; render() }
        switch("Say which song plays first", prefs.announceSong) { prefs.announceSong = it }

        header("Weather")
        switch("Weather in the greeting", prefs.weatherEnabled) { prefs.weatherEnabled = it; render() }
        if (prefs.weatherEnabled) switch("Ask before the forecast (green / red)", prefs.weatherAsk) { prefs.weatherAsk = it }
        if (prefs.weatherEnabled) {
            switch("Road conditions too", prefs.roadConditionsEnabled) { prefs.roadConditionsEnabled = it }
            val place = when {
                prefs.weatherPlace.isNotBlank() -> prefs.weatherPlace
                !prefs.weatherLat.isNaN() -> "your location"
                else -> "not set"
            }
            stepperLike("Location: $place", "Change") {
                AlertDialog.Builder(this).setTitle("Weather location")
                    .setItems(arrayOf("Use my location", "Type a city")) { _, i -> if (i == 0) useMyLocation() else askCity() }
                    .show()
            }
            if (needsTown()) warnCard("No town set and no location permission, so the weather would be silent.",
                action = "Choose location") { useMyLocation() }
        }

        header("How it sounds")
        switch("Speak Dutch (Nederlands)", prefs.greetingDutch) { prefs.greetingDutch = it; render() }
        navRow("Voice & style", (if (prefs.butler) "🎩 Butler" else "😏 Sassy") + " · " +
            (if (prefs.voiceName.isBlank()) "automatic voice" else "your chosen voice") + " · tap to listen") { go(Page.VOICE) }

        header("During the drive")
        switch("Tell me when the weather or roads change", prefs.updatesWeather) { prefs.updatesWeather = it }
        switch("Breaking news (only what matters)", prefs.updatesNews) { prefs.updatesNews = it }
        switch("Ask first, with 🟢 / 🔴 buttons", prefs.askBeforeUpdates) { prefs.askBeforeUpdates = it; render() }
        if (prefs.followActive) {
            val until = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.followUntil))
            stepperLike("Following: ${prefs.followTopic} (until $until)", "Change") { askFollowTopic() }
            button("Stop following") {
                prefs.followTopic = ""; prefs.followUntil = 0L
                DriveLog.log("Setting changed: stopped following a story"); render()
            }
        } else {
            navRow("Follow a story", "New headlines about it from NU.nl and NOS during your drives (stops after 3 days)") { askFollowTopic() }
        }
        text("Checks every 10 minutes (first after ~8) and stays quiet unless something matters.\n" +
            "•  Weather: rain on its way (minutes ahead) or easing off, snow, ice, fog, strong gusts\n" +
            "•  News: alerts, disasters, closed motorways, big political news\n" +
            "•  Never: animals, sport, celebs - max 3 per drive, never during a call")
        button(if (prefs.followActive) "Check for news now (incl. your story)" else "Check for news now") { testDriveUpdate() }
        if (prefs.askBeforeUpdates) button("Try the question (🟢 / 🔴)") { tryUpdateQuestion() }
    }

    private fun askFollowTopic() = askText(
        "Follow a story",
        "Keywords, separated by commas - e.g. kabinet, coalitie, Jetten",
        prefs.followTopic.ifBlank { "kabinet, coalitie, Jetten" }
    ) { input ->
        val kws = News.keywords(input)
        if (kws.isEmpty()) {
            prefs.followTopic = ""; prefs.followUntil = 0L
        } else {
            prefs.followTopic = kws.joinToString(", ")
            prefs.followUntil = System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000
            toast("Following: ${prefs.followTopic}")
        }
        DriveLog.log("Setting changed: follow a story = '${prefs.followTopic}'")
        render()
    }

    /** Plays the "there is an update" question with a sample update, so you can try the buttons. */
    private fun tryUpdateQuestion() {
        // Never replace a real question from the drive (its update would silently never be read).
        if (UpdatePrompt.question != null || WelcomeAnnouncer.isBusy) { toast("Busy speaking - try again in a moment"); return }
        val dutch = prefs.greetingDutch
        val butler = prefs.butler
        val sample = DriveUpdates.compose(
            listOf(if (dutch) "Het is gaan regenen, dus de weg wordt nat." else "It has started to rain, so the roads will be wet."),
            null, dutch, butler, prefs.greetingName, withIntro = false)!!
        val question = DriveUpdates.askLine(dutch, butler)
        UpdatePrompt.open(this, if (dutch) "Test" else "Test", question)
        toast("Tap 🟢 or 🔴 in the notification (or open Drive Mode)")
        Greeter(this).speak(question, dutch) {
            UpdatePrompt.awaitAnswer(this) { yes ->
                Greeter(this).speak(if (yes) sample else DriveUpdates.declinedLine(dutch, butler), dutch) {}
            }
        }
    }

    /** Runs the news check now and reads out whatever passes the filter (or says nothing passed). */
    private var newsCheckRunning = false

    private fun testDriveUpdate() {
        if (newsCheckRunning) { toast("Already checking..."); return }
        newsCheckRunning = true
        toast("Checking the news...")
        Thread {
            val p = prefs
            val items = mutableListOf<News.Item>()
            var failed = 0
            items += News.fetchAll(p.greetingDutch, p.weatherPlace) { failed++ }
            val story = News.pick(items, System.currentTimeMillis(), emptyList())
            var followed: News.Item? = null
            var source = ""
            if (p.followActive) {
                val all = News.fetchFollow(p.greetingDutch) { failed++ }
                followed = News.pickFollowed(all, News.keywords(p.followTopic), System.currentTimeMillis(), emptyList())
                source = followed?.source ?: ""
                items += all
            }
            runOnUiThread {
                newsCheckRunning = false
                if (isDestroyed) return@runOnUiThread
                when {
                    items.isEmpty() && failed > 0 -> toast("Couldn't reach the news - no internet?")
                    story == null && followed == null -> toast("Checked ${items.size} headlines - nothing urgent enough to interrupt your music. 👍")
                    else -> {
                        val text = DriveUpdates.compose(emptyList(), story, p.greetingDutch, p.butler, p.greetingName,
                            followed = followed, source = source)!!
                        DriveLog.log("UPDATE (test): \"$text\"")
                        Greeter(this).speak(text, p.greetingDutch) {}
                    }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- car data (OBD)

    private fun renderObd() {
        pageHeader()
        // Asked by the notification after a drive with the adapter: answerable here too.
        if (prefs.adapterPromptPending) adapterPromptCard()
        val carOffMs = com.daand.drivemusic.obd.ObdManager.carOffForMs()
        if (carOffMs > 0) text(com.daand.drivemusic.obd.Ignition.text(carOffMs, prefs.greetingDutch) +
            if (prefs.greetingDutch) " · de auto antwoordt niet meer (contact uit)" else " · the car's computer has stopped answering (ignition off)")
        switch("Read the car through an OBD adapter", prefs.obdEnabled) {
            prefs.obdEnabled = it
            DriveLog.log("Setting changed: OBD = $it")
            if (it && !com.daand.drivemusic.obd.ObdManager.hasPermission(this)) requestBtForObd()
            else if (it && prefs.obdDevice.isNotBlank()) DriveMonitorService.instance?.startObdIfDriving()
            render()
        }
        if (!prefs.obdEnabled) {
            text("Plug a Bluetooth OBD adapter (e.g. Vgate iCar Pro 2S) into the port under the dashboard. " +
                "During drives Drive Music reads the engine temperature, battery voltage, fuel and warning lights, " +
                "and the voice tells you when something needs attention.")
            return
        }
        // The live block first: this page is opened while driving, so the numbers are what you came for.
        header("Right now")
        val obd = com.daand.drivemusic.obd.ObdManager
        val live = obd.live()
        if (live != null) {
            liveReport(com.daand.drivemusic.obd.CarStatusText.report(live, prefs.greetingDutch, obd.trip))
            text((obd.vin?.let { "VIN: $it · " } ?: "") + "${obd.supportedCount} values shared by your car")
        } else text("Status: ${obd.status}. Values appear here while you drive.")

        header("Adapter")
        stepperLike("Adapter: ${prefs.obdDeviceName.ifBlank { "not chosen" }}", "Choose") { chooseObdDevice() }
        text("First time: plug it in, ignition on, then in your phone's Bluetooth settings pair \"Android-Vlink\" " +
            "(PIN 1234). Then choose it above.")
        button("Open Bluetooth settings") {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (e: Exception) { toast("Settings → Connections → Bluetooth") }
        }
        if (prefs.obdDevice.isNotBlank()) {
            if (obdTestRunning) button("Connecting to the adapter…", enabled = false) { }
            else button("Test connection (ignition on)", accent = true) { testObd() }
        }

        stepperLike("Engine size: %.1f L".format(java.util.Locale.ROOT, prefs.obdEngineLitres), "Change") {
            val sizes = listOf(1.6, 1.8, 2.0, 2.2, 1.0, 1.2, 1.4, 1.5, 2.4, 3.0)
            AlertDialog.Builder(this).setTitle("Engine size (for fuel use)")
                .setItems(sizes.map { "%.1f L".format(java.util.Locale.ROOT, it) + if (it == 1.6) "  (ASX 1.6 MIVEC)" else if (it == 1.8) "  (ASX 1.8 MIVEC)" else "" }.toTypedArray()) { _, i ->
                    prefs.obdEngineLitres = sizes[i]; render()
                }.show()
        }

        header("Your car")
        stepperLike("Fuel tank: ${Math.round(prefs.tankLitres)} L", "Change") {
            askNumber("Fuel tank size (litres)", "The 2011 ASX has about 60 L", prefs.tankLitres) {
                // Empty or a typo: keep the old size rather than silently jumping to the minimum.
                if (it >= 20 && it <= 150) prefs.tankLitres = it else toast("Enter a size between 20 and 150 litres")
            }
        }
        fuelPriceSection()
        serviceSection()
        batterySection()

        header("Early warnings")
        run {
            val history = com.daand.drivemusic.obd.TripHistory.load(this).mapNotNull { it.health }
            val dutch = prefs.greetingDutch
            val findings = com.daand.drivemusic.obd.CarTrends.findings(history)
            findings.forEach { f -> warnCard(com.daand.drivemusic.obd.CarTrends.message(f, dutch, false)) }
            val rows = com.daand.drivemusic.obd.CarTrends.overview(history, dutch)
            if (rows.isEmpty()) text("After a few drives with the adapter, the app learns what's normal for your car and " +
                "warns you when something slowly changes - before a fault code or warning light appears.")
            else {
                if (findings.isEmpty()) text("Nothing unusual. Compared with your car's normal from earlier drives:")
                rows.forEach { (k, v) -> statRow(k, v) }
                text("Tracks fuel mixture (air leaks, sensors), charging (alternator, belt), idle steadiness (plugs, coils), " +
                    "warm-up time (thermostat) and road temperature (cooling). A pending fault code (seen before the warning light comes on) is told once a second read confirms it.")
            }
        }

        header("What the voice tells you")
        switch("Remind me to take the adapter out after each drive", prefs.obdUnplugReminder) {
            prefs.obdUnplugReminder = it
            // Turning the reminder off also drops a question that's still open (it's in the app too).
            if (!it) com.daand.drivemusic.obd.AdapterReminder.clear(this)
            DriveLog.log("Setting changed: adapter reminder = $it"); render()
        }
        text("Always: overheating, the check-engine light (with the fault code explained), a weak battery or charging.")
        switch("When the engine is ready to drive (after a cold start)", prefs.obdReadySeconds > 0) {
            prefs.obdReadySeconds = if (it) 30 else 0; render()
        }
        if (prefs.obdReadySeconds > 0) {
            stepper("Idle first for", "${prefs.obdReadySeconds} s",
                { prefs.obdReadySeconds = (prefs.obdReadySeconds - 5).coerceAtLeast(10) },
                { prefs.obdReadySeconds = (prefs.obdReadySeconds + 5).coerceAtMost(120) })
            text("Longer automatically in the cold: at least 45 s near freezing, 60 s below -5 °C. " +
                "Skipped after a short stop (warm engine) or if you're already driving.")
        }
        switch("When the engine has warmed up", prefs.obdWarmup) { prefs.obdWarmup = it }
        switch("When fuel gets low", prefs.obdFuel) { prefs.obdFuel = it }
        text("Fault codes are only read, never cleared - clearing hides a problem instead of fixing it.")
    }

    private fun requestBtForObd() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_OBD)
    }

    @android.annotation.SuppressLint("MissingPermission")  // checked right above the call
    private fun chooseObdDevice() {
        if (!com.daand.drivemusic.obd.ObdManager.hasPermission(this)) { requestBtForObd(); return }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val bonded = try { adapter?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) { emptyList() }
        if (bonded.isEmpty()) { toast("No paired devices - pair \"Android-Vlink\" (PIN 1234) in Bluetooth settings first"); return }
        // Likely OBD adapters first.
        val sorted = bonded.sortedByDescending { com.daand.drivemusic.obd.BluetoothObd.looksLikeObd(it.name) }
        val labels = sorted.map { d ->
            val n = try { d.name } catch (e: SecurityException) { null } ?: d.address
            (if (com.daand.drivemusic.obd.BluetoothObd.looksLikeObd(n)) "🔧  " else "") + n
        }
        AlertDialog.Builder(this).setTitle("Which device is the OBD adapter?")
            .setItems(labels.toTypedArray()) { _, i ->
                val d = sorted[i]
                prefs.obdDevice = d.address
                prefs.obdDeviceName = (try { d.name } catch (e: SecurityException) { null }) ?: d.address
                DriveLog.log("Setting changed: OBD adapter = ${prefs.obdDeviceName}")
                // Chosen while driving (as on the first drive with the adapter): connect now, not next drive.
                if (prefs.obdEnabled) DriveMonitorService.instance?.startObdIfDriving()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private var obdTestRunning = false

    private fun testObd() {
        if (obdTestRunning) return
        obdTestRunning = true
        render()  // the button turns into "Connecting…" so you can see it's busy
        val address = prefs.obdDevice
        val dutch = prefs.greetingDutch
        Thread {
            val result = com.daand.drivemusic.obd.ObdManager.testOnce(this, address, dutch)
            // The VIN stays on screen but out of the log (logs get shared).
            // DriveLog takes the VIN out of every line (LogPrivacy), so this can pass it straight on.
            DriveLog.log("OBD test: ${result.replace('\n', ' ')}")
            runOnUiThread {
                obdTestRunning = false
                // Worked during a drive: keep it connected for the rest of the drive.
                if (result.startsWith("Adapter ✓  Car ✓")) DriveMonitorService.instance?.startObdIfDriving()
                if (!isDestroyed && page == Page.OBD) render()
                if (!isDestroyed) AlertDialog.Builder(this).setTitle("OBD test").setMessage(result).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun renderVoice() {
        pageHeader(back = Page.GREETING)
        ensureVoiceEngine()
        val dutch = prefs.greetingDutch

        header("Style")
        row(
            styleCard("😏  Sassy", "Cheeky one-liners", !prefs.butler) { setStyle(butler = false) },
            styleCard("🎩  Butler", if (dutch) "Refined, polite (u)" else "Refined, British", prefs.butler) { setStyle(butler = true) },
        )
        button(if (Voice.speaking) "⏹  Stop" else "Hear a full greeting", accent = true) { previewVoice() }

        premiumVoiceSection(dutch)

        header(if (prefs.cloudVoiceOn) "Phone voice (fallback)" else "Voice")
        if (!voiceTtsReady) {
            text("Loading voices…")
        } else if (voiceList.isEmpty()) {
            text("No ${if (dutch) "Dutch" else "English"} voices installed on this phone.")
        } else {
            val accents = voiceList.map { VoicePicker.accent(it) }.distinct().sortedBy { if (it == "GB") "" else it }
            if (accents.size > 1) accentChips(listOf("") + accents)
            val names = VoicePicker.friendlyNames(voiceList)
            voiceRow("Automatic", if (prefs.butler && !dutch) "Best British voice for the butler" else "Best voice on this phone",
                prefs.voiceName.isBlank(), playing = samplePlaying == AUTO_SAMPLE,
                onPlay = { playSample(null, automatic = true) }) {
                prefs.voiceName = ""
                DriveLog.log("Setting changed: voice = automatic")
                render(); playSample(null, automatic = true)
            }
            voiceList.filter { accentFilter.isEmpty() || VoicePicker.accent(it) == accentFilter }
                .sortedWith(compareBy<android.speech.tts.Voice> { if (VoicePicker.offline(it)) 0 else 1 }
                    .thenByDescending { it.quality }.thenBy { names[it.name]?.first })
                .forEach { v ->
                    val (title, details) = names[v.name] ?: (v.name to "")
                    voiceRow(title, details, prefs.voiceName == v.name, playing = samplePlaying == v.name,
                        onPlay = { playSample(v) }) {
                        prefs.voiceName = v.name
                        DriveLog.log("Setting changed: voice = ${v.name}")
                        render(); playSample(v)
                    }
                }
            if (!dutch && voiceList.none { VoicePicker.accent(it) == "GB" }) {
                // No second button here: "Download more voices" below opens the very same screen.
                text("No British voice on this phone yet - \"Download more voices\" below adds one " +
                    "(English (United Kingdom)).")
            }
        }

        header("Delivery")
        stepper("Speed", "%.2fx".format(prefs.speechRate),
            { prefs.speechRate = (prefs.speechRate - 0.05f).coerceAtLeast(0.6f); sampleSoon() },
            { prefs.speechRate = (prefs.speechRate + 0.05f).coerceAtMost(1.4f); sampleSoon() })
        stepper("Pitch", "%.2f".format(prefs.speechPitch),
            { prefs.speechPitch = (prefs.speechPitch - 0.05f).coerceAtLeast(0.6f); sampleSoon() },
            { prefs.speechPitch = (prefs.speechPitch + 0.05f).coerceAtMost(1.4f); sampleSoon() })
        stepperLike("Say my name as: ${prefs.namePronunciation.ifBlank { "as written" }}", "Change") { askPronunciation() }

        header("Engine & downloads")
        if (voiceEngines.size > 1) stepperLike("Voice engine: ${voiceEngines.firstOrNull { it.name == prefs.ttsEngine }?.label ?: "automatic"}", "Change") { pickEngine(voiceEngines) }
        if (!MusicApps.isInstalled(packageManager, VoicePicker.GOOGLE_TTS)) {
            button("Get a better voice (Google)") {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${VoicePicker.GOOGLE_TTS}"))) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${VoicePicker.GOOGLE_TTS}"))) }
            }
        }
        button("Download more voices") { openTtsSettings() }
        text("Tip: voices marked \"works offline\" keep working without signal.")
    }

    private fun openTtsSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { toast("Settings → General management → Text-to-speech") }
    }

    // ---------------------------------------------------------------- voice page: live engine + samples

    /** One speech engine while the Voice page is open, so ▶ plays instantly. Released when you leave. */
    private var voiceTts: android.speech.tts.TextToSpeech? = null
    private var voiceTtsReady = false
    private var voiceList: List<android.speech.tts.Voice> = emptyList()
    private var voiceEngines: List<android.speech.tts.TextToSpeech.EngineInfo> = emptyList()
    private var accentFilter = ""
    /** Which voice's sample is playing right now ("automatic" for the automatic row), so ▶ can become ⏹. */
    private var samplePlaying: String? = null
    private val sampleRunnable = Runnable { playSample(null, automatic = prefs.voiceName.isBlank()) }

    private fun ensureVoiceEngine() {
        if (voiceTts != null) return
        voiceTtsReady = false
        val onInit = android.speech.tts.TextToSpeech.OnInitListener { status ->
            runOnUiThread {
                val e = voiceTts ?: return@runOnUiThread
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    toast("No text-to-speech engine found"); releaseVoiceEngine(); return@runOnUiThread
                }
                voiceList = try { VoicePicker.usable(e.voices.orEmpty(), prefs.greetingDutch) } catch (_: Exception) { emptyList() }
                voiceEngines = try { e.engines } catch (_: Exception) { emptyList() }
                if (accentFilter.isNotEmpty() && voiceList.none { VoicePicker.accent(it) == accentFilter }) accentFilter = ""
                e.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == SAMPLE_ID) runOnUiThread { samplePlaying = null; refreshIfOn(Page.VOICE) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { if (id == SAMPLE_ID) runOnUiThread { samplePlaying = null; refreshIfOn(Page.VOICE) } }
                    override fun onError(id: String?, code: Int) { if (id == SAMPLE_ID) runOnUiThread { samplePlaying = null; refreshIfOn(Page.VOICE) } }
                })
                voiceTtsReady = true
                if (page == Page.VOICE) render()
            }
        }
        val engineName = VoicePicker.preferredEngine(this, prefs.ttsEngine)
        voiceTts = try {
            if (engineName != null) android.speech.tts.TextToSpeech(this, onInit, engineName)
            else android.speech.tts.TextToSpeech(this, onInit)
        } catch (e: Exception) { null }
    }

    private fun releaseVoiceEngine() {
        handler.removeCallbacks(sampleRunnable)
        voiceTts?.let { runCatching { it.stop(); it.shutdown() } }
        voiceTts = null
        voiceTtsReady = false
        samplePlaying = null
    }

    /**
     * A short line in [voice] (or the automatic choice) with the current speed and pitch. Tapping the
     * one that's already playing stops it, so you're never stuck waiting for a sample to finish.
     */
    /** Silences a voice that is only being tried out (the rows on the Voice page). */
    private fun stopVoiceSample() {
        runCatching { voiceTts?.stop() }
        samplePlaying = null
    }

    private fun playSample(voice: android.speech.tts.Voice?, automatic: Boolean = false) {
        val e = voiceTts ?: return
        if (!voiceTtsReady) return
        val dutch = prefs.greetingDutch
        val v = voice
            ?: (if (!automatic) voiceList.firstOrNull { it.name == prefs.voiceName } else null)
            ?: (if (prefs.butler && !dutch) VoicePicker.butlerVoice(voiceList) else null)
            ?: VoicePicker.best(voiceList, dutch)
        val id = if (automatic || v == null) AUTO_SAMPLE else v.name
        if (samplePlaying == id) {          // same one again: that's a stop
            runCatching { e.stop() }
            samplePlaying = null
            refreshIfOn(Page.VOICE)
            return
        }
        // Trying a voice while the full greeting is talking would play both at once.
        if (Voice.speaking) Voice.stop("you tried a different voice")
        try {
            if (v != null) e.voice = v else e.language = VoicePicker.localeFor(dutch)
            e.setSpeechRate(prefs.speechRate)
            e.setPitch(prefs.speechPitch)
            samplePlaying = id
            if (page == Page.VOICE) render()
            if (e.speak(shortSample(), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, SAMPLE_ID) != android.speech.tts.TextToSpeech.SUCCESS) {
                samplePlaying = null
                refreshIfOn(Page.VOICE)
            }
        } catch (ex: Exception) {
            samplePlaying = null
            toast("Couldn't play that voice: ${ex.message}")
        }
    }

    /** Speed/pitch taps: play a sample once you stop tapping. */
    private fun sampleSoon() {
        handler.removeCallbacks(sampleRunnable)
        handler.postDelayed(sampleRunnable, 600)
    }

    private fun shortSample(): String {
        val dutch = prefs.greetingDutch
        val name = SpeechText.spokenName(prefs.namePronunciation.ifBlank { prefs.greetingName }, dutch)
        val hello = GreetingText.timeGreeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), dutch)
        val who = if (name.isNotBlank()) ", $name" else ""
        return when {
            prefs.butler && dutch -> "$hello$who. Uw auto staat klaar, meneer, wanneer u maar wilt."
            prefs.butler -> "$hello$who. Your car is ready whenever you are, sir. Do take your time. I shan't."
            dutch -> "$hello$who. Zo klink ik in de auto."
            else -> "$hello$who. This is how I'll sound in the car."
        }
    }

    private fun setStyle(butler: Boolean) {
        val wasButler = prefs.butler
        prefs.greetingStyle = if (butler) "butler" else "sassy"
        if (butler && !wasButler) {
            if (!prefs.greetingDutch) {
                val v = if (voiceTtsReady) VoicePicker.butlerVoice(voiceList) else null
                when {
                    v != null -> {
                        prefs.voiceName = v.name
                        toast("Butler voice: ${VoicePicker.friendlyNames(voiceList)[v.name]?.first ?: v.name}")
                    }
                    voiceTtsReady -> toast("No British voice installed yet - see 'Get British voices' below")
                }
            }
            // A touch slower and lower: calm and composed.
            prefs.speechRate = 0.9f
            prefs.speechPitch = 0.9f
        } else if (!butler && wasButler) {
            prefs.speechRate = 0.95f
            prefs.speechPitch = 1.0f
        }
        DriveLog.log("Setting changed: greeting style = ${prefs.greetingStyle}")
        render()
        playSample(null, automatic = prefs.voiceName.isBlank())
    }

    private fun styleCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Style.button(if (selected) Style.HIGHLIGHT else Style.SURFACE, dp(Style.R_CARD).toFloat())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(6), dp(6), 0) }
        isClickable = true
        setOnClickListener { onClick() }
        addView(TextView(this@MainActivity).apply {
            text = title + if (selected) "  ✓" else ""; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
        })
        addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 13f; setTextColor(Style.TEXT2) })
    }

    private fun accentChips(accents: List<String>) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accents.forEach { acc ->
            val selected = accentFilter == acc
            bar.addView(TextView(this).apply {
                text = if (acc.isEmpty()) "All" else VoicePicker.accentName(acc)
                textSize = 14f; setTextColor(if (selected) Color.WHITE else Style.TEXT)
                background = Style.button(if (selected) Style.PRIMARY else Style.SURFACE2, dp(18).toFloat())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener { accentFilter = acc; render() }
            })
        }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        })
    }

    /** A voice: tap the row to choose it, ▶ to just listen. */
    private fun voiceRow(title: String, details: String, selected: Boolean, playing: Boolean, onPlay: () -> Unit, onSelect: () -> Unit) =
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Style.button(if (selected) Style.HIGHLIGHT else Style.SURFACE, dp(Style.R_CARD).toFloat())
            setPadding(dp(16), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            isClickable = true
            setOnClickListener { onSelect() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = (if (selected) "✓  " else "") + title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
                })
                if (details.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = details; textSize = 13f; setTextColor(Style.TEXT2); setPadding(0, dp(2), 0, 0)
                })
            })
            // Play icon (not the ▶ character, which some phones draw as a coloured emoji). Its corners
            // follow the card's: inner radius = outer radius - padding.
            addView(android.widget.ImageButton(this@MainActivity).apply {
                setImageResource(if (playing) R.drawable.ic_answer_no else R.drawable.ic_play); imageTintList = Style.tint(Style.TEXT)
                contentDescription = if (playing) "Stop" else "Listen"
                background = Style.button(Style.SURFACE2, dp(Style.R_CARD - 10).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(48))
                setOnClickListener { onPlay() }
                Feel.press(this, 0.9f)
            })
        })

    private fun renderDriveMode() {
        pageHeader()
        button("Open Drive Mode now", accent = true) { startActivity(Intent(this, DriveModeActivity::class.java)) }

        header("When it opens")
        switch("Open automatically when the car connects", prefs.autoOpenDriveMode) { prefs.autoOpenDriveMode = it; render() }
        if (prefs.autoOpenDriveMode) {
            text("The adapter or Android Auto connecting is enough - whichever comes first opens it once. " +
                "It takes the whole screen when the phone is locked or the screen is off; if you're using the " +
                "phone at that moment, Android shows a banner and Drive Mode opens by itself the moment the " +
                "screen goes off.")
            if (!fullScreenAllowed()) {
                warnCard("Android won't let Drive Mode take the screen yet: full-screen notifications are off for Drive Music.",
                    action = "Allow it") { openFullScreenSetting() }
            }
        }

        header("How it opens")
        switch("Intro animation", prefs.showSplash) { prefs.showSplash = it; render() }

        if (prefs.showSplash) {
            header("Intro")
            val hasImage = IntroImage.exists(this)
            stepperLike("Intro image: ${if (hasImage) "your picture" else "none (shows the text)"}", if (hasImage) "Change" else "Choose") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, REQ_INTRO_IMAGE)
            }
            if (hasImage) {
                button("Remove intro image", danger = true) {
                    AlertDialog.Builder(this).setTitle("Remove the intro image?")
                        .setMessage("The picture stays in your gallery; Drive Music deletes its own copy.")
                        .setPositiveButton("Remove") { _, _ -> IntroImage.clear(this); render() }
                        .setNegativeButton("Keep", null).show()
                }
            } else {
                stepperLike("Intro text: ${prefs.carName.ifBlank { "none" }}", "Change") {
                    askText("Text shown in the intro", "e.g. ASX", prefs.carName) { prefs.carName = it.take(12); render() }
                }
            }
            text("The intro plays every time Drive Mode opens - the button at the top of this page shows it now.")
        }
    }

    /** Themes and animations: how the whole app looks. A new theme shows up here by itself (Style.ALL_THEMES). */
    private fun renderThemes() {
        pageHeader()
        header("Theme")
        Style.ALL_THEMES.forEach { t ->
            val current = Style.theme(prefs.theme).id == t.id
            stepperLike(t.name + if (current) "  ✓" else "", if (current) "In use" else "Use") {
                if (!current) { prefs.theme = t.id; Style.apply(t.id); recreate() }
            }
        }
        header("Animations")
        switch("Animations (launch, pages, power-up sweep)", prefs.animations) {
            prefs.animations = it; Motion.enabled = it; render()
        }
        text("The Drive Mode intro has its own switch under Drive Mode.")
    }

    /** Everything the app keeps an eye on: confirmed codes, codes being checked, early codes, trends. */
    private fun watchIssues(): List<com.daand.drivemusic.obd.WatchList.Issue> {
        // One snapshot for both: the codes and the count that backs them must come from the same read.
        val live = if (prefs.obdEnabled) com.daand.drivemusic.obd.ObdManager.live() else null
        return com.daand.drivemusic.obd.WatchList.build(
        com.daand.drivemusic.obd.CodeTracker(prefs.storedCodeLog), com.daand.drivemusic.obd.CodeTracker(prefs.pendingCodeLog),
        live?.codes.orEmpty(), live?.codeCount,
        com.daand.drivemusic.obd.CarTrends.findings(com.daand.drivemusic.obd.TripHistory.load(this).mapNotNull { it.health }),
        prefs.greetingDutch)
    }

    /** "Possible issues": opened from the Fault box on Home. */
    private fun renderIssues() {
        val issues = watchIssues()
        val faults = issues.count { it.level == com.daand.drivemusic.obd.WatchList.Level.FAULT }
        pageHeader(icon = "⚠", state = when {
            issues.isEmpty() -> "Nothing to report"
            faults > 0 -> "$faults ${if (faults == 1) "fault" else "faults"} · ${issues.size} tracked"
            else -> "${issues.size} being watched"
        })
        if (issues.isEmpty()) {
            text(if (!prefs.obdEnabled) "Switch on Car data (OBD adapter) and the app starts watching your car."
                else "Nothing to report. The app watches for fault codes (each one is checked on several reads before it " +
                    "counts), early codes the car isn't sure about yet, and slow changes over your drives - fuel mixture, " +
                    "charging, idle, warm-up and running temperature.")
        }
        val names = mapOf(
            com.daand.drivemusic.obd.WatchList.Level.FAULT to "Faults",
            com.daand.drivemusic.obd.WatchList.Level.CHECKING to "Being checked",
            com.daand.drivemusic.obd.WatchList.Level.EARLY to "Early warnings",
            com.daand.drivemusic.obd.WatchList.Level.TREND to "Slow changes",
        )
        issues.groupBy { it.level }.forEach { (level, list) ->
            header(names.getValue(level))
            list.forEach { i ->
                addRow(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(14), dp(12)); minimumHeight = dp(56)
                    addView(TextView(this@MainActivity).apply {
                        text = i.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (level == com.daand.drivemusic.obd.WatchList.Level.FAULT) Style.AMBER else Style.TEXT)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = i.detail; textSize = 13f; setTextColor(Style.TEXT2); setPadding(0, dp(3), 0, 0)
                    })
                })
            }
        }
        if (issues.isNotEmpty()) text("Codes are only read, never cleared - clearing hides a problem instead of fixing it. " +
            "Live values and the full diagnostics are on Car data.")
        if (prefs.obdEnabled) button("Open Car data") { go(Page.OBD) }
    }

    /** Service has its own page: the reminder works by date without the adapter too. */
    private fun renderService() {
        pageHeader()
        serviceSection(heading = false)
        if (!prefs.obdEnabled) text("Counting down by km needs the OBD adapter (Car data); by date works without it.")
    }

    /** [heading]: on Car data it needs one; on its own page the page title already says "Service". */
    private fun serviceSection(heading: Boolean = true) {
        if (heading) header("Service")
        switch("Remind me in the greeting when it's due", prefs.serviceReminderOn) { prefs.serviceReminderOn = it; render() }
        if (prefs.serviceReminderOn) {
            val trips = com.daand.drivemusic.obd.TripHistory.load(this)
            val synced = prefs.serviceSyncAt > 0 && prefs.serviceSyncAt >= prefs.serviceDate
            val left = if (synced) com.daand.drivemusic.obd.CarAlerts.serviceLeft(prefs.serviceSyncAt, prefs.serviceSyncKm, prefs.serviceSyncDays,
                com.daand.drivemusic.obd.TripHistory.kmSince(trips, prefs.serviceSyncAt), System.currentTimeMillis()) else null
            stepperLike("Same as the car's dashboard: " + when {
                left == null -> "not synced"
                left.first <= 0 || left.second <= 0 -> "service due now"
                else -> "≈ %,d km · %d days left".format(java.util.Locale.ROOT, left.first, left.second)
            }, "Sync") { syncServiceWithCar() }
            text("The ASX keeps its own service reminder in the instrument cluster, which an OBD adapter can't read " +
                "(and the app never writes to the car). Type in what the dashboard shows and the app counts down " +
                "with your drives, so both remind you at the same time. After a service, sync again.")
            val svc = if (prefs.serviceDate > 0) java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(prefs.serviceDate)) else "not set"
            stepperLike("Or by date - last service: $svc", "Change") { pickServiceDate() }
            if (prefs.serviceDate > 0 && !synced) {
                val km = com.daand.drivemusic.obd.TripHistory.kmSince(trips, prefs.serviceDate)
                stepper("Service every", "%,d km".format(java.util.Locale.ROOT, prefs.serviceKm),
                    { prefs.serviceKm = (prefs.serviceKm - 1000).coerceAtLeast(1000) },
                    { prefs.serviceKm = (prefs.serviceKm + 1000).coerceAtMost(60000) })
                text("Driven since: %,d km (counted from your drives with the adapter).".format(java.util.Locale.ROOT, Math.round(km)))
            }
        }
    }

    private fun renderUpdates() {
        pageHeader()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val upd = availableUpdate

        header("This phone")
        statRow("Installed version", version)
        if (prefs.updateStatus.isNotEmpty() && updateMessage.isEmpty()) statRow("Last update", prefs.updateStatus)
        if (updateMessage.isNotEmpty()) text(updateMessage)

        header("Update")
        if (upd == null) {
            text("No update waiting to install." + if (prefs.autoUpdateCheck) " The app checks once a day by itself, or tap below to check now." else " Tap below to check now.")
        } else {
            statRow("Available", "${upd.versionName} - ready to install")
            if (upd.notes.isNotBlank()) text(upd.notes.take(400))
            button(if (updateBusy) "Downloading..." else "⬇  Install ${upd.versionName}", accent = true,
                enabled = !updateBusy) { if (!updateBusy) installUpdate(upd) }
            text("Drive Music checks the file's checksum and signature before handing it to Android, " +
                "and Android always asks you to confirm.")
        }
        button(if (updateBusy) "Working..." else "Check for updates now", enabled = !updateBusy) {
            if (!updateBusy) checkForUpdate(silent = false)
        }
        switch("Check automatically (daily)", prefs.autoUpdateCheck) { prefs.autoUpdateCheck = it }
    }

    private fun renderHelp() {
        pageHeader()

        header("If the music keeps pausing")
        text("• Samsung: put YouTube Music, Android Auto, MicroG and Drive Music in 'Never sleeping apps'.\n" +
            "• YouTube Music: turn on Smart downloads; turn off 'Remind me to take a break'.\n" +
            "• Logs show GOOGLE ASSISTANT before pauses? Lower 'Hey Google' sensitivity.\n" +
            "• Wired Android Auto dropping? Try a short, good USB cable.\n" +
            "• Music doesn't start at all? Music & driving → check which app it plays from.")
        button("Open the drive logs") { startActivity(Intent(this, LogActivity::class.java)) }

        header("The car screen")
        navRow("Car screen setup", "Show Drive Music in Android Auto") { go(Page.CAR) }

        header("Privacy")
        text("• Internet only for update checks (GitHub), weather (Open-Meteo, location rounded to ~10 km; in NL/BE also the Buienradar rain radar, rounded to ~5 km), fuel prices " +
            "(ANWB's station database: a box of about 15 km around a position rounded to ~1 km) and, if on, " +
            "drive news (NU.nl, NOS, NL Times and regional newsrooms; Google News is searched for your town name).\n" +
            "• Notifications are never read. Logs stay on the phone unless you share them.\n" +
            "• Where's my car (optional): your parked spot is saved on the phone only, never sent anywhere.\n" +
            "• The premium voice (if you set it up) sends the spoken text to Microsoft, and nothing else.\n" +
            "• No overlay, accessibility, contacts, SMS or storage access.")

        header("Still stuck?")
        text("Home → Music & driving is where the music settings live; Home → Setup runs the checklist again and " +
            "explains a greyed-out switch.\nFor help from someone else: Home → Logs → Share sends a file with what " +
            "happened, your Android version and your music app.")
    }

    private fun renderCar() {
        pageHeader()
        text("Android Auto hides apps that aren't from the Play Store until you allow them once. " +
            "This takes a minute and survives updates.")
        button("Open Android Auto settings", accent = true) { openAndroidAutoSettings() }
        header("In Android Auto")
        stepRow(1, "Scroll down to Version (Versie) and tap it about 10 times → OK.")
        stepRow(2, "Tap ⋮ (top right) → Developer settings (Ontwikkelaarsinstellingen).")
        stepRow(3, "Tick Unknown sources (Onbekende bronnen).")
        stepRow(4, "Back in the Android Auto settings → Customise launcher (Launcher aanpassen) → tick Drive Music.")
        stepRow(5, "Unplug the phone and reconnect it.")
        header("What you get")
        text("Drive Music in the car's app grid: now playing, the media controls and the Welcome button " +
            "(which also skips the greeting while it speaks).")
    }

    private fun openAndroidAutoSettings() {
        val aa = DriveMonitorService.ANDROID_AUTO
        val launch = packageManager.getLaunchIntentForPackage(aa)
        try {
            if (launch != null) startActivity(launch) else openAppSettings(aa)
        } catch (e: Exception) {
            toast("Open Settings → Connected devices → Connection preferences → Android Auto")
        }
    }

    private fun renderBackup() {
        pageHeader()
        header("What a backup holds")
        text("Your name, voice, location, car and adapter details, service intervals and every switch - " +
            "so you don't have to set the app up again after a reinstall or a new phone.\n" +
            "Not in it: your trips, battery history, logs and pictures (those are yours, and they stay on the phone).")

        header("Save")
        button("Save settings to a file", accent = true) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "drive-music-settings.json")
            }, REQ_EXPORT)
        }
        header("Restore")
        button("Restore settings from a file") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }
        text("A restore only changes what the file contains - anything it doesn't mention stays as it is on this phone.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(prefs.exportJson().toByteArray()) }
                    toast("Settings saved")
                    DriveLog.log("Settings backed up")
                }
                REQ_CAR_PHOTO -> saveImageInBackground(uri, IntroImage.HERO, "Car photo set")
                REQ_INTRO_IMAGE -> saveImageInBackground(uri, null, "Intro image set")
                REQ_IMPORT -> {
                    // A settings backup is a few KB; refuse anything big (picked the wrong file?).
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(256 * 1024 + 1) }
                    require(bytes.size <= 256 * 1024) { "that file is too big to be a settings backup" }
                    val json = bytes.toString(Charsets.UTF_8)
                    val n = prefs.importJson(json)
                    toast("Restored $n settings")
                    DriveLog.log("Settings restored ($n)")
                    render()
                }
            }
        } catch (e: Exception) {
            toast("Couldn't ${when (requestCode) { REQ_EXPORT -> "save"; REQ_INTRO_IMAGE, REQ_CAR_PHOTO -> "use that image"; else -> "restore" }}: ${e.message}")
        }
    }

    /** Decoding/scaling a big photo can take a moment - don't freeze the screen while it does. */
    private fun saveImageInBackground(uri: android.net.Uri, name: String?, done: String) {
        toast("Processing image...")
        Thread {
            val error = try {
                if (name == null) IntroImage.save(this, uri) else IntroImage.save(this, uri, name)
                null
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName
            } catch (e: OutOfMemoryError) { "image is too big" }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (error == null) done else "Couldn't use that image: $error")
                render()
            }
        }.start()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- home hero (car + status)

    /**
     * Keeps now-playing / driving status fresh - but only redraws when something on the home screen
     * actually changed. (Rebuilding the whole screen every few seconds while it stays on costs battery.)
     */
    private val homeTick = object : Runnable {
        override fun run() {
            if (page == Page.HOME && homeQuery.isBlank()) {   // never redraw under someone typing a search
                val sig = homeSignature()
                if (sig != lastHomeSignature) render()
            } else if (page == Page.OBD && prefs.obdEnabled) render()  // live values
            handler.postDelayed(this, 5000)
        }
    }

    private var lastHomeSignature = ""

    /** Everything the home screen shows that can change on its own (the rest changes via taps / resume). */
    private fun homeSignature(): String {
        val svc = DriveMonitorService.instance
        val weatherFresh = System.currentTimeMillis() - prefs.lastWeatherAt < 3 * 60 * 60 * 1000L
        return listOf(svc != null, prefs.lastCarState, svc?.currentSong(), svc?.currentArt()?.let { System.identityHashCode(it) },
            prefs.lastWeatherSummary, weatherFresh,
            prefs.lastDrive, prefs.parkedSpot, availableUpdate?.versionName,
            // The adapter question and "the car is off": show up (and go away) without a manual refresh.
            prefs.adapterPromptPending, com.daand.drivemusic.obd.ObdManager.carOffForMs() / 60_000L,
            fullScreenMissing(), Voice.speaking, MusicApps.isInstalled(packageManager, prefs.musicPackage),
            // Car data: rounded so the screen only redraws when the shown value changes.
            com.daand.drivemusic.obd.ObdManager.live()?.let { "${it.coolantC}|${it.volts?.let { v -> Math.round(v * 10) }}|${it.codes}" }).joinToString("|")
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(homeTick)
        releaseVoiceEngine()
    }

    /** Tesla-style header: car name and status, the car drawn in the theme colour, status tiles around it. */
    /**
     * Your car: the photo fills the top of the card (a faint outline + "add a photo" without one, same
     * size so nothing jumps), the name and status over it, three numbers along its bottom edge - from
     * the last drive when parked, live while driving - then the song and the quick buttons.
     */
    private fun carCard() {
        val svc = DriveMonitorService.instance
        val driving = svc != null && prefs.lastCarState == "Android Auto connected"
        val holding = prefs.lastCarState == DriveHold.STATE
        val car = if (prefs.obdEnabled) com.daand.drivemusic.obd.ObdManager.live() else null
        val card = android.widget.FrameLayout(this).apply {
            background = Style.rounded(Style.SURFACE, dp(Style.R_BIG).toFloat()); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val photo = IntroImage.load(this, IntroImage.HERO)
        val photoMenu = {
            AlertDialog.Builder(this).setTitle("Car photo")
                .setItems(arrayOf("Change photo", "Remove photo")) { _, i ->
                    if (i == 0) pickCarPhoto() else { IntroImage.clear(this, IntroImage.HERO); render() }
                }.show()
        }
        val top: View = if (photo != null) CarPhotoView(this, photo).apply {
            fadeTo = Style.SURFACE
            if (animateNext == ENTER) Motion.settle(this)
            setOnClickListener { go(Page.OBD) }
            setOnLongClickListener { photoMenu(); true }
        } else android.widget.FrameLayout(this).apply {
            minimumHeight = dp(180)
            addView(TextView(this@MainActivity).apply {
                text = "🚗"; textSize = 96f; alpha = 0.12f; gravity = android.view.Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(180)))
            addView(TextView(this@MainActivity).apply {
                text = "📷  Add a photo of your car\nstays on your phone"; textSize = 13f; setTextColor(Style.TEXT)
                gravity = android.view.Gravity.CENTER; minHeight = dp(48)
                background = Style.button(Style.SURFACE2, dp(Style.R_CARD).toFloat()); setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { pickCarPhoto() }
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER).apply { topMargin = dp(28) })
        }
        column.addView(top)

        // Three numbers along the photo's bottom edge.
        fun v(d: Double?, fmt: String) = d?.let { fmt.format(java.util.Locale.ROOT, it) } ?: "–"
        val lastMins = Regex("""- (\d+) min""").find(prefs.lastDrive)?.groupValues?.get(1)
        val stats: List<Triple<String, String, (() -> Unit)?>> = if (driving) listOf(
            Triple("Speed", car?.speedKmh?.let { "$it km/h" } ?: "–", null),
            Triple("Engine", car?.coolantC?.let { "$it °C" } ?: "–") { go(Page.OBD) },
            Triple("Battery", v(car?.volts, "%.1f V")) { go(Page.OBD) },
        ) else listOf(
            Triple("Last drive", when (lastMins) { null -> "–"; "0" -> "< 1 min"; else -> "$lastMins min" }) { lastDriveDetails() },
            Triple("Battery", v(car?.volts, "%.1f V")) { go(Page.OBD) },
            Triple("Fault", faultBox()) { go(Page.ISSUES) },
        )
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(8))
            stats.forEach { (label, value, action) ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                    background = Style.button(Style.SURFACE2, dp(12).toFloat()); setPadding(dp(4), dp(8), dp(4), dp(8))
                    minimumHeight = dp(56)
                    if (action != null) { isClickable = true; setOnClickListener { action() }; Feel.press(this, 0.96f) }
                    val warn = label == "Fault" && Regex("""^[PCBU]\d""").containsMatchIn(value)
                    addView(TextView(this@MainActivity).apply {
                        text = value; textSize = 17f; setTypeface(typeface, Typeface.BOLD); fontFeatureSettings = Style.TABULAR
                        setTextColor(if (warn) Style.AMBER else Style.TEXT); gravity = android.view.Gravity.CENTER
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = label; textSize = 11f; setTextColor(Style.TEXT2); gravity = android.view.Gravity.CENTER
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        })

        // While driving: the song (cover + title on one line, full width), its controls on a line of
        // their own underneath - side by side, the three buttons squeezed the title to a letter or two.
        val song = svc?.currentSong()
        if (driving && svc != null) column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.rounded(Style.SURFACE2, dp(12).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(12), 0, dp(12), dp(8)) }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true; setOnClickListener { openMusicApp() }
                // The cover, as the music app gives it (nothing is downloaded).
                svc.currentArt()?.let { art ->
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageBitmap(art); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = Style.rounded(Style.SURFACE, dp(8).toFloat()); clipToOutline = true
                        contentDescription = "Cover"
                    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = song?.title ?: "Nothing playing"; textSize = 14f; setTextColor(Style.TEXT)
                        setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    song?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                        addView(TextView(this@MainActivity).apply {
                            text = artist; textSize = 13f; setTextColor(Style.TEXT2); maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("⏮" to { svc.skipPrevious() }, "⏯" to { svc.playPause() }, "⏭" to { svc.skipNext() }).forEach { (sym, act) ->
                    addView(TextView(this@MainActivity).apply {
                        text = sym; textSize = 22f; setTextColor(Style.TEXT); gravity = android.view.Gravity.CENTER
                        background = Style.button(android.graphics.Color.TRANSPARENT, dp(10).toFloat())
                        contentDescription = when (sym) { "⏮" -> "Previous"; "⏯" -> "Play or pause"; else -> "Next" }
                        setOnClickListener { act(); handler.postDelayed({ refreshIfOn(Page.HOME) }, 600) }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
            })
        })

        // Quick buttons: start the music now, hear the greeting (or stop it), find the car.
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(4), dp(10))
            addView(quickBtn("▶  Music") {
                if (svc == null) toast("Allow notification access first (Setup)")
                else { svc.startMusic("manual test button", requireCar = false); toast("Starting...") }
            })
            addView(quickBtn(if (Voice.speaking) "\u23F9  Stop" else "🎙  Greeting") {
                if (Voice.speaking) Voice.stop("stop button on the home screen")
                else {
                    stopVoiceSample()   // a voice sample must not talk through the greeting
                    toast("Speaking...")
                    WelcomeAnnouncer.run(this@MainActivity, svc?.currentSong(), recordAsGreeted = false) { refreshIfOn(Page.HOME) }
                }
                refreshIfOn(Page.HOME)
            })
            addView(quickBtn("📍  Car") { whereIsMyCar() })
            // Same height for all three, even when one label wraps at a big font.
            for (i in 0 until childCount) getChildAt(i).layoutParams.height = LinearLayout.LayoutParams.MATCH_PARENT
        })
        card.addView(column)

        // Name and status over the top of the photo.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0)
            addView(TextView(this@MainActivity).apply {
                text = "DRIVE MUSIC"; textSize = 10f; letterSpacing = 0.3f; setTextColor(Style.TEXT2)
            })
            addView(TextView(this@MainActivity).apply {
                text = prefs.carName.ifBlank { "My car" }; textSize = 32f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Style.TEXT); setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
            })
            val weatherFresh = System.currentTimeMillis() - prefs.lastWeatherAt < 3 * 60 * 60 * 1000L
            val weather = if (weatherFresh && prefs.lastWeatherSummary.isNotBlank()) "  ·  ${prefs.lastWeatherSummary}" else ""
            addView(TextView(this@MainActivity).apply {
                text = when {
                    svc == null -> "●  Setup needed"
                    driving -> "●  Driving" + com.daand.drivemusic.obd.ObdManager.trip.km.let { km -> if (km >= 0.5) "  ·  %.0f km so far".format(java.util.Locale.ROOT, km) else "" }
                    holding -> "●  Android Auto reconnecting…"
                    else -> "●  Parked$weather"
                }
                textSize = 13f; setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                setTextColor(when { svc == null || holding -> Style.AMBER; driving -> Style.GREEN; else -> Style.TEXT })
                if (driving || holding) Motion.pulse(this)
            })
        }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(card)
    }

    private fun openMusicApp() {
        packageManager.getLaunchIntentForPackage(prefs.musicPackage)?.let { startActivity(it) } ?: toast("Music app not installed")
    }

    /** Weather is switched on but has no place to look up: it would silently never be told. */
    private fun needsTown() = prefs.greetingEnabled && prefs.weatherEnabled && prefs.weatherLat.isNaN() &&
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED

    private fun lastDriveDetails() {
        AlertDialog.Builder(this).setTitle("Last drive")
            .setMessage(prefs.lastDrive.replace(" - 0 min,", " - under a minute,").ifBlank { "No drives yet - connect to Android Auto and it shows up here." })
            .setPositiveButton("Open logs") { _, _ -> startActivity(Intent(this, LogActivity::class.java)) }
            .setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------- where's my car

    private fun whereIsMyCar() {
        val spot = Parking.spot(this)
        val tracker = Parking.trackerApp(this)
        val on = prefs.rememberParking && Parking.hasPermission(this) && Parking.hasBackgroundPermission(this)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (spot != null) items += "Walk there in Maps" to {
            try { startActivity(Parking.mapsIntent(spot)) } catch (e: Exception) { toast("No maps app found") }
        }
        if (tracker != null) items += "Open ${tracker.second} (tracker)" to { startActivity(tracker.first) }
        items += (if (on) "Stop remembering where I park" else "Remember where I park") to {
            if (on) { prefs.rememberParking = false; DriveLog.log("Setting changed: remember parking = off"); render() }
            else enableParking()
        }
        val msg = when {
            spot != null -> "Saved ${Parking.whenText(spot.time)} when Android Auto disconnected (±${spot.accuracyM.toInt()} m)."
            on -> "Your car's spot is saved the next time Android Auto disconnects."
            else -> "Turn this on and the app saves where you parked each time Android Auto disconnects. " +
                "The spot stays on your phone."
        } + if (tracker == null) "" else "\n\nYour tracker's live position is only visible in ${tracker.second} - it doesn't share it with other apps."
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val dialog = AlertDialog.Builder(this).setTitle("Where's my car?").setView(box)
            .setNegativeButton("Close", null).create()
        box.addView(TextView(this).apply { text = msg; textSize = 14f; setPadding(0, 0, 0, dp(8)) })
        items.forEach { (label, action) ->
            box.addView(TextView(this).apply {
                text = label; textSize = 16f; setTextColor(Style.PRIMARY); setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        dialog.show()
    }

    /** Fine location first, then "Allow all the time" (Android asks for those separately). */
    private fun enableParking() {
        when {
            !Parking.hasPermission(this) ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_PARKING)
            !Parking.hasBackgroundPermission(this) ->
                AlertDialog.Builder(this).setTitle("One more step")
                    .setMessage("The car disconnects while the app is in the background, so on the next screen choose " +
                        "\"Allow all the time\". The location is only read at that moment, and it stays on your phone.")
                    .setPositiveButton("Continue") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_PARKING)
                    }.setNegativeButton("Cancel", null).show()
            else -> {
                prefs.rememberParking = true
                DriveLog.log("Setting changed: remember parking = on")
                toast("I'll remember where you park")
                render()
            }
        }
    }

    private fun pickCarPhoto() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }, REQ_CAR_PHOTO)
    }


    /** Title row for a sub-page with a back arrow. */
    /** The page's header: the home icon grown up - back, icon, name and the same state line as on Home. */
    private fun pageHeader(back: Page = Page.HOME, icon: String? = null, state: String? = null) {
        group = null
        val f = HomeFeatures.forPage(page.name) ?: icon?.let { Feature("", FeatureGroup.CAR, page.title, it, emptyList()) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "‹"; textSize = 28f; setTextColor(Style.TEXT2); gravity = android.view.Gravity.CENTER
                contentDescription = "Back"; setOnClickListener { go(back, back = true) }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            if (f != null) addView(TextView(this@MainActivity).apply {
                text = f.icon; textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(Style.TEXT)
                background = Style.rounded(Style.SURFACE, dp(14).toFloat())
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = page.title; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
                })
                if (f != null) addView(TextView(this@MainActivity).apply {
                    text = state ?: featureState(f.id); textSize = 13f; setTextColor(Style.TEXT2)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }


    /** Menu row: title + short subtitle, opens something when tapped. */
    private fun navRow(title: String, subtitle: String, highlight: Boolean = false, warn: Boolean = false, onClick: () -> Unit) =
        (if (highlight || warn) ::addCard else ::addRow)(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = if (highlight || warn) Style.button(if (highlight) Style.HIGHLIGHT else Style.WARN_BG, dp(Style.R_CARD).toFloat())
                else Style.button(Color.TRANSPARENT, 0f)
            setPadding(dp(16), dp(14), dp(14), dp(14))
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8); layoutParams = it
            }
            isClickable = true
            setOnClickListener { onClick() }
            Feel.press(this, 0.98f)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle; textSize = 13f; setTextColor(Style.TEXT2); setPadding(0, dp(2), 0, 0)
                })
            })
            addView(TextView(this@MainActivity).apply { text = "›"; textSize = 24f; setTextColor(Style.TEXT2) })
        })

    /** Half-width button for the home screen's quick actions. */
    private fun quickBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(Style.TEXT)
        background = Style.button(Style.SURFACE2, dp(Style.R_CARD).toFloat()); stateListAnimator = null
        setPadding(dp(12), dp(14), dp(12), dp(14))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, dp(6), dp(6), 0)
        }
        setOnClickListener { onClick() }
        Feel.press(this)
    }

    // ---------------------------------------------------------------- greeting settings

    /**
     * A setting as a card: "Name: value" shows the name small on top and the value big underneath,
     * with the action as an accent word on the right. The whole card is the tap target.
     */
    private fun stepperLike(label: String, action: String, onClick: () -> Unit) {
        val cut = label.indexOf(": ")
        val title = if (cut > 0) label.substring(0, cut) else label
        val value = if (cut > 0) label.substring(cut + 2) else ""
        addRow(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Style.button(Color.TRANSPARENT, 0f)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            minimumHeight = dp(56)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8); layoutParams = it
            }
            isClickable = true
            contentDescription = "$label. $action"
            setOnClickListener { onClick() }
            Feel.press(this, 0.98f)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = title; textSize = if (value.isEmpty()) 15f else 13f
                    setTextColor(if (value.isEmpty()) Style.TEXT else Style.TEXT2)
                })
                if (value.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                    text = value; textSize = 16f; setTextColor(Style.TEXT); setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = action; textSize = 14f; setTextColor(Style.PRIMARY); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(4), dp(8))
            })
        })
    }

    /** Battery before each start: how long the car stood, adapter in or out, and the drain check. */
    private fun batterySection() {
        val events = prefs.batteryEvents
        if (events.isEmpty()) return
        header("Battery")
        val dutch = prefs.greetingDutch
        val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault())
        events.takeLast(8).reversed().forEach { e ->
            statRow(fmt.format(java.util.Date(e.at)), "%.2f V · %s · %s".format(java.util.Locale.ROOT, e.volts,
                com.daand.drivemusic.obd.BatteryLog.parkedText(e.parkedMin, dutch), com.daand.drivemusic.obd.BatteryLog.adapterText(e.adapter, dutch)))
        }
        val c = com.daand.drivemusic.obd.BatteryLog.compare(events)
        text(when {
            c == null -> "Read with the ignition on before starting (lights and radio draw on it, so ~11.5-12.3 V is normal). " +
                "Answer the 'take the adapter out' reminder and after a few nights parked with it in and out, this shows whether it drains the battery."
            c.drainSuspected -> "⚠ After 12 h+ parked: %.2f V with the adapter left in vs %.2f V taken out - it looks like the adapter drains the battery. Take it out when parking for longer."
                .format(java.util.Locale.ROOT, c.leftIn, c.out)
            else -> "✓ After 12 h+ parked: %.2f V with the adapter left in vs %.2f V taken out - no sign that it drains the battery."
                .format(java.util.Locale.ROOT, c.leftIn, c.out)
        })
        if (com.daand.drivemusic.obd.CarAlerts.batteryTrendWeak(prefs.batteryLog)) warnCard("The battery is getting weaker over the last days.")
    }

    /**
     * An amber card for something that needs attention. With an [action] it carries the fix itself, so a
     * warning never sits there without a way out of it.
     */
    private fun warnCard(msg: String, action: String? = null, onClick: (() -> Unit)? = null) =
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.rounded(Style.WARN_BG, dp(Style.R_CARD).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8); layoutParams = it
            }
            addView(TextView(this@MainActivity).apply {
                text = "⚠  $msg"; textSize = 14f; setTextColor(Style.TEXT); setLineSpacing(dp(2).toFloat(), 1f)
            })
            if (action != null && onClick != null) addView(Button(this@MainActivity).apply {
                text = action; isAllCaps = false; textSize = 14f; setTextColor(Style.ON_BRIGHT)
                background = Style.button(Style.AMBER, dp(12).toFloat()); stateListAnimator = null
                setPadding(dp(18), dp(10), dp(18), dp(10)); minHeight = dp(44)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
                Feel.press(this); setOnClickListener { onClick() }
            })
        })

    /** A numbered instruction: the step number in a bubble, the instruction beside it. */
    private fun stepRow(n: Int, instruction: String) = addRow(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.TOP
        background = null
        setPadding(dp(14), dp(12), dp(14), dp(12))
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(8); layoutParams = it
        }
        addView(TextView(this@MainActivity).apply {
            text = "$n"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setTextColor(Style.ON_BRIGHT); background = Style.rounded(Style.AMBER, dp(13).toFloat())
            val sz = dp(26); layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
        })
        addView(TextView(this@MainActivity).apply {
            text = instruction; textSize = 14f; setTextColor(Style.TEXT); setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    })

    /** Copy the car's own service reminder: km left, then time left (days, weeks or months). */
    private fun syncServiceWithCar() = askText("Service: km left", "What the dashboard shows, e.g. 3200", "") { kmIn ->
        val km = kmIn.filter { it.isDigit() || it == '-' }.toIntOrNull()
        if (km == null) { toast("Type the km the dashboard shows"); return@askText }
        askText("Service: time left", "e.g. 45 days or 2 months", "") { timeIn ->
            val days = com.daand.drivemusic.obd.CarAlerts.parseDays(timeIn)
            if (days == null) { toast("Type e.g. 45 days or 2 months"); return@askText }
            prefs.serviceSyncKm = km.coerceIn(-60_000, 60_000); prefs.serviceSyncDays = days
            prefs.serviceSyncAt = System.currentTimeMillis(); prefs.lastServiceReminder = 0L
            DriveLog.log("Setting changed: service synced with the dashboard ($km km, $days days)")
            render()
        }
    }

    /** A number input (comma or dot both fine); empty = 0. */
    private fun askNumber(title: String, hint: String, initial: Double, onOk: (Double) -> Unit) =
        askText(title, hint, if (initial > 0) "%.2f".format(java.util.Locale.ROOT, initial).trimEnd('0').trimEnd('.') else "") { input ->
            val v = input.replace(',', '.').trim().toDoubleOrNull() ?: 0.0
            onOk(v); render()
        }

    private fun pickServiceDate() {
        val c = java.util.Calendar.getInstance().apply { if (prefs.serviceDate > 0) timeInMillis = prefs.serviceDate }
        android.app.DatePickerDialog(this, { _, y, m, d ->
            prefs.serviceDate = java.util.Calendar.getInstance().apply { set(y, m, d, 12, 0) }.timeInMillis
            prefs.lastServiceReminder = 0L
            render()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    // ---------------------------------------------------------------- trips & costs

    /**
     * Fuel price for the cost per trip: looked up for the area you're in (ANWB's station database,
     * every Dutch fuel station, no account), with the price you type in as the fallback.
     */
    private fun fuelPriceSection() {
        val fuel = FuelPrices.Fuel.of(prefs.fuelType)
        switch("Look up the fuel price for my area", prefs.fuelPriceAuto) { on ->
            prefs.fuelPriceAuto = on
            DriveLog.log("Setting changed: automatic fuel price = $on")
            if (on) refreshFuelPrice("you turned automatic lookup on")
            render()
        }
        if (prefs.fuelPriceAuto) {
            val quoted = prefs.fuelPriceQuoted
            val at = prefs.fuelPriceQuoteAt
            when {
                quoted > 0 && System.currentTimeMillis() - at <= FuelPrices.USABLE_MS -> {
                    stepperLike("${fuel.en}: €%.3f per litre".format(java.util.Locale.ROOT, quoted), "Refresh") {
                        refreshFuelPrice("you tapped refresh")
                    }
                    if (prefs.fuelPriceQuoteSource.isNotEmpty()) text(prefs.fuelPriceQuoteSource + " · " + ageText(at))
                }
                fuelPriceFetching -> button("Looking up the price around you...", enabled = false) { }
                quoted > 0 -> {
                    stepperLike("${fuel.en}: €%.3f (out of date)".format(java.util.Locale.ROOT, quoted), "Refresh") {
                        refreshFuelPrice("you tapped refresh")
                    }
                    text("Older than a week: costs use the price you typed in until this is refreshed.")
                }
                else -> {
                    stepperLike("${fuel.en}: not looked up yet", "Look up") { refreshFuelPrice("you tapped look up") }
                    text("Needs your location (approximate is enough) and internet. The position is rounded to about a kilometre before it is sent.")
                }
            }
            stepperLike("Fuel: ${fuel.en}", "Change") {
                android.app.AlertDialog.Builder(this).setTitle("Which fuel does the car take?")
                    .setItems(FuelPrices.Fuel.values().map { it.en }.toTypedArray()) { _, i ->
                        prefs.fuelType = FuelPrices.Fuel.values()[i].code
                        refreshFuelPrice("you changed the fuel type"); render()
                    }.show()
            }
        }
        stepperLike(
            (if (prefs.fuelPriceAuto) "Price if the lookup fails: " else "Fuel price: ") +
                (if (prefs.fuelPrice > 0) "€%.2f per litre".format(java.util.Locale.ROOT, prefs.fuelPrice) else "not set"),
            "Change",
        ) {
            askNumber("Fuel price per litre (€)", "e.g. 2.05 - leave empty to hide costs", prefs.fuelPrice) {
                // Say so when the number is out of range instead of quietly storing something else (tank
                // size already works that way) - a typo like "20" used to become €10 without a word.
                if (it < 0.0 || it > 10.0) toast("Enter a price between €0 and €10 per litre")
                else { prefs.fuelPrice = it; render() }
            }
        }
    }

    private fun ageText(at: Long): String {
        val mins = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 2 -> if (prefs.greetingDutch) "net opgehaald" else "just fetched"
            mins < 60 -> if (prefs.greetingDutch) "$mins min geleden" else "$mins min ago"
            mins < 48 * 60 -> if (prefs.greetingDutch) "${mins / 60} u geleden" else "${mins / 60} h ago"
            else -> if (prefs.greetingDutch) "${mins / (24 * 60)} dagen geleden" else "${mins / (24 * 60)} days ago"
        }
    }

    /**
     * One request for the stations in a box around you, off the main thread. Called when the app opens
     * (at most every few hours) and when you tap Look up / Refresh.
     */
    private fun refreshFuelPrice(reason: String) {
        if (!prefs.fuelPriceAuto || fuelPriceFetching) return
        val fuel = FuelPrices.Fuel.of(prefs.fuelType)
        fuelPriceFetching = true
        // Why it happened: three lookups in half a minute in your log looked like a bug until the cause
        // (you tapping "refresh") was in the line as well.
        DriveLog.log("FUEL: looking up the price ($reason)")
        Thread({
            val here = com.daand.drivemusic.DriveUpdates.whereAreWe(this)
                ?: prefs.weatherLat.takeIf { !it.isNaN() }?.let { lat -> prefs.weatherLon.takeIf { !it.isNaN() }?.let { lon -> lat to lon } }
            val quote: FuelPrices.Quote? = if (here == null) {
                // Nothing was asked of the network, so this must not start the "don't try again" timer:
                // granting location (or one drive, which sets weatherLat) has to be enough on its own.
                DriveLog.log("FUEL: no position yet - keeping the manual price")
                null
            } else {
                // A real attempt: remembered even when it fails, so opening the app a minute later
                // doesn't hammer a server that just said no.
                prefs.fuelPriceTriedAt = System.currentTimeMillis()
                try {
                    FuelPrices.fetch(here.first, here.second, fuel)
                } catch (e: Throwable) {
                    // Throwable, not Exception: a huge answer can fail with an Error, and then the flag
                    // below would stay set and the page would say "looking up..." for the whole session.
                    DriveLog.log("FUEL: lookup failed (${e.javaClass.simpleName}: ${e.message})")
                    null
                }
            }
            runOnUiThread {
                fuelPriceFetching = false
                if (quote != null) {
                    prefs.fuelPriceQuoted = quote.euro
                    prefs.fuelPriceQuoteAt = System.currentTimeMillis()
                    prefs.fuelPriceQuoteSource = if (quote.national) {
                        // The station service had nothing, so this is the official country-wide figure:
                        // say so, or it looks like the price at the pump around the corner.
                        val month = FuelPrices.periodLabel(quote.nationalMonth ?: "", prefs.greetingDutch)
                        if (prefs.greetingDutch) "landelijk gemiddelde ($month)" else "national average ($month)"
                    } else {
                        listOfNotNull(
                            listOf(quote.nearestName, quote.nearestPlace).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() },
                            "%.1f km".format(java.util.Locale.ROOT, quote.nearestKm),
                            if (prefs.greetingDutch) "mediaan van ${quote.stations} stations" else "median of ${quote.stations} stations",
                        ).joinToString("\u00A0· ")
                    }
                }
                if (!isDestroyed && page == Page.OBD) render()
            }
        }, "fuel-price").start()
    }

    private fun renderTrips() {
        pageHeader()
        val trips = com.daand.drivemusic.obd.TripHistory.load(this)
        if (trips.isEmpty()) {
            text("Your drives appear here once the OBD adapter is connected: distance, fuel, costs and a driving score.")
            if (!prefs.obdEnabled) {
                warnCard("Trips need the adapter: the car reports distance and fuel, and the app can't guess them.",
                    action = "Set up the adapter") { go(Page.OBD) }
            } else if (prefs.obdDevice.isBlank()) {
                warnCard("No adapter chosen yet, so no trips are being recorded.",
                    action = "Choose the adapter") { go(Page.OBD) }
            }
            return
        }
        val price = prefs.fuelPriceForCosts   // fetched for your area while it's fresh, else what you typed
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis
        val monthStart = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0) }.timeInMillis
        fun line(label: String, since: Long) {
            val t = com.daand.drivemusic.obd.TripHistory.totals(trips, since)
            if (t.trips == 0) return
            // Two deliberate lines: a narrow card can't fit five values, and wrapped at the separators
            // the break lands on a "·", which then dangles at the start of the next line.
            val distance = listOfNotNull(
                "%.0f km".format(java.util.Locale.ROOT, t.km),
                t.per100?.let { "%.1f L/100".format(java.util.Locale.ROOT, it) },
            ).joinToString(" · ")
            // Each drive is priced with the price from its own day, so the totals don't move when you
            // update the fuel price. Drives from before this was kept fall back to today's price.
            val cost = trips.filter { it.at >= since }.sumOf { it.cost(price) }
            val fuel = listOfNotNull(
                if (t.litres > 0) "%.1f L".format(java.util.Locale.ROOT, t.litres) else null,
                if (price > 0 && cost > 0) "€%.2f".format(java.util.Locale.ROOT, cost) else null,
                if (t.trips == 1) "1 trip" else "${t.trips} trips",
            ).joinToString(" · ")
            statRow(label, if (fuel.isEmpty()) distance else "$distance\n$fuel")
        }
        header("Totals")
        line("This week", weekStart)
        line("This month", monthStart)
        line("All time", 0)
        if (price <= 0) text("Set the fuel price under Car data (OBD) → Your car to see costs.")
        header("Recent drives")
        val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
        for (t in trips.sortedBy { it.at }.takeLast(30).reversed()) {  // newest first
            statRow(fmt.format(java.util.Date(t.at)), com.daand.drivemusic.obd.TripNotice.text(t, price, prefs.greetingDutch, multiline = true) +
                if (t.codes.isNotEmpty()) "\n⚠ ${t.codes.joinToString()}" else "")
        }
        button("Clear trip history", danger = true) {
            AlertDialog.Builder(this).setTitle("Clear all trips?").setMessage("Distance, fuel and costs for every drive - this can't be undone.")
                .setPositiveButton("Clear") { _, _ -> com.daand.drivemusic.obd.TripHistory.clear(this); render() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** A label over a value, in a card - for the trips page. */
    /**
     * The live report as cards - one per group (Engine, Air & fuel, ...) with the label left and the value
     * right-aligned, instead of a wall of "Label: value" text. Lines that aren't "Label: value" stay text.
     */
    private fun liveReport(report: String) {
        var card: LinearLayout? = null
        for (raw in report.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) { card = null; continue }
            val indented = line.startsWith(" ")
            val colon = line.indexOf(": ")
            if (!indented) {
                card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Style.rounded(Style.SURFACE, dp(Style.R_CARD).toFloat())
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    addView(TextView(this@MainActivity).apply {
                        text = line.trim(); textSize = 13f; setTextColor(Style.TEXT2); setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 0, 0, dp(4))
                    })
                }
                root.addView(card)
                continue
            }
            val c = card ?: run { text(line.trim()); null } ?: continue
            if (colon < 0) { c.addView(TextView(this).apply { text = line.trim(); textSize = 14f; setTextColor(Style.TEXT) }); continue }
            val label = line.substring(0, colon).trim(); val value = line.substring(colon + 2).trim()
            if (value.length > 22) {
                // A long value (a fault code's meaning): label on top, value under it, not squeezed right.
                c.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Style.TEXT); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, 0) })
                c.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(Style.TEXT2); setPadding(0, dp(2), 0, dp(4)) })
                continue
            }
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 15f; setTextColor(Style.TEXT2)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = value; textSize = 15f; setTextColor(Style.TEXT)
                    setTypeface(typeface, Typeface.BOLD); gravity = android.view.Gravity.END
                    fontFeatureSettings = "tnum"
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    private fun statRow(label: String, value: String) = addRow(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(TextView(this@MainActivity).apply { text = label; textSize = 13f; setTextColor(Style.TEXT2) })
        addView(TextView(this@MainActivity).apply { text = value; textSize = 16f; setTextColor(Style.TEXT); setPadding(0, dp(2), 0, 0) })
    })

    private fun askText(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial); this.hint = hint; setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPronunciation() = askText(
        "How should the name sound?",
        "e.g. Mister Dik-hof (leave empty to use the name)",
        prefs.namePronunciation
    ) {
        prefs.namePronunciation = it
        render()
        playSample(null, automatic = prefs.voiceName.isBlank())
    }

    /** The real greeting (your style, voice, weather) - not counted as "greeted" for the next drive. */
    /** The optional premium butler voice (Microsoft Azure, free tier). */
    private fun premiumVoiceSection(dutch: Boolean) {
        header("Premium voice (free)")
        switch("Posh premium voice (Microsoft Azure)", prefs.cloudVoiceOn) {
            prefs.cloudVoiceOn = it
            DriveLog.log("Setting changed: premium voice = ${if (it) "on" else "off"}")
            render()
        }
        if (!prefs.cloudVoiceOn) {
            // The privacy point comes first: long texts fold to their first sentence.
            text("A much more natural voice from Microsoft - the spoken text (incl. your name and the weather) goes to " +
                "Microsoft. Free: Microsoft's free allowance is 500,000 characters a month; a greeting is ~300. Needs a " +
                "free Azure account and internet; without signal the phone voice speaks.")
            return
        }
        val key = prefs.azureKey
        stepperLike("Azure key: " + (if (key.isBlank()) "not set" else "••••" + key.takeLast(4)), if (key.isBlank()) "Paste" else "Change") {
            askText("Azure Speech key", "Key 1 from your Speech resource", "") { input ->
                val k = input.filter { it.isLetterOrDigit() }
                if (k.length < 20) { toast("That doesn't look like a key"); return@askText }
                prefs.azureKey = k
                DriveLog.log("Setting changed: premium voice key set")   // never the key itself
                render()
            }
        }
        stepperLike("Region: ${prefs.azureRegion}", "Change") {
            askText("Azure region", "As shown on your Speech resource, e.g. westeurope", prefs.azureRegion) { input ->
                val r = CloudVoice.region(input)
                if (r == null) toast("Type the region, e.g. westeurope") else { prefs.azureRegion = r; render() }
            }
        }
        stepperLike("Voice: " + (if (dutch) "Maarten (Dutch)" else CloudVoice.BRITISH_VOICES[CloudVoice.voiceFor(prefs, false)]), if (dutch) "" else "Choose") {
            if (dutch) return@stepperLike
            val ids = CloudVoice.BRITISH_VOICES.keys.toList()
            AlertDialog.Builder(this).setTitle("British voice")
                .setItems(ids.map { CloudVoice.BRITISH_VOICES[it] }.toTypedArray()) { _, i ->
                    prefs.azureVoice = ids[i]; render()
                    Greeter(this).speak(sampleButlerLine(), false) {}
                }.show()
        }
        button("Hear the premium voice") {
            toast("Speaking... turn up the media volume")
            Greeter(this).speak(if (dutch) "Goedemiddag, meneer. Alles staat voor u klaar." else sampleButlerLine(), dutch) {}
        }
        val used = CloudVoice.usedThisMonth(prefs)
        text("Free allowance this month: %,d of %,d characters used (about %d greetings left). At the limit the phone voice takes over until next month - it never costs anything."
            .format(java.util.Locale.ROOT, used, CloudVoice.MONTHLY_CAP, (CloudVoice.MONTHLY_CAP - used) / 400))
        if (key.isBlank()) text("Set up (5 minutes, free):\n" +
            "1. portal.azure.com → Create a resource → Speech (Azure AI services).\n" +
            "2. Pricing tier: Free F0 - the free tier can't be charged. Region: West Europe.\n" +
            "3. Open it → Keys and Endpoint → copy Key 1 → Paste above.")
    }

    /** The launch animation, drawn by the app (see LaunchIntroView), over the home screen; tap skips. */
    private fun playLaunchIntro() {
        val content = window.decorView as android.view.ViewGroup
        val intro = LaunchIntroView(this).apply { setBackgroundColor(Style.BG); isClickable = true }
        content.addView(intro, android.view.ViewGroup.LayoutParams(-1, -1))
        fun out() = Motion.play(320, update = { f -> intro.alpha = 1 - f; intro.scaleX = 1 + 0.15f * f; intro.scaleY = intro.scaleX },
            end = { content.removeView(intro) })
        var gone = false
        intro.setOnClickListener { if (!gone) { gone = true; out() } }
        intro.play { handler.postDelayed({ if (!gone) { gone = true; out() } }, 250) }
    }

    private fun sampleButlerLine() = "Good afternoon, sir. The car is warm, the music is ready, and the weather is behaving itself. Shall we?"

    private fun previewVoice() {
        if (Voice.speaking) { Voice.stop("you pressed stop"); refreshIfOn(Page.VOICE); return }
        stopVoiceSample()
        toast("Speaking... turn up the media volume")
        WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false) {
            refreshIfOn(Page.VOICE)
        }
    }

    /** Re-draw one page if that's the one on screen (used by callbacks that arrive later). */
    private fun refreshIfOn(which: Page) {
        if (!isDestroyed && page == which) render()
    }

    private fun pickEngine(engines: List<android.speech.tts.TextToSpeech.EngineInfo>) {
        val labels = listOf("Automatic (Google if installed)") + engines.map { it.label }
        AlertDialog.Builder(this)
            .setTitle("Voice engine")
            .setItems(labels.toTypedArray()) { _, i ->
                prefs.ttsEngine = if (i == 0) "" else engines[i - 1].name
                prefs.voiceName = ""  // voices belong to an engine
                DriveLog.log("Setting changed: voice engine = ${prefs.ttsEngine.ifBlank { "automatic" }}")
                releaseVoiceEngine()  // reload the voice list from the new engine
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askName() = askText("How should the voice call you?", "e.g. Mr. Smith", prefs.greetingName) {
        prefs.greetingName = it
        render()
    }

    private fun askBirthday() = askText("Your birthday (MM-DD)", "e.g. 04-27 - leave empty for none", prefs.birthday) { input ->
        val v = input.trim()
        if (v.isEmpty() || Regex("""^\d{2}-\d{2}$""").matches(v)) {
            prefs.birthday = v
            if (v.isNotEmpty()) toast("A birthday greeting will play on $v")
            render()
        } else toast("Please use MM-DD, e.g. 04-27")
    }

    private fun askCity() = askText("Weather for which city?", "e.g. Utrecht", prefs.weatherPlace) { city ->
        if (city.isBlank()) return@askText
        Thread {
            val place = try { Weather.findPlace(city, prefs.greetingDutch) } catch (e: Exception) { null }
            runOnUiThread {
                if (place == null) toast("Couldn't find '$city'") else {
                    saveWeatherLocation(place.lat, place.lon, place.name)
                    toast("Weather location: ${place.name}")
                }
            }
        }.start()
    }

    private fun saveWeatherLocation(lat: Double, lon: Double, name: String) {
        prefs.weatherLat = Weather.round(lat)
        prefs.weatherLon = Weather.round(lon)
        prefs.weatherPlace = name
        DriveLog.log("Setting changed: weather location = ${name.ifBlank { "current location" }}")
        render()
    }

    private fun useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(android.location.LocationManager::class.java)
        val provider = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER))
            android.location.LocationManager.FUSED_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
        toast("Finding your location...")
        val onLocation = { loc: android.location.Location? ->
            val l = loc ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
            if (l == null) toast("Couldn't get your location - try 'Type a city'") else Thread {
                val name = try {
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(this).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.locality ?: ""
                } catch (e: Exception) { "" }
                runOnUiThread { saveWeatherLocation(l.latitude, l.longitude, name); toast("Weather location saved") }
            }.start()
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { onLocation(it) }
            else onLocation(null)  // Android 10: last known location
        } catch (e: Exception) {
            toast("Location unavailable: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) useMyLocation()
            else toast("No problem - use 'Type a city' instead")
        } else if (requestCode == REQ_BT_OBD) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (page == Page.OBD) chooseObdDevice() }
            else toast("Needed to talk to the OBD adapter")
            render()
        } else if (requestCode == REQ_PARKING) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) enableParking()
            else toast("Location wasn't allowed - parking spot can't be saved")
        } else render()
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdate(silent: Boolean) {
        updateBusy = true
        if (!silent) { updateMessage = "Checking..."; render() }
        Thread {
            val (release, msg) = try {
                val r = Updater.checkForUpdate(this)
                r to (if (r == null) "You have the latest version ✅" else "Version ${r.versionName} is available")
            } catch (e: Throwable) {
                // Throwable: an Error here would leave updateBusy set and the button saying "Checking..."
                // for the rest of the session.
                null to "Couldn't check for updates (${e.message ?: "no internet?"})"
            }
            runOnUiThread {
                updateBusy = false
                availableUpdate = release
                updateMessage = if (!silent || release != null) msg else ""
                if (!isFinishing && !isDestroyed) render()
            }
        }.start()
    }

    private fun installUpdate(r: Updater.Release) {
        if (!Updater.canInstall(this)) {
            toast("Allow 'Install unknown apps' for Drive Music, then tap Install again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateBusy = true
        updateMessage = "Downloading version ${r.versionName}..."
        render()
        DriveLog.log("UPDATE: downloading version ${r.versionName}")
        Thread {
            try {
                var lastShown = -10
                val apk = Updater.downloadAndVerify(this, r) { pct ->
                    if (pct >= lastShown + 10) {
                        lastShown = pct
                        runOnUiThread { updateMessage = "Downloading version ${r.versionName}... $pct%"; render() }
                    }
                }
                DriveLog.log("UPDATE: download verified (checksum + signature OK)")
                Updater.install(this, apk)
                runOnUiThread {
                    updateBusy = false; updateMessage = "Verified ✅ - confirm the update on the next screen"
                    if (!isFinishing && !isDestroyed) render()
                }
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error while unpacking a big file would leave updateBusy
                // set, and the Updates page would say "Downloading..." until the app was restarted.
                DriveLog.log("UPDATE: failed - ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    updateBusy = false; updateMessage = "❌ ${e.message ?: "something went wrong"}"
                    if (!isFinishing && !isDestroyed) render()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- helpers

    private fun pickTheme() {
        val ids = Style.THEMES.keys.toList()
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setItems(Style.THEMES.values.toTypedArray()) { _, i ->
                prefs.theme = ids[i]
                Style.apply(prefs.theme)
                recreate()  // redraw everything, including the status bar colours
            }
            .show()
    }

    private fun pickMusicApp() {
        val candidates = MusicApps.youtubeMusicCandidates(this)
        val others = MusicApps.launchableApps(this).filter { a -> candidates.none { it.pkg == a.pkg } }
        val all = candidates + others
        val labels = all.map { (if (it in candidates) "★ " else "") + "${it.label}\n${it.pkg}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which app plays your music?")
            .setItems(labels) { _, i ->
                prefs.musicPackage = all[i].pkg
                DriveLog.log("Setting changed: music app = ${all[i].pkg}")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasNotificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openAppSettings(pkg: String) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(s: String) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(6))
        addView(TextView(this@MainActivity).apply {
            text = s; textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
        })
        addView(View(this@MainActivity).apply {
            background = Style.rounded(Style.PRIMARY, dp(2).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply { topMargin = dp(6) }
        })
    })

    /** A section heading; the rows after it go into a new card. */
    private fun header(s: String) {
        group = null
        root.addView(TextView(this).apply {
            text = s.uppercase(); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Style.TEXT2); letterSpacing = 0.08f
            setPadding(dp(4), dp(20), 0, dp(6))
        })
    }

    /** The section card rows are going into: a new one starts after a heading or any other view. */
    private var group: LinearLayout? = null

    private fun openGroup(): LinearLayout? = group?.takeIf { root.childCount > 0 && root.getChildAt(root.childCount - 1) === it }

    /** Adds a row to the current section card (rows inside one card, thin lines between them). */
    private fun addRow(v: View) {
        val g = openGroup() ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.rounded(Style.SURFACE, dp(Style.R_CARD + 2).toFloat()); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            root.addView(this); group = this
        }
        if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(Style.BG) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(14) })
        g.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** A view that stands on its own (a warning, a big button): closes the current card. */
    private fun addCard(v: View) { group = null; root.addView(v) }


    /**
     * Explanation text. Inside a card it becomes the card's footnote; long ones fold to their first
     * sentence + "More ›" (tap to read it all, tap again to fold).
     */
    private fun text(s: String) {
        val inCard = openGroup() != null
        // The Help page is there to be read: nothing folds there.
        val short = if (page == Page.HELP) null else TextFold.short(s)
        fun shown(folded: Boolean): CharSequence {
            if (short == null) return s
            val tail = if (folded) "  More ›" else "  Less ‹"
            return android.text.SpannableStringBuilder(if (folded) short else s).append(tail).apply {
                setSpan(android.text.style.ForegroundColorSpan(Style.TEXT), length - tail.length, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), length - tail.length, length, 0)
            }
        }
        val tv = TextView(this).apply {
            text = shown(true); setTextColor(Style.TEXT2); setLineSpacing(dp(3).toFloat(), 1f)
            textSize = if (inCard) 13f else 14f
            if (inCard) setPadding(dp(14), dp(10), dp(14), dp(12)) else setPadding(dp(4), dp(6), dp(4), dp(8))
            if (short != null) {
                var folded = true
                minHeight = dp(44)
                setOnClickListener { folded = !folded; text = shown(folded) }
            }
        }
        if (inCard) addRow(tv) else root.addView(tv)
    }


    /**
     * The page's main action (accent / danger) is a big button of its own; any other button is a
     * "label ›" row in the current card.
     */
    private fun button(label: String, accent: Boolean = false, danger: Boolean = false, enabled: Boolean = true,
                       onClick: () -> Unit) {
        if (!accent && !danger) {
            addRow(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                background = Style.button(Color.TRANSPARENT, 0f)
                setPadding(dp(16), dp(12), dp(14), dp(12)); minimumHeight = dp(52)
                isEnabled = enabled; if (!enabled) alpha = 0.55f
                isClickable = true; contentDescription = label
                setOnClickListener { if (enabled) onClick() }
                Feel.press(this, 0.98f)
                addView(TextView(this@MainActivity).apply { text = label; textSize = 15f; setTextColor(Style.TEXT) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply { text = "›"; textSize = 22f; setTextColor(Style.TEXT2) })
            })
            return
        }
        addCard(Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f
            isEnabled = enabled; if (!enabled) alpha = 0.55f
            setTextColor(Color.WHITE)
            background = Style.button(if (danger) Style.DANGER else Style.PRIMARY, dp(Style.R_CARD).toFloat())
            stateListAnimator = null
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { onClick() }
            Feel.press(this, 0.97f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })
    }


    private fun row(vararg views: View) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { addView(it) }
    })

    /** A value you nudge up/down: card with the name on the left and compact round − value + on the right. */
    private fun stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
        fun round(sign: String, desc: String, act: () -> Unit) = TextView(this).apply {
            text = sign; textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(Style.TEXT)
            background = Style.button(Style.SURFACE2, dp(22).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isClickable = true; contentDescription = "$desc $label"
            setOnClickListener { act(); render() }
            Feel.press(this, 0.9f)
        }
        addRow(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(16), dp(8), dp(10), dp(8))
            minimumHeight = dp(60)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8); layoutParams = it
            }
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 15f; setTextColor(Style.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(round("−", "Less", minus))
            addView(TextView(this@MainActivity).apply {
                text = value; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
                gravity = android.view.Gravity.CENTER; maxLines = 1; fontFeatureSettings = Style.TABULAR
                minWidth = dp(64); setPadding(dp(6), 0, dp(6), 0)
            })
            addView(round("+", "More", plus))
        })
    }

    private fun check(label: String, help: String, ok: Boolean, action: String, onClick: () -> Unit) {
        addRow(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            background = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
            (LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)).also {
                it.topMargin = dp(8); layoutParams = it
            }
            addView(TextView(this@MainActivity).apply {
                text = if (ok) "✓" else "!"
                setTypeface(typeface, Typeface.BOLD); textSize = 16f; gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                background = Style.rounded(if (ok) Style.GREEN else Style.AMBER, dp(13).toFloat())
                val sz = dp(26); layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = help; textSize = 13f; setTextColor(Style.TEXT2); setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(0, dp(2), 0, 0)
                })
                // The fix button sits under the text, so the title gets the full width.
                if (!ok) addView(Button(this@MainActivity).apply {
                    text = action; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
                    background = Style.button(Style.PRIMARY, dp(12).toFloat()); stateListAnimator = null
                    setPadding(dp(18), dp(10), dp(18), dp(10)); minHeight = dp(44)
                    Feel.press(this)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener { onClick() }
                })
            })
        })
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = addRow(Switch(this).apply {
        text = label; textSize = 15f; isChecked = value; setTextColor(Style.TEXT)
        background = null
        setPadding(dp(14), dp(14), dp(14), dp(14))
        thumbTintList = Style.tint(if (value) Style.PRIMARY else Style.TEXT2)
        trackTintList = Style.tint(if (value) Style.primarySoft() else Style.SURFACE2)
        (LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)).also {
            it.topMargin = dp(8); layoutParams = it
        }
        setOnCheckedChangeListener { sw, v ->
            // Keep the colours in step with the new state.
            (sw as Switch).thumbTintList = Style.tint(if (v) Style.PRIMARY else Style.TEXT2)
            sw.trackTintList = Style.tint(if (v) Style.primarySoft() else Style.SURFACE2)
            onChange(v); DriveLog.log("Setting changed: $label = $v")
        }
    })

    companion object {
        private const val ENTER = 1
        private const val BACK = 2
        private const val REQ_LOCATION = 3
        private const val REQ_PARKING = 14
        private const val REQ_BT_OBD = 15
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_INTRO_IMAGE = 12
        private const val REQ_CAR_PHOTO = 13
        /** Utterance id for a voice sample on the Voice page. */
        private const val SAMPLE_ID = "sample"
        /** Key for the "Automatic" row (it has no voice name of its own). */
        private const val AUTO_SAMPLE = "auto"
    }

    private fun openEqualizer() {
        val i = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, 0)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
        }
        if (i.resolveActivity(packageManager) != null) startActivity(i)
        else {
            toast("No system equalizer found - opening sound settings")
            try { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (e: Exception) {}
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
