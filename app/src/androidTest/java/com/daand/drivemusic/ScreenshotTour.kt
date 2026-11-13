package com.daand.drivemusic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.ObdManager
import com.daand.drivemusic.obd.Trip
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a pass/fail test: photographs every screen (portrait, landscape, small phone) so the UI can be
 * reviewed. Screenshots go to /data/local/tmp/screens (survives the app being uninstalled after the
 * tests); the CI workflow collects them as an artifact.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private fun shell(cmd: String) = instr.uiAutomation.executeShellCommand(cmd).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
    }

    private fun snap(name: String) {
        instr.waitForIdleSync()
        SystemClock.sleep(700)
        shell("screencap -p /data/local/tmp/screens/$name.png")
    }

    private fun setUpRealisticState() {
        Prefs(ctx).apply {
            greetingEnabled = true; greetingName = "Mr. Dickhoff"; greetingStyle = "butler"; carName = "ASX"
            obdEnabled = true; obdDeviceName = "Android-Vlink"; obdDevice = "00:11:22:33:44:55"
            weatherPlace = "Noordwijk"; lastWeatherSummary = "6° · light rain · 70% rain"; lastWeatherAt = System.currentTimeMillis()
            lastDrive = "Thu 12 Nov 17:40 - 23 min, 1 pause(s), 1 auto-resume(s)\nCar: 18.4 km · 6.3 L/100km · 1.16 L used"
            showSplash = false; autoOpenDriveMode = false; followTopic = "kabinet, coalitie, jetten"
            followUntil = System.currentTimeMillis() + 86_400_000L
        }
        Prefs(ctx).apply { fuelPrice = 2.05; tankLitres = 60.0 }
        com.daand.drivemusic.obd.TripHistory.clear(ctx)
        val now = System.currentTimeMillis()
        for (d in 11 downTo 0) com.daand.drivemusic.obd.TripHistory.add(ctx, com.daand.drivemusic.obd.TripRecord(
            at = now - d * 86_400_000L, minutes = 18 + d % 5 * 4, km = 14.0 + d % 4 * 6.5, litres = 0.95 + d % 4 * 0.4,
            score = 72 + d % 3 * 9, maxCoolant = 89 + d % 3,
            // Newest 4 drives drift lean, so the Early warnings card shows up in the screenshots.
            health = com.daand.drivemusic.obd.HealthSample(ltft = if (d < 4) 11.0 else 2.0, chargeV = 14.2, idleRpm = 750.0,
                idleSpread = 12.0, warmupMin = 9.0, cruiseCoolant = 90.0, ambient = 8)))
        refreshLive()
    }

    /** The app treats car data older than 20 s as gone, so renew it right before each shot. */
    private fun refreshLive() {
        val trip = Trip()
        for (i in 0..250) trip.add(CarSnapshot(i * 5_000L, rpm = 2300, speedKmh = 72, litresPerHour = 4.6))
        ObdManager.setLiveForTest(CarSnapshot(System.currentTimeMillis(), coolantC = 88, rpm = 2350, speedKmh = 72, fuelPct = 41,
            volts = 14.1, ambientC = 6, checkEngine = true, codes = listOf("P0420"), pendingCodes = listOf("P0141"),
            readiness = 7 to 8, litresPerHour = 4.4,
            extra = mapOf(0x04 to 34.0, 0x05 to 88.0, 0x0C to 2350.0, 0x0D to 72.0, 0x11 to 16.0, 0x0B to 48.0, 0x0F to 14.0,
                0x10 to 7.9, 0x06 to -1.6, 0x07 to 2.3, 0x0E to 18.5, 0x14 to 0.62, 0x15 to 0.71, 0x2F to 41.0, 0x33 to 101.0,
                0x42 to 14.1, 0x46 to 6.0, 0x31 to 2310.0, 0x21 to 0.0)), trip)
    }

    private fun tourMainApp(prefix: String) {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var pages = emptyList<String>()
            sc.onActivity { pages = it.pageNamesForTest() }
            for (p in pages) {
                refreshLive()
                sc.onActivity { it.openPageForTest(p) }
                SystemClock.sleep(if (p == "VOICE") 3_000 else 500)
                var tall = false
                sc.onActivity { tall = it.scrollForTest(false) }
                snap("${prefix}main_${p.lowercase()}_1top")
                if (tall) { sc.onActivity { it.scrollForTest(true) }; snap("${prefix}main_${p.lowercase()}_2bottom") }
            }
        }
    }

    private fun driveMode(): DriveModeActivity? {
        var a: DriveModeActivity? = null
        instr.runOnMainSync {
            a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<DriveModeActivity>().firstOrNull()
        }
        return a
    }

    private fun tourDriveMode(prefix: String, landscape: Boolean) {
        refreshLive()
        ctx.startActivity(Intent(ctx, DriveModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var tries = 0
        while (driveMode() == null && tries++ < 50) SystemClock.sleep(200)
        val a = driveMode() ?: return
        if (landscape) { instr.runOnMainSync { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }; SystemClock.sleep(2_000) }
        val dm = driveMode() ?: return
        val o = if (landscape) "land" else "port"
        snap("${prefix}drive_${o}_1music")
        refreshLive()
        instr.runOnMainSync { dm.pagerForTest().goTo(1, animate = false) }
        SystemClock.sleep(2_500)
        snap("${prefix}drive_${o}_2car")
        instr.runOnMainSync { UpdatePrompt.open(ctx, "Breaking news", "I do apologise, sir, but there is an update. Shall I go ahead?") }
        snap("${prefix}drive_${o}_3question")
        instr.runOnMainSync { UpdatePrompt.cancel(ctx) }
        instr.runOnMainSync { dm.finish() }
        SystemClock.sleep(800)
    }

    @Test
    fun photographEveryScreen() {
        shell("rm -rf /data/local/tmp/screens"); shell("mkdir -p /data/local/tmp/screens")
        setUpRealisticState()
        tourMainApp("a_")
        tourDriveMode("b_", landscape = false)
        tourDriveMode("b_", landscape = true)
        // A small, low-resolution phone: does everything still fit?
        shell("wm size 720x1280"); shell("wm density 320")
        SystemClock.sleep(1_500)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                for (p in listOf("HOME", "OBD", "TRIPS", "VOICE")) {
                    refreshLive()
                    sc.onActivity { it.openPageForTest(p) }; SystemClock.sleep(if (p == "VOICE") 3_000 else 600)
                    snap("c_small_${p.lowercase()}")
                }
            }
            tourDriveMode("c_small_", landscape = false)
        } finally {
            shell("wm size reset"); shell("wm density reset")
        }
    }

    @After fun tearDown() {
        ObdManager.setLiveForTest(null)
        com.daand.drivemusic.obd.TripHistory.clear(ctx)
        Prefs(ctx).apply { obdEnabled = false; followTopic = ""; followUntil = 0L; greetingStyle = "sassy" }
    }
}
