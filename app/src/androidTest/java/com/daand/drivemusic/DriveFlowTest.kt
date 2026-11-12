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
            autoOpenDriveMode = false  // each test starts clean (one test switches this on)
            showSplash = true
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
    fun transientErrorBlipIsNotCountedAsPause() {
        connectCar()
        val pausesLogged = { log().split("!!! PAUSE").size - 1 }
        val before = pausesLogged()
        // Simulate the Morphe "Premium" blip: PLAYING -> ERROR -> PLAYING within the grace window.
        onMain { setState(PlaybackState.STATE_ERROR) }
        SystemClock.sleep(600)
        onMain { setState(PlaybackState.STATE_PLAYING) }
        waitFor("blip to be recognised", 4_000) { log().contains("Brief interruption") }
        SystemClock.sleep(4_000)  // past the grace period
        assertEquals("a self-recovering blip must not add a pause", before, pausesLogged())
        assertEquals(PlaybackState.STATE_PLAYING, currentState)
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

    /** The real-world case: phone locked with the screen off in the holder, then the car connects. */
    @Test
    fun driveModePopsUpOverTheLockScreenWhenTheCarConnects() {
        prefs.autoOpenDriveMode = true
        prefs.showSplash = false
        prefs.autoStart = false
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        if (android.os.Build.VERSION.SDK_INT >= 34) shell("appops set ${ctx.packageName} USE_FULL_SCREEN_INTENT allow")
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        try {
            // Twice: the second time a banner from the first drive may still exist (the stale-banner bug).
            for (drive in 1..2) {
                onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
                closeDriveMode()
                shell("input keyevent KEYCODE_SLEEP")
                waitFor("screen to switch off (drive $drive)", 10_000) { !power.isInteractive }
                SystemClock.sleep(1_500)
                val lockedBefore = keyguard.isKeyguardLocked
                DriveLog.clear()

                onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }

                waitFor("Drive Mode to appear over the lock screen (drive $drive)", 20_000) { driveModeResumed() }
                assertTrue("screen should be woken by Drive Mode", power.isInteractive)
                val log = log()
                assertTrue(log, log.contains("Drive Mode: opening on connect"))
                if (lockedBefore) assertTrue(log, log.contains("Drive Mode: opened over the lock screen"))
            }
        } finally {
            closeDriveMode()
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun driveModeResumed(): Boolean {
        var found = false
        onMain {
            found = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .any { it is DriveModeActivity }
        }
        return found
    }

    private fun closeDriveMode() {
        onMain {
            androidx.test.runner.lifecycle.Stage.values().forEach { stage ->
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(stage).filterIsInstance<DriveModeActivity>().forEach { it.finish() }
            }
        }
        SystemClock.sleep(500)
    }

    @Test
    fun screensOpenWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { it.onActivity { a -> assertTrue(!a.isFinishing) } }
        DriveLog.log("!!! PAUSE #1 while driving. Clues: test")
        ActivityScenario.launch(LogActivity::class.java).use { it.onActivity { a -> assertTrue(!a.isFinishing) } }
        // Drive Mode: skip the intro overlay and verify the control buttons actually have size
        // (a weighted-layout bug once made them collapse to zero height).
        prefs.showSplash = false
        ActivityScenario.launch(DriveModeActivity::class.java).use { scenario ->
            SystemClock.sleep(600)  // let it lay out
            scenario.onActivity { a ->
                assertTrue(!a.isFinishing)
                val buttons = mutableListOf<android.widget.Button>()
                fun walk(v: android.view.View) {
                    if (v is android.widget.Button) buttons += v
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(a.window.decorView)
                assertTrue("expected control buttons", buttons.size >= 4)
                assertTrue("buttons must have a visible size", buttons.any { it.width > 0 && it.height > 0 })
            }
        }
    }

    @Test
    fun driveModeClosesWhenYouLeaveTheCarButNotWhenOpenedWhileParked() {
        prefs.showSplash = false
        prefs.autoStart = false
        fun openDriveMode() = ctx.startActivity(
            android.content.Intent(ctx, DriveModeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

        // Opened by hand while parked: stays open.
        openDriveMode()
        waitFor("Drive Mode to open", 10_000) { driveModeResumed() }
        SystemClock.sleep(4_000)
        assertTrue("Drive Mode opened while parked must stay open", driveModeResumed())
        closeDriveMode()

        // Open during a drive, then leave the car: closes by itself (no screen left on in your pocket).
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        openDriveMode()
        waitFor("Drive Mode to open", 10_000) { driveModeResumed() }
        SystemClock.sleep(3_500)  // let it see the car connected
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        waitFor("Drive Mode to close after leaving the car", 10_000) { !driveModeResumed() }
        assertTrue(log().contains("Drive Mode: closed - you left the car"))
    }

    @Test
    fun briefingPopsUpOnTheCarScreenAndIsRemovedWhenDone() {
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        fun shown() = nm.activeNotifications.firstOrNull { it.id == 8 }

        // Not in the car (e.g. the test button at home): nothing on a car screen.
        Briefing.start(ctx, "Welcome.", "Parked test")
        SystemClock.sleep(500)
        assertTrue(shown() == null)

        prefs.autoStart = false
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        onMain { Briefing.start(ctx, "Good morning Mr. Smith.", "It's 4 degrees.") }
        waitFor("the briefing pop-up", 5_000) { shown() != null }
        val car = androidx.car.app.notification.CarAppExtender(shown()!!.notification)
        assertEquals("Good morning Mr. Smith.", car.contentTitle.toString())
        assertEquals(androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH, car.importance)

        onMain { Briefing.end(ctx) }
        waitFor("the briefing pop-up to be removed", 5_000) { shown() == null }
        assertTrue(Briefing.text == null)
    }

    @Test
    fun parkedSpotIsDeletedWhenTheCarDrivesAgain() {
        prefs.parkedSpot = Parking.Spot(52.09, 5.12, 8f, System.currentTimeMillis()).encode()
        assertTrue(Parking.spot(ctx) != null)
        connectCar()
        assertEquals("", prefs.parkedSpot)
        assertTrue(log().contains("old spot removed"))
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
