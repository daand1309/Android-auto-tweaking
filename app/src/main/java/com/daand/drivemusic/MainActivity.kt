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
        // Screen stays on while the app is open (it sits in a car holder); normal timeout again once you leave.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        handler.removeCallbacks(homeTick)
        handler.postDelayed(homeTick, 5000)
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Test hook: open a page by name ("VOICE", "SETUP", ...). */
    @androidx.annotation.VisibleForTesting
    internal fun openPageForTest(name: String) = go(Page.valueOf(name))

    @androidx.annotation.VisibleForTesting
    internal fun pageNamesForTest(): List<String> = Page.values().map { it.name }

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
            SetupItem("Drive Music: battery Unrestricted",
                "Battery → Unrestricted. Samsung: also check Settings → Battery → Background usage limits - Drive Music must not be under Sleeping apps.",
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
        lastHomeSignature = homeSignature()
        heroCard()

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

        if (!MusicApps.isInstalled(packageManager, prefs.musicPackage)) {
            navRow("⚠  Music app not found", "Tap to pick the app that plays your music", warn = true) { pickMusicApp() }
        }
        availableUpdate?.let { upd -> navRow("⬇  Update ${upd.versionName} available", "Tap to install", highlight = true) { go(Page.UPDATES) } }
        val missing = setupItems().count { !it.ok && !it.label.contains("optional") }
        if (missing > 0) navRow("⚠  Setup: $missing step(s) left", "Needed for the app to work properly", warn = true) { go(Page.SETUP) }

        header("Settings")
        navRow("Music & driving", "Auto-start, auto-resume, volume") { go(Page.MUSIC) }
        navRow("Welcome greeting", if (prefs.greetingEnabled) "On · ${prefs.greetingName.ifBlank { "no name set" }}" else "Off") { go(Page.GREETING) }
        navRow("Drive Mode", if (prefs.autoOpenDriveMode) "Opens when the car connects" else "Big buttons for your phone holder") { go(Page.DRIVE_MODE) }
        navRow("Sound", "Equalizer and sound settings") { go(Page.SOUND) }
        navRow("Theme", Style.THEMES[prefs.theme] ?: "") { pickTheme() }
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
        navRow("Voice & style", (if (prefs.butler) "🎩 Butler" else "😏 Sassy") + " · " +
            (if (prefs.voiceName.isBlank()) "automatic voice" else "your chosen voice") + " · tap to listen") { go(Page.VOICE) }

        header("During the drive")
        switch("Tell me when weather or roads change", prefs.updatesWeather) { prefs.updatesWeather = it }
        switch("Breaking news (only what you need to know)", prefs.updatesNews) { prefs.updatesNews = it }
        switch("Ask first, with 🟢 / 🔴 buttons", prefs.askBeforeUpdates) { prefs.askBeforeUpdates = it }
        text("Checks every 15 minutes and stays quiet unless something matters: rain or snow starting, ice, fog, " +
            "strong gusts - or major news in the Netherlands (alerts, disasters, big outages, closed motorways, " +
            "the government falling). Animals, sport, celebs and other soft news are always filtered out. " +
            "At most 3 news items per drive, never during a call.")
        button("Check for news now") { testDriveUpdate() }
        if (prefs.askBeforeUpdates) button("Try the question (🟢 / 🔴)") { tryUpdateQuestion() }
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
            runOnUiThread {
                newsCheckRunning = false
                if (isDestroyed) return@runOnUiThread
                when {
                    items.isEmpty() && failed > 0 -> toast("Couldn't reach the news - no internet?")
                    story == null -> toast("Checked ${items.size} headlines - nothing urgent enough to interrupt your music. 👍")
                    else -> {
                        val text = DriveUpdates.compose(emptyList(), story, p.greetingDutch, p.butler, p.greetingName)!!
                        DriveLog.log("UPDATE (test): \"$text\"")
                        Greeter(this).speak(text, p.greetingDutch) {}
                    }
                }
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
        button("▶  Hear a full greeting", accent = true) { previewVoice() }

        header("Voice")
        if (!voiceTtsReady) {
            text("Loading voices…")
        } else if (voiceList.isEmpty()) {
            text("No ${if (dutch) "Dutch" else "English"} voices installed on this phone.")
        } else {
            val accents = voiceList.map { VoicePicker.accent(it) }.distinct().sortedBy { if (it == "GB") "" else it }
            if (accents.size > 1) accentChips(listOf("") + accents)
            val names = VoicePicker.friendlyNames(voiceList)
            voiceRow("✨  Automatic", if (prefs.butler && !dutch) "Best British voice for the butler" else "Best voice on this phone",
                prefs.voiceName.isBlank(), onPlay = { playSample(null, automatic = true) }) {
                prefs.voiceName = ""
                DriveLog.log("Setting changed: voice = automatic")
                render(); playSample(null, automatic = true)
            }
            voiceList.filter { accentFilter.isEmpty() || VoicePicker.accent(it) == accentFilter }
                .sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }.thenBy { names[it.name]?.first })
                .forEach { v ->
                    val (title, details) = names[v.name] ?: (v.name to "")
                    voiceRow(title, details, prefs.voiceName == v.name, onPlay = { playSample(v) }) {
                        prefs.voiceName = v.name
                        DriveLog.log("Setting changed: voice = ${v.name}")
                        render(); playSample(v)
                    }
                }
            if (!dutch && voiceList.none { VoicePicker.accent(it) == "GB" }) {
                navRow("🇬🇧  Get British voices", "Tap → Google speech engine → Install voice data → English (United Kingdom)", highlight = true) { openTtsSettings() }
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

        header("More voices")
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
    }

    /** A short line in [voice] (or the automatic choice) with the current speed and pitch. */
    private fun playSample(voice: android.speech.tts.Voice?, automatic: Boolean = false) {
        val e = voiceTts ?: return
        if (!voiceTtsReady) return
        val dutch = prefs.greetingDutch
        val v = voice
            ?: (if (!automatic) voiceList.firstOrNull { it.name == prefs.voiceName } else null)
            ?: (if (prefs.butler && !dutch) VoicePicker.butlerVoice(voiceList) else null)
            ?: VoicePicker.best(voiceList, dutch)
        try {
            if (v != null) e.voice = v else e.language = VoicePicker.localeFor(dutch)
            e.setSpeechRate(prefs.speechRate)
            e.setPitch(prefs.speechPitch)
            e.speak(shortSample(), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sample")
        } catch (ex: Exception) {
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
            prefs.butler && dutch -> "$hello$who. Uw auto staat klaar, wanneer u maar wilt."
            prefs.butler -> "$hello$who. Your car is ready whenever you are."
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
        background = Style.button(if (selected) Style.HIGHLIGHT else Style.SURFACE, dp(14).toFloat())
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
    private fun voiceRow(title: String, details: String, selected: Boolean, onPlay: () -> Unit, onSelect: () -> Unit) =
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Style.button(if (selected) Style.HIGHLIGHT else Style.SURFACE, dp(14).toFloat())
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
            addView(smallBtn("▶") { onPlay() })
        })

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
        text("• Internet only for update checks (GitHub), weather (Open-Meteo, location rounded to ~10 km) and, if on, " +
            "drive news (NOS / NL Times; Google News is searched for your town name).\n" +
            "• Notifications are never read. Logs stay on the phone unless you share them.\n" +
            "• Where's my car (optional): your parked spot is saved on the phone only, never sent anywhere.\n" +
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
            if (page == Page.HOME) {
                val sig = homeSignature()
                if (sig != lastHomeSignature) render()
            }
            handler.postDelayed(this, 5000)
        }
    }

    private var lastHomeSignature = ""

    /** Everything the home screen shows that can change on its own (the rest changes via taps / resume). */
    private fun homeSignature(): String {
        val svc = DriveMonitorService.instance
        val weatherFresh = System.currentTimeMillis() - prefs.lastWeatherAt < 3 * 60 * 60 * 1000L
        return listOf(svc != null, prefs.lastCarState, svc?.currentSong(), prefs.lastWeatherSummary, weatherFresh,
            prefs.lastDrive, prefs.parkedSpot, availableUpdate?.versionName).joinToString("|")
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(homeTick)
        releaseVoiceEngine()
    }

    /** Tesla-style header: car name and status, the car drawn in the theme colour, status tiles around it. */
    private fun heroCard() {
        val svc = DriveMonitorService.instance
        val driving = svc != null && prefs.lastCarState == "Android Auto connected"
        root.addView(TextView(this).apply {
            text = "DRIVE MUSIC"; textSize = 11f; letterSpacing = 0.3f; setTextColor(Style.TEXT2)
            setPadding(dp(4), 0, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = prefs.carName.ifBlank { "My car" }; textSize = 34f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Style.TEXT); setPadding(dp(4), 0, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = when {
                svc == null -> "●  Setup needed"
                driving -> "●  Driving  ·  Android Auto connected"
                else -> "●  Parked"
            }
            textSize = 14f
            setTextColor(when { svc == null -> Style.AMBER; driving -> Style.GREEN; else -> Style.TEXT2 })
            setPadding(dp(4), dp(2), 0, 0)
        })
        val photo = IntroImage.load(this, IntroImage.HERO)
        if (photo != null) {
            root.addView(CarPhotoView(this, photo).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) }
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Car photo")
                        .setItems(arrayOf("Change photo", "Remove photo")) { _, i ->
                            if (i == 0) pickCarPhoto() else { IntroImage.clear(this@MainActivity, IntroImage.HERO); render() }
                        }.show()
                }
            })
        } else {
            navRow("📷  Add a photo of your car", "Shown here; stays on your phone") { pickCarPhoto() }
        }

        val song = svc?.currentSong()
        val weatherFresh = System.currentTimeMillis() - prefs.lastWeatherAt < 3 * 60 * 60 * 1000L
        val lastMins = Regex("""- (\d+) min""").find(prefs.lastDrive)?.groupValues?.get(1)
        val tiles = listOf(
            "♪" to (song?.let { if (it.artist.isNotBlank()) "${it.title} · ${it.artist}" else it.title } ?: "Nothing playing"),
            "☁" to (if (weatherFresh && prefs.lastWeatherSummary.isNotBlank()) prefs.lastWeatherSummary else "Weather after next drive"),
            "⏱" to when (lastMins) { null -> "No drives yet"; "0" -> "Last drive: <1 min"; else -> "Last drive: $lastMins min" },
            "📍" to (Parking.spot(this)?.let { "Parked ${Parking.whenText(it.time)}" } ?: "Where's my car?"),
        )
        tiles.chunked(2).forEach { pair ->
            row(*pair.map { (icon, label) ->
                statusTile(icon, label).apply {
                    val action: (() -> Unit)? = when (icon) {
                        "♪" -> { { openMusicApp() } }
                        "☁" -> { { go(Page.GREETING) } }
                        "⏱" -> { { lastDriveDetails() } }
                        "📍" -> { { whereIsMyCar() } }
                        else -> null
                    }
                    if (action != null) { background = Style.button(Style.SURFACE, dp(12).toFloat()); setOnClickListener { action() } }
                }
            }.toTypedArray())
        }
    }

    private fun openMusicApp() {
        packageManager.getLaunchIntentForPackage(prefs.musicPackage)?.let { startActivity(it) } ?: toast("Music app not installed")
    }

    private fun lastDriveDetails() {
        AlertDialog.Builder(this).setTitle("Last drive")
            .setMessage(prefs.lastDrive.ifBlank { "No drives yet - connect to Android Auto and it shows up here." })
            .setPositiveButton("Open logs") { _, _ -> startActivity(Intent(this, LogActivity::class.java)) }
            .setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------- where's my car

    private fun whereIsMyCar() {
        val spot = Parking.spot(this)
        val tracker = Parking.trackerApp(this)
        val on = prefs.rememberParking && Parking.hasPermission(this) && Parking.hasBackgroundPermission(this)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (spot != null) items += "🗺  Walk there in Maps" to {
            try { startActivity(Parking.mapsIntent(spot)) } catch (e: Exception) { toast("No maps app found") }
        }
        if (tracker != null) items += "📡  Open ${tracker.second} (tracker)" to { startActivity(tracker.first) }
        items += (if (on) "Stop remembering where I park" else "📍  Remember where I park") to {
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

    private fun statusTile(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = Style.rounded(Style.SURFACE, dp(12).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, dp(6), dp(6), 0)
        }
        addView(TextView(this@MainActivity).apply {
            text = icon; textSize = 15f; setTextColor(Style.PRIMARY); setPadding(0, 0, dp(8), 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 13f; setTextColor(Style.TEXT)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
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
                when { highlight -> Style.HIGHLIGHT; warn -> Style.WARN_BG; else -> Style.SURFACE }, dp(14).toFloat()
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
        playSample(null, automatic = prefs.voiceName.isBlank())
    }

    /** The real greeting (your style, voice, weather) - not counted as "greeted" for the next drive. */
    private fun previewVoice() {
        voiceTts?.stop()
        toast("Speaking... turn up the media volume")
        WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false)
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
        private const val REQ_LOCATION = 3
        private const val REQ_PARKING = 14
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_INTRO_IMAGE = 12
        private const val REQ_CAR_PHOTO = 13
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
