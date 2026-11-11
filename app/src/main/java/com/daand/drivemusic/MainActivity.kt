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
@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n", "BatteryLife")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, DriveMonitorService::class.java))
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val pm = getSystemService(PowerManager::class.java)

        title("Drive Music")
        text("Starts YouTube Music (official or patched, e.g. Morphe) when Android Auto connects, resumes it if it pauses, and logs what happened so problems can be fixed.")

        // ---------------- status
        header("Status")
        val running = DriveMonitorService.instance != null
        text((if (running) "✅ Monitor running" else "❌ Monitor NOT running - grant Notification access below") +
            "\nCar: ${prefs.lastCarState}")

        // ---------------- checklist
        header("Setup checklist (do all of these)")

        check(
            "1. Notification access (required)",
            "Lets this app see and control YouTube Music and keeps it running in the background.",
            hasNotificationAccess(), "Open"
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        check(
            "2. This app: battery Unrestricted",
            "Stops Samsung from putting this app to sleep.",
            pm.isIgnoringBatteryOptimizations(packageName), "Fix"
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

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
                "Optional: lets the log show your car's Bluetooth name when it connects/disconnects.",
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Allow"
            ) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
        }

        check(
            "6. Display over other apps",
            "Optional but recommended: lets this app open YouTube Music while the screen is off / you're in Maps.",
            Settings.canDrawOverlays(this), "Allow"
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        // ---------------- settings
        header("Settings")
        val installed = MusicApps.isInstalled(packageManager, music)
        text((if (installed) "🎵 " else "❌ ") + "Music app: ${MusicApps.label(this, music)}\n$music" +
            if (music != MusicApps.OFFICIAL_YTM && installed) "\n(patched build detected - good)" else "")
        button("Change music app") { pickMusicApp() }
        switch("Start music when Android Auto connects", prefs.autoStart) { prefs.autoStart = it }
        switch("Auto-resume if music pauses while driving", prefs.autoResume) { prefs.autoResume = it }
        text("Resume after ${prefs.resumeDelaySec} seconds. It won't resume during calls, while the Assistant talks, " +
            "or if another app is playing. If you pause on purpose it will still resume (max 4 times per 10 min) - turn it off if that annoys you.")
        row(
            Button(this).apply { text = "- 2s"; setOnClickListener { prefs.resumeDelaySec = (prefs.resumeDelaySec - 2).coerceAtLeast(2); render() } },
            Button(this).apply { text = "+ 2s"; setOnClickListener { prefs.resumeDelaySec = (prefs.resumeDelaySec + 2).coerceAtMost(60); render() } },
        )

        // ---------------- actions
        header("Test & logs")
        button("▶ Test: start YouTube Music now") {
            val svc = DriveMonitorService.instance
            if (svc == null) toast("Grant Notification access first") else {
                svc.startMusic("manual test button")
                toast("Trying... check the logs")
            }
        }
        button("📄 View / share logs") { startActivity(Intent(this, LogActivity::class.java)) }

        // ---------------- manual steps
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
        button("Open YouTube Music settings") {
            packageManager.getLaunchIntentForPackage(prefs.musicPackage)?.let { startActivity(it) }
                ?: toast("Music app not installed")
        }
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
        views.forEach { addView(it) }
    })

    private fun check(label: String, help: String, ok: Boolean, action: String, onClick: () -> Unit) {
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
