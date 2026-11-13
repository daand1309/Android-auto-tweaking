package com.daand.drivemusic

import android.content.ComponentName
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.car.app.connection.CarConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not pass/fail: plays a brand-new user. Starts from a clean install state and follows the app the
 * way a person would - reading the screen and tapping what it says - then a first drive. Every step
 * is photographed and written to a journal (/data/local/tmp/screens/journey.txt) with what was on
 * screen and what the app logged, to compare with the user guide.
 */
@RunWith(AndroidJUnit4::class)
class NewUserJourney {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val dir = "/data/local/tmp/screens"
    private val journal = StringBuilder()
    private var step = 0

    private fun shell(cmd: String) = instr.uiAutomation.executeShellCommand(cmd).use { pfd ->
        String(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes())
    }

    private fun note(s: String) { journal.append(s).append('\n') }

    /** Photo + everything readable on screen. */
    private fun look(name: String, root: View?) {
        instr.waitForIdleSync(); SystemClock.sleep(800)
        step++
        val file = "j%02d_%s".format(step, name)
        shell("screencap -p $dir/$file.png")
        val texts = mutableListOf<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is TextView && v.text.isNotBlank()) texts += v.text.toString().replace('\n', ' ')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        root?.let { instr.runOnMainSync { walk(it) } }
        note("\n## Step $step - $name ($file.png)\nOn screen: " + texts.joinToString(" | "))
    }

    /** Taps the first visible thing whose text contains [text] - like a person would. */
    private fun tap(root: View, text: String): Boolean {
        var hit: View? = null
        fun walk(v: View) {
            if (hit != null || !v.isShown) return
            if (v is TextView && v.text.contains(text, ignoreCase = true)) {
                var c: View? = v
                while (c != null && !c.isClickable) c = c.parent as? View
                hit = c
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        instr.runOnMainSync { walk(root) }
        val h = hit
        note(if (h == null) "TAP '$text': NOT FOUND on screen" else "TAP '$text'")
        if (h != null) instr.runOnMainSync { h.performClick() }
        instr.waitForIdleSync(); SystemClock.sleep(600)
        return h != null
    }

    private fun logSince(mark: Int): String = DriveLog.readAll().lines().drop(mark).joinToString("\n") { "    $it" }
    private fun logSize() = DriveLog.readAll().lines().size

    @Test
    fun aNewUserSetsUpAndDrives() {
        shell("mkdir -p $dir"); shell("sh -c 'rm -f $dir/j*.png $dir/journey.txt'")
        // ---- A clean install: no settings, no permissions, no history.
        ctx.getSharedPreferences("drive_music", 0).edit().clear().commit()
        File(ctx.filesDir, "trips.json").delete()
        val listener = ComponentName(ctx, DriveMonitorService::class.java).flattenToString()
        shell("cmd notification disallow_listener $listener")
        shell("dumpsys deviceidle whitelist -${ctx.packageName}")
        DriveLog.init(ctx); DriveLog.clear()
        note("# New user journey (clean install)\nDefaults: " + Prefs(ctx).let {
            "autoStart=${it.autoStart} autoResume=${it.autoResume} resumeDelay=${it.resumeDelaySec}s startDelay=${it.startDelaySec}s " +
                "greeting=${it.greetingEnabled} style=${it.greetingStyle} weather=${it.weatherEnabled} autoOpenDriveMode=${it.autoOpenDriveMode} " +
                "pauseOnDisconnect=${it.pauseOnDisconnect} updatesWeather=${it.updatesWeather} updatesNews=${it.updatesNews} " +
                "askBeforeUpdates=${it.askBeforeUpdates} obd=${it.obdEnabled} musicApp=${it.musicPackage}"
        })

        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var root: View? = null
            sc.onActivity { root = it.window.decorView }
            SystemClock.sleep(1_200)   // the launch animation (the emulator's own animations are off)
            look("first_open", root)
            // ---- What does the first screen ask me to do? Follow the setup prompt.
            if (!tap(root!!, "Setup")) tap(root!!, "steps")
            look("setup_page", root)
            sc.onActivity { it.scrollForTest(true) }
            look("setup_page_bottom", root)

            // ---- Do what the setup page says (in Android's settings, as a person would).
            note("USER: grants Notification access and battery Unrestricted in Android settings")
            shell("cmd notification allow_listener $listener")
            NotificationListenerService.requestRebind(ComponentName.unflattenFromString(listener)!!)
            shell("dumpsys deviceidle whitelist +${ctx.packageName}")
            var waited = 0
            while (DriveMonitorService.instance == null && waited < 20_000) { SystemClock.sleep(500); waited += 500 }
            note("Monitor running after granting access: ${DriveMonitorService.instance != null}")
            sc.onActivity { it.openPageForTest("SETUP") }
            look("setup_after_granting", root)
            sc.onActivity { it.openPageForTest("HOME") }
            look("home_after_setup", root)

            // ---- Personalise: the greeting page.
            tap(root!!, "Welcome greeting") || tap(root!!, "Greeting")
            look("greeting_page", root)
            sc.onActivity { it.openPageForTest("HOME") }

            // ---- No YouTube Music on this test phone: use the stand-in player (like picking it in Music app > Change).
            note("USER: picks the music app (test stand-in for YouTube Music)")
            Prefs(ctx).musicPackage = ctx.packageName
        }

        // ---- The music app: a stand-in that behaves like YouTube Music.
        val main = Handler(Looper.getMainLooper())
        var state = PlaybackState.STATE_PAUSED
        lateinit var session: MediaSession
        fun setState(s: Int) {
            state = s
            session.setPlaybackState(PlaybackState.Builder().setState(s, 0L, if (s == PlaybackState.STATE_PLAYING) 1f else 0f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE).build())
        }
        instr.runOnMainSync {
            session = MediaSession(ctx, "StandInMusic").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { setState(PlaybackState.STATE_PLAYING) }
                    override fun onPause() { setState(PlaybackState.STATE_PAUSED) }
                }, main)
                setMetadata(android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Mr. Brightside")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "The Killers").build())
                isActive = true
            }
            setState(PlaybackState.STATE_PAUSED)
        }
        SystemClock.sleep(3_000)

        // ---- First drive: plug the phone into the car (Android Auto starts).
        val mark = logSize()
        note("\nUSER: gets in the car, Android Auto connects")
        instr.runOnMainSync { DriveMonitorService.instance?.simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        var t = 0
        while (state != PlaybackState.STATE_PLAYING && t < 40_000) { SystemClock.sleep(500); t += 500 }
        note("Music playing ${t / 1000}s after connecting: ${state == PlaybackState.STATE_PLAYING}")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var root: View? = null
            sc.onActivity { root = it.window.decorView }
            look("home_while_driving", root)
        }
        // ---- Something pauses the music mid-drive (the thing the app exists for).
        note("\nEVENT: something pauses the music while driving")
        instr.runOnMainSync { setState(PlaybackState.STATE_PAUSED) }
        t = 0
        while (state != PlaybackState.STATE_PLAYING && t < 30_000) { SystemClock.sleep(500); t += 500 }
        note("Music back on by itself after ${t / 1000}s: ${state == PlaybackState.STATE_PLAYING}")

        // ---- Open Drive Mode from the home screen, as a driver would.
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var root: View? = null
            sc.onActivity { root = it.window.decorView }
            tap(root!!, "Drive Mode")
            SystemClock.sleep(3_000)  // intro animation
            var dm: DriveModeActivity? = null
            instr.runOnMainSync {
                dm = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<DriveModeActivity>().firstOrNull()
            }
            look("drive_mode", dm?.window?.decorView)
            dm?.window?.decorView?.let { tap(it, "Car") }
            look("drive_mode_car_tab", dm?.window?.decorView)
            instr.runOnMainSync { dm?.finish() }
            SystemClock.sleep(800)
        }

        // ---- Park and get out.
        note("\nUSER: parks and unplugs")
        instr.runOnMainSync { DriveMonitorService.instance?.simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        SystemClock.sleep(4_000)
        note("Music after leaving the car: ${if (state == PlaybackState.STATE_PLAYING) "still playing" else "paused"}")
        note("\nWhat the app logged during the drive:\n" + logSince(mark))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var root: View? = null
            sc.onActivity { root = it.window.decorView }
            look("home_after_drive", root)
            tap(root!!, "Last drive")
            look("last_drive_details", root)
            sc.onActivity { it.openPageForTest("HOME") }
            // ---- Curious about the OBD adapter: open Car data.
            tap(root!!, "Car data")
            look("obd_page_new", root)
            sc.onActivity { it.openPageForTest("HELP") }
            look("help_page", root)
        }
        instr.runOnMainSync { session.release() }
        journal.lines().forEach { android.util.Log.i("Journey", it) }   // the journal: in the test's logcat
    }
}
