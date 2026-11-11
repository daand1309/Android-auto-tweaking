package com.daand.drivemusic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
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

    private fun render() {
        val y = scroll.scrollY  // keep scroll position when re-drawing after a button tap
        root.removeAllViews()
        toFix = 0
        val pm = getSystemService(PowerManager::class.java)

        title("Drive Music")
        text("Starts YouTube Music (official or patched, e.g. Morphe) when Android Auto connects, resumes it if it pauses, and logs what happened so problems can be fixed.")

        // ---------------- status
        header("Status")
        val running = DriveMonitorService.instance != null
        text((if (running) "✅ Monitor running" else "❌ Monitor NOT running - grant Notification access below") +
            "\nCar: ${prefs.lastCarState}" +
            if (prefs.lastDrive.isNotEmpty()) "\nLast drive: ${prefs.lastDrive}" else "")
        val checklistHeader = TextView(this)
        root.addView(checklistHeader)

        check(
            "1. Notification access (required)",
            "Only used to control YouTube Music and keep this app running - it never reads your notifications. " +
                "If the switch is greyed out ('Restricted setting'): tap 'App info' → ⋮ (top right) → " +
                "'Allow restricted settings', then come back and tap 'Open'.",
            hasNotificationAccess(), "Open"
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        if (!hasNotificationAccess()) button("App info (for 'Allow restricted settings')") { openAppSettings(packageName) }

        check(
            "2. This app: battery Unrestricted",
            "Stops Samsung from putting this app to sleep. In the page that opens: Battery → Unrestricted.",
            pm.isIgnoringBatteryOptimizations(packageName), "Open"
        ) { openAppSettings(packageName) }

        val music = prefs.musicPackage
        check(
            "3. ${MusicApps.label(this, music)}: battery Unrestricted",
            "In the page that opens: Battery → Unrestricted.",
            pm.isIgnoringBatteryOptimizations(music), "Open"
        ) { openAppSettings(music) }

        val microG = MusicApps.findMicroG(this)
        if (microG != null) {
            check(
                "3b. MicroG (${MusicApps.label(this, microG)}): battery Unrestricted",
                "Patched YouTube Music needs MicroG to stay signed in and stream. If Samsung puts it to sleep, music can stop.",
                pm.isIgnoringBatteryOptimizations(microG), "Open"
            ) { openAppSettings(microG) }
        }

        check(
            "4. Android Auto: battery Unrestricted",
            "In the page that opens: Battery → Unrestricted.",
            pm.isIgnoringBatteryOptimizations(DriveMonitorService.ANDROID_AUTO), "Open"
        ) { openAppSettings(DriveMonitorService.ANDROID_AUTO) }

        if (Build.VERSION.SDK_INT >= 31) {
            check(
                "5. Nearby devices (Bluetooth)",
                "Optional: only used to show your car's Bluetooth name in the log.",
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Allow"
            ) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
        }

        checklistHeader.apply {
            text = if (toFix == 0) "Setup checklist - all done ✅" else "Setup checklist - $toFix to fix ⚠️"
            textSize = 19f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(20), 0, dp(6))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            check(
                "7. Notifications",
                "Optional: only used to tell you when an app update is available.",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Allow"
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
        }

        // ---------------- settings
        header("Settings")
        val installed = MusicApps.isInstalled(packageManager, music)
        text((if (installed) "🎵 " else "❌ ") + "Music app: ${MusicApps.label(this, music)}\n$music" +
            if (music != MusicApps.OFFICIAL_YTM && installed) "\n(patched build detected - good)" else "")
        button("Change music app") { pickMusicApp() }
        switch("Start music when Android Auto connects", prefs.autoStart) { prefs.autoStart = it }
        switch("Auto-resume if music pauses while driving", prefs.autoResume) { prefs.autoResume = it }
        stepper("Resume after", "${prefs.resumeDelaySec}s",
            { prefs.resumeDelaySec = (prefs.resumeDelaySec - 2).coerceAtLeast(2) },
            { prefs.resumeDelaySec = (prefs.resumeDelaySec + 2).coerceAtMost(60) })
        text("Never resumes during calls, while the Assistant talks, or while another app plays. " +
            "If you pause again within a minute of an auto-resume, it takes the hint and stays off until the next drive. " +
            "Tip: add the 'Auto-resume' Quick Settings tile to switch it on/off quickly.")
        stepper("Wait after connecting", "${prefs.startDelaySec}s",
            { prefs.startDelaySec = (prefs.startDelaySec - 1).coerceAtLeast(0) },
            { prefs.startDelaySec = (prefs.startDelaySec + 1).coerceAtMost(30) })
        switch("Pause music when leaving the car", prefs.pauseOnDisconnect) { prefs.pauseOnDisconnect = it }
        switch("Set media volume when connecting", prefs.setVolumeOnConnect) { prefs.setVolumeOnConnect = it; render() }
        if (prefs.setVolumeOnConnect) {
            stepper("Volume", "${prefs.connectVolumePct}%",
                { prefs.connectVolumePct = (prefs.connectVolumePct - 10).coerceAtLeast(10) },
                { prefs.connectVolumePct = (prefs.connectVolumePct + 10).coerceAtMost(100) })
            text("Note: with some cars the car's own volume knob is used and this has no effect.")
        }

        // ---------------- greeting
        header("Welcome greeting")
        switch("Say a welcome when Android Auto connects", prefs.greetingEnabled) { prefs.greetingEnabled = it; render() }
        if (prefs.greetingEnabled) {
            val name = prefs.greetingName.ifBlank { "(not set)" }
            stepperLike("Name: $name", "Change") { askName() }
            switch("Speak in Dutch (Nederlands)", prefs.greetingDutch) { prefs.greetingDutch = it }
            switch("Say which song will play", prefs.announceSong) { prefs.announceSong = it }
            switch("Include the weather", prefs.weatherEnabled) { prefs.weatherEnabled = it; render() }
            if (prefs.weatherEnabled) {
                val place = when {
                    prefs.weatherPlace.isNotBlank() -> prefs.weatherPlace
                    !prefs.weatherLat.isNaN() -> "your location"
                    else -> "(not set)"
                }
                switch("Include road conditions (ice, fog, rain, wind, darkness)", prefs.roadConditionsEnabled) { prefs.roadConditionsEnabled = it }
                text("Weather for: $place")
                row(
                    Button(this).apply { text = "📍 Use my location"; isAllCaps = false; setOnClickListener { useMyLocation() } },
                    Button(this).apply { text = "🔎 Type a city"; isAllCaps = false; setOnClickListener { askCity() } },
                )
            }
            // ---- voice & pronunciation
            text("Voice & pronunciation")
            stepperLike("Pronounce name as: ${prefs.namePronunciation.ifBlank { "(same as name)" }}", "Change") { askPronunciation() }
            stepperLike("Voice: ${prefs.voiceName.ifBlank { "automatic (best available)" }}", "Choose") { pickVoice() }
            stepper("Speed", "%.2fx".format(prefs.speechRate),
                { prefs.speechRate = (prefs.speechRate - 0.05f).coerceAtLeast(0.6f) },
                { prefs.speechRate = (prefs.speechRate + 0.05f).coerceAtMost(1.4f) })
            stepper("Pitch", "%.2f".format(prefs.speechPitch),
                { prefs.speechPitch = (prefs.speechPitch - 0.05f).coerceAtLeast(0.6f) },
                { prefs.speechPitch = (prefs.speechPitch + 0.05f).coerceAtMost(1.4f) })
            button("▶ Preview voice") { previewVoice() }
            if (!MusicApps.isInstalled(packageManager, VoicePicker.GOOGLE_TTS)) {
                text("Tip: for a much more natural voice, install 'Speech Services by Google' and choose it as the voice engine.")
                button("Get Speech Services by Google") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${VoicePicker.GOOGLE_TTS}")))
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${VoicePicker.GOOGLE_TTS}")))
                    }
                }
            }
            button("Download better voices (phone speech settings)") {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { toast("Open Settings → General management → Text-to-speech") }
            }

            button("🔊 Test greeting now") {
                toast("Speaking... turn up the media volume")
                WelcomeAnnouncer.run(this, DriveMonitorService.instance?.currentSong())
            }
            text("The greeting plays before your music starts (not again if you reconnect within 10 minutes).")
        }

        // ---------------- actions
        header("Test & logs")
        button("▶ Test: start YouTube Music now") {
            val svc = DriveMonitorService.instance
            if (svc == null) toast("Grant Notification access first") else {
                svc.startMusic("manual test button", requireCar = false)
                toast("Trying... check the logs")
            }
        }
        button("📄 View / share logs") { startActivity(Intent(this, LogActivity::class.java)) }

        // ---------------- updates
        header("Updates")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        text("Installed version: $version" +
            (if (updateMessage.isNotEmpty()) "\n$updateMessage" else "") +
            (if (prefs.updateStatus.isNotEmpty() && updateMessage.isEmpty()) "\nLast update: ${prefs.updateStatus}" else ""))
        val upd = availableUpdate
        if (upd != null) {
            button(if (updateBusy) "Downloading..." else "⬇ Install version ${upd.versionName}") { if (!updateBusy) installUpdate(upd) }
            if (upd.notes.isNotBlank()) text("What's new:\n" + upd.notes.take(600))
        }
        button(if (updateBusy) "Working..." else "Check for updates now") { if (!updateBusy) checkForUpdate(silent = false) }
        switch("Check for updates automatically (daily, never while driving)", prefs.autoUpdateCheck) { prefs.autoUpdateCheck = it }

        // ---------------- privacy
        header("Privacy & security")
        text("• Internet is only used to check this app's own GitHub page for updates (turn off above). " +
            "No data about you, your music or your logs is ever sent.\n" +
            "• Updates are only installed after a checksum and signature check, and Android always asks you first.\n" +
            "• It never reads your notifications; notification access is only needed to press play/pause.\n" +
            "• Weather: only your approximate location (rounded to ~10 km) is sent to Open-Meteo, a free weather service, " +
            "when the greeting plays. Your location is never tracked in the background.\n" +
            "• Logs stay on your phone and are only shared when you tap Share.\n" +
            "• No overlay, accessibility, location, contacts, SMS or storage permissions.")

        // ---------------- manual steps
        header("Drive Music on the car screen")
        text(
            "There's now a Drive Music screen on the Android Auto display (now playing, status, and Play / Welcome / " +
                "Auto-resume buttons). To switch it on, once:\n" +
                "Android Auto → Settings → tap 'Version' 10 times → ⋮ (top right) → Developer settings → turn on 'Unknown sources'.\n" +
                "Then Drive Music appears in the car's app grid. (This is personal/developer mode — the screen uses only " +
                "Android Auto's safety-approved buttons and lists.)"
        )

        header("Things only you can change")
        text(
            "• Samsung: Settings → Battery → Background usage limits → make sure YouTube Music, Android Auto and Drive Music " +
                "are NOT in 'Sleeping apps' or 'Deep sleeping apps'. Add them to 'Never sleeping apps'.\n\n" +
                "• Samsung Modes & Routines: you can keep your 'open YouTube Music' routine - this app will press play after it opens. " +
                "Remove any 'Play/pause media' step from the routine so they don't fight.\n\n" +
                "• YouTube Music → Settings → Downloads & storage → turn on Smart downloads, and download your driving playlists " +
                "(stops pauses in areas with no signal). Make sure 'Stream via Wi-Fi only' is OFF.\n\n" +
                "• YouTube Music → Settings → General → 'Remind me to take a break' → OFF.\n\n" +
                "• Android Auto settings → turn off notification sounds / message read-outs if logs show NOTIFICATION or ASSISTANT pauses.\n\n" +
                "• If logs show 'GOOGLE ASSISTANT' just before pauses, 'Hey Google' is being triggered by road noise: " +
                "Google app → Settings → Google Assistant → Hey Google & Voice Match → turn off or lower sensitivity.\n\n" +
                "• Patched YouTube Music (Morphe/ReVanced): keep MicroG updated and signed in, and set it to Unrestricted battery too. " +
                "If the app ever disappears from the car screen: Android Auto settings → tap 'Version' 10 times → ⋮ → Developer settings → " +
                "turn on 'Unknown sources'.\n\n" +
                "• Wired Android Auto: if the logs show Bluetooth/car disconnects, try a different short USB cable."
        )
        button("Open music app") {
            packageManager.getLaunchIntentForPackage(prefs.musicPackage)?.let { startActivity(it) }
                ?: toast("Music app not installed")
        }
        scroll.post { scroll.scrollTo(0, y) }
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

    private fun title(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
    })

    private fun header(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(20), 0, dp(6))
    })

    private fun text(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 14f; setPadding(0, dp(2), 0, dp(6))
    })

    private fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    })

    private fun row(vararg views: View) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    })

    private fun stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) = row(
        TextView(this).apply {
            text = "$label: $value"; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        Button(this).apply { text = "−"; setOnClickListener { minus(); render() } },
        Button(this).apply { text = "+"; setOnClickListener { plus(); render() } },
    )

    private fun check(label: String, help: String, ok: Boolean, action: String, onClick: () -> Unit) {
        if (!ok) toFix++
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = (if (ok) "✅ " else "⚠️ ") + label; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply { text = help; textSize = 13f })
            })
            if (!ok) addView(Button(this@MainActivity).apply {
                text = action; isAllCaps = false; setOnClickListener { onClick() }
            })
        })
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = root.addView(Switch(this).apply {
        text = label; textSize = 15f; isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v); DriveLog.log("Setting changed: $label = $v") }
    })

    companion object {
        private const val REQ_LOCATION = 3
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
