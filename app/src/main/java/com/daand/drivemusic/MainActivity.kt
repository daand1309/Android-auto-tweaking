package com.daand.drivemusic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
    private var toFix = 0
    private var availableUpdate: Updater.Release? = null
    private var updateBusy = false
    private var updateMessage = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(Style.BG)
            isFillViewport = true
        }
        setContentView(scroll)
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
            root.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(40) + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, DriveMonitorService::class.java))
        }
        render()
        // Quietly look for an update when the app is opened (at most every 6 hours).
        if (prefs.autoUpdateCheck && System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
            checkForUpdate(silent = true)
        }
    }

    // ---------------------------------------------------------------- pages

    private enum class Page(val title: String) {
        HOME("Drive Music"), SETUP("Setup"), MUSIC("Music & driving"), GREETING("Welcome greeting"),
        VOICE("Voice"), DRIVE_MODE("Drive Mode"), SOUND("Sound"), UPDATES("Updates"), HELP("Help & privacy"),
        CAR("Car screen"), BACKUP("Backup & restore")
    }

    private var page = Page.HOME

    private fun go(p: Page) {
        page = p
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun handleBack() {
        when (page) {
            Page.HOME -> finish()
            Page.VOICE -> go(Page.GREETING)
            else -> go(Page.HOME)
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
            SetupItem("Drive Music: battery Unrestricted", "Battery → Unrestricted.",
                pm.isIgnoringBatteryOptimizations(packageName), "Open") { openAppSettings(packageName) },
            SetupItem("${MusicApps.label(this, music)}: battery Unrestricted", "Battery → Unrestricted.",
                pm.isIgnoringBatteryOptimizations(music), "Open") { openAppSettings(music) },
            SetupItem("Android Auto: battery Unrestricted", "Battery → Unrestricted.",
                pm.isIgnoringBatteryOptimizations(DriveMonitorService.ANDROID_AUTO), "Open") { openAppSettings(DriveMonitorService.ANDROID_AUTO) },
        )
        MusicApps.findMicroG(this)?.let { mg ->
            items += SetupItem("MicroG: battery Unrestricted", "Keeps patched YouTube Music signed in.",
                pm.isIgnoringBatteryOptimizations(mg), "Open") { openAppSettings(mg) }
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
        val y = scroll.scrollY  // keep scroll position when re-drawing after a button tap
        root.removeAllViews()
        toFix = 0
        when (page) {
            Page.HOME -> renderHome()
            Page.SETUP -> renderSetup()
            Page.MUSIC -> renderMusic()
            Page.GREETING -> renderGreeting()
            Page.VOICE -> renderVoice()
            Page.DRIVE_MODE -> renderDriveMode()
            Page.SOUND -> renderSound()
            Page.UPDATES -> renderUpdates()
            Page.HELP -> renderHelp()
            Page.CAR -> renderCar()
            Page.BACKUP -> renderBackup()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun renderHome() {
        title("Drive Music")
        val running = DriveMonitorService.instance != null
        text((if (running) "● Running" else "● Not running") + "   ·   ${prefs.lastCarState}")

        button("🚗  Drive Mode", accent = true) { startActivity(Intent(this, DriveModeActivity::class.java)) }
        row(
            quickBtn("▶  Start music") {
                val svc = DriveMonitorService.instance
                if (svc == null) toast("Finish setup first") else { svc.startMusic("manual test button", requireCar = false); toast("Starting...") }
            },
            quickBtn("🔊  Greeting") {
                toast("Speaking...")
                WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false)
            },
        )

        availableUpdate?.let { upd -> navRow("⬇  Update ${upd.versionName} available", "Tap to install", highlight = true) { go(Page.UPDATES) } }
        val missing = setupItems().count { !it.ok && !it.label.contains("optional") }
        if (missing > 0) navRow("⚠  Setup: $missing step(s) left", "Needed for the app to work properly", warn = true) { go(Page.SETUP) }

        header("Settings")
        navRow("Music & driving", "Auto-start, auto-resume, volume") { go(Page.MUSIC) }
        navRow("Welcome greeting", if (prefs.greetingEnabled) "On · ${prefs.greetingName.ifBlank { "no name set" }}" else "Off") { go(Page.GREETING) }
        navRow("Drive Mode", if (prefs.autoOpenDriveMode) "Opens when the car connects" else "Big buttons for your phone holder") { go(Page.DRIVE_MODE) }
        navRow("Sound", "Equalizer and sound settings") { go(Page.SOUND) }
        navRow("Car screen", "Show Drive Music in Android Auto") { go(Page.CAR) }
        if (missing == 0) navRow("Setup", "All done ✓") { go(Page.SETUP) }

        header("More")
        navRow("Updates", "Version ${packageManager.getPackageInfo(packageName, 0).versionName}") { go(Page.UPDATES) }
        navRow("Logs", "What happened on your drives") { startActivity(Intent(this, LogActivity::class.java)) }
        navRow("Backup & restore", "Save your settings to a file") { go(Page.BACKUP) }
        navRow("Help & privacy", "Car screen setup, tips, what data is used") { go(Page.HELP) }
        if (prefs.lastDrive.isNotEmpty()) text("Last drive: ${prefs.lastDrive}")
    }

    private fun renderSetup() {
        pageHeader()
        setupItems().forEach { check(it.label, it.help, it.ok, it.action, it.onClick) }
        if (!hasNotificationAccess()) {
            text("Switch greyed out? App info → ⋮ → Allow restricted settings.")
            button("App info") { openAppSettings(packageName) }
        }
    }

    private fun renderMusic() {
        pageHeader()
        val music = prefs.musicPackage
        val installed = MusicApps.isInstalled(packageManager, music)
        stepperLike((if (installed) "" else "❌ ") + "Music app: ${MusicApps.label(this, music)}", "Change") { pickMusicApp() }
        switch("Start music when the car connects", prefs.autoStart) { prefs.autoStart = it }
        stepper("Wait after connecting", "${prefs.startDelaySec}s",
            { prefs.startDelaySec = (prefs.startDelaySec - 1).coerceAtLeast(0) },
            { prefs.startDelaySec = (prefs.startDelaySec + 1).coerceAtMost(30) })
        switch("Auto-resume after a pause", prefs.autoResume) { prefs.autoResume = it; render() }
        if (prefs.autoResume) {
            stepper("Resume after", "${prefs.resumeDelaySec}s",
                { prefs.resumeDelaySec = (prefs.resumeDelaySec - 2).coerceAtLeast(2) },
                { prefs.resumeDelaySec = (prefs.resumeDelaySec + 2).coerceAtMost(60) })
            text("Never during calls, the Assistant, or other apps.")
        }
        switch("Pause music when leaving the car", prefs.pauseOnDisconnect) { prefs.pauseOnDisconnect = it }
        switch("Set media volume when connecting", prefs.setVolumeOnConnect) { prefs.setVolumeOnConnect = it; render() }
        if (prefs.setVolumeOnConnect) {
            stepper("Volume", "${prefs.connectVolumePct}%",
                { prefs.connectVolumePct = (prefs.connectVolumePct - 10).coerceAtLeast(10) },
                { prefs.connectVolumePct = (prefs.connectVolumePct + 10).coerceAtMost(100) })
        }
    }

    private fun renderGreeting() {
        pageHeader()
        switch("Welcome me when the car connects", prefs.greetingEnabled) { prefs.greetingEnabled = it; render() }
        if (!prefs.greetingEnabled) return
        button("🔊  Test greeting", accent = true) {
            toast("Speaking...")
            WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false)
        }
        stepperLike("Name: ${prefs.greetingName.ifBlank { "not set" }}", "Change") { askName() }
        stepperLike("Birthday: ${prefs.birthday.ifBlank { "none" }}", "Set") { askBirthday() }
        switch("Speak Dutch", prefs.greetingDutch) { prefs.greetingDutch = it }
        switch("Say which song plays first", prefs.announceSong) { prefs.announceSong = it }
        switch("Weather", prefs.weatherEnabled) { prefs.weatherEnabled = it; render() }
        if (prefs.weatherEnabled) {
            switch("Road conditions", prefs.roadConditionsEnabled) { prefs.roadConditionsEnabled = it }
            val place = when {
                prefs.weatherPlace.isNotBlank() -> prefs.weatherPlace
                !prefs.weatherLat.isNaN() -> "your location"
                else -> "not set"
            }
            stepperLike("Location: $place", "Change") {
                AlertDialog.Builder(this).setTitle("Weather location")
                    .setItems(arrayOf("📍  Use my location", "🔎  Type a city")) { _, i -> if (i == 0) useMyLocation() else askCity() }
                    .show()
            }
        }
        navRow("Voice", prefs.voiceName.ifBlank { "Automatic" }) { go(Page.VOICE) }
    }

    private fun renderVoice() {
        pageHeader(back = Page.GREETING)
        button("▶  Preview voice", accent = true) { previewVoice() }
        stepperLike("Voice: ${prefs.voiceName.ifBlank { "automatic" }}", "Choose") { pickVoice() }
        stepperLike("Say my name as: ${prefs.namePronunciation.ifBlank { "as written" }}", "Change") { askPronunciation() }
        stepper("Speed", "%.2fx".format(prefs.speechRate),
            { prefs.speechRate = (prefs.speechRate - 0.05f).coerceAtLeast(0.6f) },
            { prefs.speechRate = (prefs.speechRate + 0.05f).coerceAtMost(1.4f) })
        stepper("Pitch", "%.2f".format(prefs.speechPitch),
            { prefs.speechPitch = (prefs.speechPitch - 0.05f).coerceAtLeast(0.6f) },
            { prefs.speechPitch = (prefs.speechPitch + 0.05f).coerceAtMost(1.4f) })
        if (!MusicApps.isInstalled(packageManager, VoicePicker.GOOGLE_TTS)) {
            button("Get a better voice (Google)") {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${VoicePicker.GOOGLE_TTS}"))) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${VoicePicker.GOOGLE_TTS}"))) }
            }
        }
        button("Download more voices") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { toast("Settings → General management → Text-to-speech") }
        }
    }

    private fun renderDriveMode() {
        pageHeader()
        button("🚗  Open Drive Mode", accent = true) { startActivity(Intent(this, DriveModeActivity::class.java)) }
        switch("Open automatically when the car connects", prefs.autoOpenDriveMode) { prefs.autoOpenDriveMode = it; render() }
        if (prefs.autoOpenDriveMode) {
            text("Pops up when the phone is locked; otherwise shows a tap banner.")
            if (Build.VERSION.SDK_INT >= 34) button("Allow full-screen pop-up") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { openAppSettings(packageName) }
            }
        }
        switch("Intro animation", prefs.showSplash) { prefs.showSplash = it; render() }
        if (prefs.showSplash) {
            val hasImage = IntroImage.exists(this)
            stepperLike("Intro image: ${if (hasImage) "your picture" else "none (shows text)"}", if (hasImage) "Change" else "Choose") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, REQ_INTRO_IMAGE)
            }
            if (hasImage) button("Remove intro image") { IntroImage.clear(this); render() }
            else stepperLike("Intro text: ${prefs.carName.ifBlank { "none" }}", "Change") {
                askText("Text shown in the intro", "e.g. ASX", prefs.carName) { prefs.carName = it.take(12); render() }
            }
            button("▶  Preview intro") { startActivity(Intent(this, DriveModeActivity::class.java)) }
        }
    }

    private fun renderSound() {
        pageHeader()
        button("🎚  Open equalizer", accent = true) { openEqualizer() }
        button("Phone sound settings") {
            try { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (e: Exception) { toast("Open Settings → Sounds") }
        }
        text("Music too quiet? Music & driving → Set media volume when connecting.")
        text("Quiet calls? Turn on VoLTE (Settings → Connections → Mobile networks) and raise the volume during the call.")
    }

    private fun renderUpdates() {
        pageHeader()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        text("Installed: $version" +
            (if (updateMessage.isNotEmpty()) "\n$updateMessage" else "") +
            (if (prefs.updateStatus.isNotEmpty() && updateMessage.isEmpty()) "\nLast update: ${prefs.updateStatus}" else ""))
        val upd = availableUpdate
        if (upd != null) {
            button(if (updateBusy) "Downloading..." else "⬇  Install ${upd.versionName}", accent = true) { if (!updateBusy) installUpdate(upd) }
            if (upd.notes.isNotBlank()) text(upd.notes.take(400))
        }
        button(if (updateBusy) "Working..." else "Check for updates") { if (!updateBusy) checkForUpdate(silent = false) }
        switch("Check automatically (daily)", prefs.autoUpdateCheck) { prefs.autoUpdateCheck = it }
    }

    private fun renderHelp() {
        pageHeader()
        navRow("Car screen setup", "Show Drive Music in Android Auto") { go(Page.CAR) }
        header("If music pauses")
        text("• Samsung: put YouTube Music, Android Auto, MicroG and Drive Music in 'Never sleeping apps'.\n" +
            "• YouTube Music: turn on Smart downloads; turn off 'Remind me to take a break'.\n" +
            "• Logs show GOOGLE ASSISTANT before pauses? Lower 'Hey Google' sensitivity.\n" +
            "• Wired Android Auto dropping? Try a short, good USB cable.")
        header("Privacy")
        text("• Internet only for update checks (GitHub) and weather (Open-Meteo, location rounded to ~10 km).\n" +
            "• Notifications are never read. Logs stay on the phone unless you share them.\n" +
            "• No overlay, accessibility, contacts, SMS or storage access.")
        button("Open music app") {
            packageManager.getLaunchIntentForPackage(prefs.musicPackage)?.let { startActivity(it) } ?: toast("Music app not installed")
        }
    }

    private fun renderCar() {
        pageHeader()
        text("Android Auto hides apps that aren't from the Play Store until you allow them once:")
        button("Open Android Auto settings", accent = true) { openAndroidAutoSettings() }
        text("1. Scroll down to Version (Versie) and tap it about 10 times → OK.\n" +
            "2. Tap ⋮ (top right) → Developer settings (Ontwikkelaarsinstellingen).\n" +
            "3. Tick Unknown sources (Onbekende bronnen).\n" +
            "4. Back in Android Auto settings → Customise launcher (Launcher aanpassen) → tick Drive Music.\n" +
            "5. Unplug and reconnect the car.")
        text("Drive Music then appears in the car's app grid with media controls and the Welcome button.")
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
        text("Keeps your name, voice, location and other choices if you reinstall or change phones.")
        button("Save settings to a file", accent = true) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "drive-music-settings.json")
            }, REQ_EXPORT)
        }
        button("Restore settings from a file") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }
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
                REQ_INTRO_IMAGE -> {
                    IntroImage.save(this, uri)
                    toast("Intro image set")
                    render()
                }
                REQ_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val n = prefs.importJson(json)
                    toast("Restored $n settings")
                    DriveLog.log("Settings restored ($n)")
                    render()
                }
            }
        } catch (e: Exception) {
            toast("Couldn't ${when (requestCode) { REQ_EXPORT -> "save"; REQ_INTRO_IMAGE -> "use that image"; else -> "restore" }}: ${e.message}")
        }
    }

    /** Title row for a sub-page with a back arrow. */
    private fun pageHeader(back: Page = Page.HOME) = root.addView(TextView(this).apply {
        text = "‹  ${page.title}"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Style.TEXT)
        setPadding(dp(4), dp(4), 0, dp(14))
        setOnClickListener { go(back) }
    })

    /** Menu row: title + short subtitle, opens something when tapped. */
    private fun navRow(title: String, subtitle: String, highlight: Boolean = false, warn: Boolean = false, onClick: () -> Unit) =
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Style.button(
                when { highlight -> 0xFF1D3A6B.toInt(); warn -> 0xFF4A3510.toInt(); else -> Style.SURFACE }, dp(14).toFloat()
            )
            setPadding(dp(16), dp(14), dp(14), dp(14))
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8); layoutParams = it
            }
            isClickable = true
            setOnClickListener { onClick() }
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
        background = Style.button(Style.SURFACE2, dp(14).toFloat()); stateListAnimator = null
        setPadding(dp(12), dp(14), dp(12), dp(14))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, dp(6), dp(6), 0)
        }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- greeting settings

    private fun stepperLike(label: String, action: String, onClick: () -> Unit) = row(
        TextView(this).apply {
            text = label; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        Button(this).apply { text = action; isAllCaps = false; setOnClickListener { onClick() } },
    )

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
        previewVoice()
    }

    private fun sampleText(): String {
        val dutch = prefs.greetingDutch
        val name = SpeechText.spokenName(prefs.namePronunciation.ifBlank { prefs.greetingName }, dutch)
        val hello = GreetingText.timeGreeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), dutch)
        return if (dutch) "$hello${if (name.isNotBlank()) ", $name" else ""}. Zo klink ik. Het is nu min 2 graden en de zon gaat om 17.05 uur onder."
        else "$hello${if (name.isNotBlank()) ", $name" else ""}. This is how I sound. It's currently minus 2 degrees and the sun sets at 5:05 PM."
    }

    private fun previewVoice() {
        toast("Speaking... turn up the media volume")
        Greeter(this).speak(sampleText(), prefs.greetingDutch) {}
    }

    /** Lists installed voices for the greeting language (best first) and previews the one you pick. */
    private fun pickVoice() {
        val dutch = prefs.greetingDutch
        var tts: android.speech.tts.TextToSpeech? = null
        val onInit = android.speech.tts.TextToSpeech.OnInitListener { status ->
            runOnUiThread {
                val engine = tts ?: return@runOnUiThread
                if (status != android.speech.tts.TextToSpeech.SUCCESS) { toast("No text-to-speech engine found"); engine.shutdown(); return@runOnUiThread }
                val voices = VoicePicker.usable(engine.voices.orEmpty(), dutch)
                val best = VoicePicker.best(voices, dutch)
                val sorted = voices.sortedWith(compareByDescending<android.speech.tts.Voice> { it == best }
                    .thenBy { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
                val engines = engine.engines
                engine.shutdown()
                if (sorted.isEmpty()) {
                    toast("No ${if (dutch) "Dutch" else "English"} voices installed - tap 'Download better voices'")
                    return@runOnUiThread
                }
                val labels = listOf("Automatic (recommended)") + sorted.map { VoicePicker.label(it) } +
                    (if (engines.size > 1) listOf("Change voice engine…") else emptyList())
                AlertDialog.Builder(this)
                    .setTitle("Choose a voice")
                    .setItems(labels.toTypedArray()) { _, i ->
                        when {
                            i == 0 -> prefs.voiceName = ""
                            i <= sorted.size -> prefs.voiceName = sorted[i - 1].name
                            else -> { pickEngine(engines); return@setItems }
                        }
                        DriveLog.log("Setting changed: voice = ${prefs.voiceName.ifBlank { "automatic" }}")
                        render()
                        previewVoice()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val engineName = VoicePicker.preferredEngine(this, prefs.ttsEngine)
        tts = if (engineName != null) android.speech.tts.TextToSpeech(this, onInit, engineName)
        else android.speech.tts.TextToSpeech(this, onInit)
    }

    private fun pickEngine(engines: List<android.speech.tts.TextToSpeech.EngineInfo>) {
        val labels = listOf("Automatic (Google if installed)") + engines.map { it.label }
        AlertDialog.Builder(this)
            .setTitle("Voice engine")
            .setItems(labels.toTypedArray()) { _, i ->
                prefs.ttsEngine = if (i == 0) "" else engines[i - 1].name
                prefs.voiceName = ""  // voices belong to an engine
                DriveLog.log("Setting changed: voice engine = ${prefs.ttsEngine.ifBlank { "automatic" }}")
                render()
                previewVoice()
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
            } catch (e: Exception) {
                null to "Couldn't check for updates (${e.message ?: "no internet?"})"
            }
            runOnUiThread {
                updateBusy = false
                availableUpdate = release
                if (!silent || release != null) updateMessage = msg
                if (!isFinishing) render()
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
                runOnUiThread { updateBusy = false; updateMessage = "Verified ✅ - confirm the update on the next screen"; render() }
            } catch (e: Exception) {
                DriveLog.log("UPDATE: failed - ${e.message}")
                runOnUiThread { updateBusy = false; updateMessage = "❌ ${e.message}"; render() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- helpers

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

    private fun header(s: String) = root.addView(TextView(this).apply {
        text = s.uppercase(); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Style.PRIMARY); letterSpacing = 0.08f
        setPadding(dp(4), dp(22), 0, dp(8))
    })

    private fun text(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 14f; setTextColor(Style.TEXT2); setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(2), dp(4), dp(8))
    })

    private fun button(label: String, accent: Boolean = false, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (accent) Color.WHITE else Style.TEXT)
        background = Style.button(if (accent) Style.PRIMARY else Style.SURFACE2, dp(14).toFloat())
        stateListAnimator = null
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setOnClickListener { onClick() }
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )).also { it.topMargin = dp(6); layoutParams = it }
    })

    private fun row(vararg views: View) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { addView(it) }
    })

    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Style.TEXT)
        background = Style.button(Style.SURFACE2, dp(12).toFloat()); stateListAnimator = null
        minimumWidth = dp(52); setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) = row(
        TextView(this).apply {
            text = "$label: $value"; textSize = 15f; setTextColor(Style.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        smallBtn("−") { minus(); render() },
        smallBtn("+") { plus(); render() },
    )

    private fun check(label: String, help: String, ok: Boolean, action: String, onClick: () -> Unit) {
        if (!ok) toFix++
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Style.rounded(Style.SURFACE, dp(14).toFloat())
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
            })
            if (!ok) addView(Button(this@MainActivity).apply {
                text = action; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
                background = Style.button(Style.PRIMARY, dp(12).toFloat()); stateListAnimator = null
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }
                setOnClickListener { onClick() }
            })
        })
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = root.addView(Switch(this).apply {
        text = label; textSize = 15f; isChecked = value; setTextColor(Style.TEXT)
        background = Style.rounded(Style.SURFACE, dp(14).toFloat())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        thumbTintList = Style.tint(if (value) Style.PRIMARY else Style.TEXT2)
        trackTintList = Style.tint(if (value) 0x66_4F8CFF else Style.SURFACE2)
        (LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)).also {
            it.topMargin = dp(8); layoutParams = it
        }
        setOnCheckedChangeListener { sw, v ->
            // Keep the colours in step with the new state.
            (sw as Switch).thumbTintList = Style.tint(if (v) Style.PRIMARY else Style.TEXT2)
            sw.trackTintList = Style.tint(if (v) 0x66_4F8CFF else Style.SURFACE2)
            onChange(v); DriveLog.log("Setting changed: $label = $v")
        }
    })

    companion object {
        private const val REQ_LOCATION = 3
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_INTRO_IMAGE = 12
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
