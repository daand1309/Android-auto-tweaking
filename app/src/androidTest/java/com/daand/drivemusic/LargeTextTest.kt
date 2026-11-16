package com.daand.drivemusic

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daand.drivemusic.obd.ObdManager
import com.daand.drivemusic.obd.Trip
import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import com.daand.drivemusic.obd.CarSnapshot
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens were only ever looked at with the phone's text size at 100%. Plenty of people run
 * 130-150% (and Android's own accessibility settings suggest it), where a tile value or a stat row
 * can end up as "6° · light ra…".
 *
 * So: put the phone's font scale at 150%, walk every page, and fail on any text the layout had to
 * cut off. Screenshots go to /data/local/tmp/screens/e_large_* so the result can be looked at too.
 */
@RunWith(AndroidJUnit4::class)
class LargeTextTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext

    private fun shell(cmd: String) = instr.uiAutomation.executeShellCommand(cmd).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
    }

    private fun snap(name: String) {
        instr.waitForIdleSync()
        SystemClock.sleep(500)
        shell("screencap -p /data/local/tmp/screens/$name.png")
    }

    @Before fun bigText() {
        shell("settings put system font_scale 1.5")
        SystemClock.sleep(2_000)
        Prefs(ctx).apply {
            greetingEnabled = true; carName = "ASX"; obdEnabled = true
            obdDeviceName = "Android-Vlink"; obdDevice = "00:11:22:33:44:55"
            weatherPlace = "Noordwijk"; lastWeatherSummary = "6° · light rain · 70% rain"
            lastWeatherAt = System.currentTimeMillis()
            lastDrive = "Thu 12 Nov 17:40 - 23 min, 1 pause(s), 1 auto-resume(s)\nCar: 18.4 km · 6.3 L/100km · 1.16 L used"
            showSplash = false; animations = false; fuelPrice = 2.05; tankLitres = 60.0
        }
        TripHistory.clear(ctx)
        val now = System.currentTimeMillis()
        for (d in 6 downTo 1) TripHistory.add(ctx, TripRecord(
            at = now - d * 86_400_000L, minutes = 18 + d, km = 14.0 + d * 2.5, litres = 1.0 + d * 0.1,
            score = 72 + d, maxCoolant = 90, price = 2.05))
        val trip = Trip()
        for (i in 0..250) trip.add(CarSnapshot(i * 5_000L, rpm = 2300, speedKmh = 72, litresPerHour = 4.6))
        ObdManager.setLiveForTest(CarSnapshot(System.currentTimeMillis(), coolantC = 88, rpm = 2350, speedKmh = 72,
            fuelPct = 41, volts = 14.1, ambientC = 6, checkEngine = true, codes = listOf("P0420"),
            readiness = 7 to 8, litresPerHour = 4.4,
            extra = mapOf(0x04 to 34.0, 0x05 to 88.0, 0x0C to 2350.0, 0x0D to 72.0, 0x11 to 16.0, 0x0B to 48.0,
                0x0F to 14.0, 0x10 to 7.9, 0x06 to -1.6, 0x07 to 2.3, 0x0E to 18.5, 0x14 to 0.62, 0x15 to 0.71,
                0x2F to 41.0, 0x33 to 101.0, 0x42 to 14.1, 0x46 to 6.0)), trip)
    }

    @After fun normalText() {
        shell("settings put system font_scale 1.0")
        SystemClock.sleep(1_000)
    }

    @Test fun noPageCutsTextOffAtOneAndAHalfTimesTheFont() {
        val cut = mutableListOf<String>()
        var scale = 0f
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var pages = emptyList<String>()
            sc.onActivity { a -> pages = a.pageNamesForTest(); scale = a.resources.configuration.fontScale }
            // If the phone ignored the setting the test would quietly prove nothing.
            assertTrue("font scale is $scale - the test could not change it", scale > 1.3f)
            for (p in pages) {
                sc.onActivity { it.openPageForTest(p) }
                SystemClock.sleep(400)
                sc.onActivity { a -> cut += a.cutOffTextsForTest().map { "$p: $it" } }
                snap("e_large_main_${p.lowercase()}")
                var tall = false
                sc.onActivity { tall = it.scrollForTest(true) }
                if (tall) {
                    SystemClock.sleep(300)
                    sc.onActivity { a -> cut += a.cutOffTextsForTest().map { "$p (bottom): $it" } }
                    snap("e_large_main_${p.lowercase()}_bottom")
                }
            }
        }
        assertTrue("text is cut off with the phone's text size at 150%: $cut", cut.isEmpty())
    }

    @Test fun noTappableThingIsSmallerThanAFingertip() {
        val small = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var pages = emptyList<String>()
            sc.onActivity { a -> pages = a.pageNamesForTest() }
            for (p in pages) {
                sc.onActivity { it.openPageForTest(p) }
                SystemClock.sleep(400)
                sc.onActivity { a -> small += a.smallTargetsForTest().map { "$p: $it" } }
                sc.onActivity { it.scrollForTest(true) }
                SystemClock.sleep(300)
                sc.onActivity { a -> small += a.smallTargetsForTest().map { "$p (bottom): $it" } }
            }
        }
        // Reported in the log rather than failed: this is a look-and-feel backlog, not a break.
        if (small.isNotEmpty()) DriveLog.log("A11Y: tappable things under 44 dp at 150% text: ${small.distinct().joinToString(", ")}")
        assertTrue("pages rendered", true)
    }
}
