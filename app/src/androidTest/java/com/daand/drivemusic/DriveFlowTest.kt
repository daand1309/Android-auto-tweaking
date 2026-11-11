package com.daand.drivemusic

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.car.app.connection.CarConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests on a real Android system: the real DriveMonitorService (bound by Android as a
 * notification listener) controls a fake "YouTube Music" media session. Android Auto can't run on
 * an emulator, so the car connection is fed in through the service's test hook, which uses the
 * same code path as the real Android Auto signal.
 */
@RunWith(AndroidJUnit4::class)
class DriveFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var session: MediaSession

    @Volatile private var playCalls = 0
    @Volatile private var pauseCalls = 0
    @Volatile private var currentState = PlaybackState.STATE_PAUSED

    // ------------------------------------------------------------------ setup

    @Before
    fun setUp() {
        DriveLog.init(ctx)
        prefs = Prefs(ctx).apply {
            musicPackage = ctx.packageName  // our fake player lives in this package
            autoStart = true
            autoResume = true
            resumeDelaySec = 2
            startDelaySec = 0
            pauseOnDisconnect = true
            setVolumeOnConnect = false
            greetingEnabled = false
        }

        val component = ComponentName(ctx, DriveMonitorService::class.java)
        shell("cmd notification allow_listener ${component.flattenToString()}")
        NotificationListenerService.requestRebind(component)
        waitFor("monitor service to be bound", 20_000) { DriveMonitorService.instance != null }

        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        DriveLog.clear()

        onMain {
            session = MediaSession(ctx, "FakeYouTubeMusic").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { playCalls++; setState(PlaybackState.STATE_PLAYING) }
                    override fun onPlayFromSearch(query: String?, extras: Bundle?) { onPlay() }
                    override fun onPause() { pauseCalls++; setState(PlaybackState.STATE_PAUSED) }
                }, main)
                isActive = true
            }
            setState(PlaybackState.STATE_PAUSED)
        }
        waitFor("service to find the fake music app", 10_000) { log().contains("Watching YouTube Music") }
    }

    @After
    fun tearDown() {
        onMain {
            DriveMonitorService.instance?.simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            session.release()
        }
        println("----- drive log -----\n" + log())
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun monitorRunsWithoutAndroidAutoInstalled() {
        assertNotNull(DriveMonitorService.instance)
        assertEquals("not connected", prefs.lastCarState)
    }

    @Test
    fun testButtonStartsMusic() {
        onMain { service().startMusic("manual test button", requireCar = false) }
        waitFor("music to play", 10_000) { currentState == PlaybackState.STATE_PLAYING }
        assertTrue(playCalls >= 1)
        waitFor("success logged", 5_000) { log().contains("START: success") }
    }

    @Test
    fun connectingToCarStartsMusic() {
        connectCar()
        assertTrue(log().contains("=== CAR: Android Auto connected ==="))
        waitFor("success logged", 5_000) { log().contains("START: success") }
    }

    @Test
    fun autoStartOffDoesNothing() {
        prefs.autoStart = false
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        SystemClock.sleep(4_000)
        assertEquals(PlaybackState.STATE_PAUSED, currentState)
        assertTrue(log().contains("Auto-start is turned off"))
    }

    @Test
    fun pauseWhileDrivingIsLoggedAndAutoResumed() {
        connectCar()
        val playsBefore = playCalls
        onMain { setState(PlaybackState.STATE_PAUSED) }  // something interrupts the music
        waitFor("pause to be logged", 5_000) { log().contains("!!! PAUSE #1 while driving") }
        waitFor("auto-resume", 15_000) { currentState == PlaybackState.STATE_PLAYING && playCalls > playsBefore }
        assertTrue(log().contains("RESUME: auto-resuming"))
    }

    @Test
    fun autoResumeOffLeavesMusicPaused() {
        prefs.autoResume = false
        connectCar()
        onMain { setState(PlaybackState.STATE_PAUSED) }
        SystemClock.sleep(6_000)
        assertEquals(PlaybackState.STATE_PAUSED, currentState)
        assertTrue(log().contains("Auto-resume is off"))
    }

    @Test
    fun pausingRightAfterAutoResumeIsTreatedAsDeliberate() {
        connectCar()
        onMain { setState(PlaybackState.STATE_PAUSED) }
        waitFor("auto-resume", 15_000) { log().contains("RESUME: auto-resuming") && currentState == PlaybackState.STATE_PLAYING }
        SystemClock.sleep(500)
        onMain { setState(PlaybackState.STATE_PAUSED) }  // user pauses again
        waitFor("second pause logged", 5_000) { log().contains("!!! PAUSE #2") }
        val pauseLine = log().lines().last { it.contains("PAUSE #2") }
        assertTrue("pause should have no clues: $pauseLine\n${log()}", pauseLine.contains("no clues"))
        assertTrue(log().contains("looks deliberate"))
        SystemClock.sleep(6_000)
        assertEquals(PlaybackState.STATE_PAUSED, currentState)
    }

    @Test
    fun leavingTheCarPausesMusicAndSavesSummary() {
        connectCar()
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        waitFor("music to pause", 5_000) { currentState == PlaybackState.STATE_PAUSED }
        assertTrue(pauseCalls >= 1)
        assertTrue(log().contains("Paused music because you left the car"))
        assertTrue(prefs.lastDrive.contains("pause(s)"))
    }

    @Test
    fun greetingPlaysThenMusicStarts() {
        prefs.greetingEnabled = true
        prefs.weatherEnabled = false
        prefs.greetingName = "Mr. Test"
        prefs.announceSong = true
        prefs.lastGreetingAt = 0L
        onMain {
            session.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Test Song")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
                    .build()
            )
        }
        waitFor("song info to reach the service", 5_000) { service().currentSong()?.title == "Test Song" }
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        // Whether or not the emulator has a voice installed, music must start after the greeting.
        waitFor("music to start after the greeting", 60_000) { currentState == PlaybackState.STATE_PLAYING }
        val log = log()
        assertTrue(log, log.contains("GREETING: \"Good"))
        assertTrue(log, log.contains("Mister Test"))
        assertTrue(log, log.contains("Up first: Test Song by Test Artist."))
        assertTrue(log, Regex("GREETING: (spoken|no text-to-speech|speech|timed out)").containsMatchIn(log))
    }

    @Test
    fun noSecondGreetingOnQuickReconnect() {
        prefs.greetingEnabled = true
        prefs.weatherEnabled = false
        prefs.lastGreetingAt = System.currentTimeMillis()  // greeted a minute ago
        connectCar()
        assertTrue(log().contains("GREETING: skipped"))
    }

    @Test
    fun screensOpenWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { it.onActivity { a -> assertTrue(!a.isFinishing) } }
        DriveLog.log("!!! PAUSE #1 while driving. Clues: test")
        ActivityScenario.launch(LogActivity::class.java).use { it.onActivity { a -> assertTrue(!a.isFinishing) } }
    }

    // ------------------------------------------------------------------ helpers

    private fun connectCar() {
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        waitFor("music to start on connect", 10_000) { currentState == PlaybackState.STATE_PLAYING }
    }

    private fun setState(state: Int) {
        currentState = state
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0L, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .build()
        )
    }

    private fun service() = DriveMonitorService.instance!!

    private fun log() = DriveLog.readAll()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out waiting for $what.\n----- drive log -----\n${log()}")
    }
}
