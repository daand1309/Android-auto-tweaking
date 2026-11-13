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
            greetingStyle = "sassy"
            obdEnabled = false
        }
        com.daand.drivemusic.obd.ObdManager.setLiveForTest(null)
        // Old tests expect "left the car" right away; the stall test sets its own hold.
        DriveHold.holdWithoutObdMs = 0L

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
    fun everyPageOpensWithoutCrashingIncludingTheVoicePage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var pages = emptyList<String>()
            sc.onActivity { pages = it.pageNamesForTest() }
            for (p in pages) {
                sc.onActivity { it.openPageForTest(p) }
                SystemClock.sleep(if (p == "VOICE") 4_000 else 300)  // voice page loads the speech engine
                sc.onActivity { a -> assertTrue("page $p", !a.isFinishing) }
            }
            // Butler style on and off again, with the voice page open.
            prefs.greetingStyle = "butler"
            sc.onActivity { it.openPageForTest("VOICE") }
            SystemClock.sleep(1_000)
            sc.onActivity { it.openPageForTest("HOME") }
            prefs.greetingStyle = "sassy"
        }
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
                val buttons = mutableListOf<android.view.View>()
                fun walk(v: android.view.View) {
                    if (v is android.widget.Button || v is android.widget.ImageButton) buttons += v
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
        assertEquals(0, Briefing.start(ctx, "Welcome.", "Parked test"))
        SystemClock.sleep(500)
        assertTrue(shown() == null)

        prefs.autoStart = false
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        var t = 0
        onMain { t = Briefing.start(ctx, "Good morning Mr. Smith.", "It's 4 degrees.") }
        waitFor("the briefing pop-up", 5_000) { shown() != null }
        val car = androidx.car.app.notification.CarAppExtender(shown()!!.notification)
        assertEquals("Good morning Mr. Smith.", car.contentTitle.toString())
        assertEquals(androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH, car.importance)

        onMain { Briefing.end(ctx, t - 1) }  // an older greeting finishing must NOT remove this one
        SystemClock.sleep(500)
        assertTrue("newer briefing must stay", shown() != null)
        onMain { Briefing.end(ctx, t) }
        waitFor("the briefing pop-up to be removed", 5_000) { shown() == null }
        assertTrue(Briefing.text == null)
    }

    @Test
    fun driveModeShowsBigRedAndGreenButtonsForAnUpdate() {
        prefs.showSplash = false
        ctx.startActivity(android.content.Intent(ctx, DriveModeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Drive Mode to open", 10_000) { driveModeResumed() }
        var answer: Boolean? = null
        onMain {
            UpdatePrompt.open(ctx, "News", "I do apologise, sir, but there is an update. Shall I go ahead?")
            UpdatePrompt.awaitAnswer(ctx) { answer = it }
        }
        var red: android.widget.Button? = null
        waitFor("the red button", 5_000) {
            onMain {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<DriveModeActivity>()
                    .firstOrNull()?.let { a ->
                        fun find(v: android.view.View) {
                            if (v is android.widget.Button && v.contentDescription == "Not interested" && v.width > 0 && v.height > 0) red = v
                            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
                        }
                        find(a.window.decorView)
                    }
            }
            red != null
        }
        onMain { red!!.performClick() }
        waitFor("the answer", 3_000) { answer != null }
        assertEquals(false, answer)
        assertTrue(UpdatePrompt.question == null)
        closeDriveMode()
    }

    @Test
    fun carMediaAppShowsTheMenuControlsMusicAndDoesNotBlockAutoResume() {
        prefs.autoResume = true
        var browser: android.media.browse.MediaBrowser? = null
        var children: List<String> = emptyList()
        onMain {
            browser = android.media.browse.MediaBrowser(ctx,
                ComponentName(ctx, com.daand.drivemusic.car.DriveMediaService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        browser!!.subscribe(browser!!.root, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                            override fun onChildrenLoaded(parentId: String, items: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                                children = items.map { it.mediaId!! }
                            }
                        })
                    }
                }, null)
            browser!!.connect()
        }
        try {
            waitFor("the car media menu", 10_000) { children.isNotEmpty() }
            assertTrue(children.toString(), children.containsAll(listOf(com.daand.drivemusic.car.MediaMenu.WELCOME, com.daand.drivemusic.car.MediaMenu.RESUME)))

            // Menu item toggles auto-resume (and back).
            val controller = android.media.session.MediaController(ctx, browser!!.sessionToken)
            onMain { controller.transportControls.playFromMediaId(com.daand.drivemusic.car.MediaMenu.RESUME, null) }
            waitFor("auto-resume off", 5_000) { !prefs.autoResume }
            onMain { controller.transportControls.playFromMediaId(com.daand.drivemusic.car.MediaMenu.RESUME, null) }
            waitFor("auto-resume on", 5_000) { prefs.autoResume }

            // Drive with the media app open: its mirror must NOT count as "another app playing".
            connectCar()
            SystemClock.sleep(3_500)  // let the mirror pick up "playing"
            val playsBefore = playCalls
            onMain { setState(PlaybackState.STATE_PAUSED) }
            waitFor("auto-resume with the car media app open", 15_000) { currentState == PlaybackState.STATE_PLAYING && playCalls > playsBefore }
            assertTrue(!log().contains("another app is playing (${ctx.packageName})"))

            // Its pause button reaches the music - and it STAYS paused (no auto-resume over your own pause).
            onMain { controller.transportControls.pause() }
            waitFor("pause from the car media app", 5_000) { currentState == PlaybackState.STATE_PAUSED }
            SystemClock.sleep(6_000)  // longer than the 2 s resume delay used in tests
            assertEquals("auto-resume must respect a pause from the car", PlaybackState.STATE_PAUSED, currentState)
            assertTrue(log().contains("Car media: pause (you asked - won't auto-resume)"))
        } finally {
            onMain { browser?.disconnect() }
        }
    }

    @Test
    fun stallAndRestartRebootingTheRadioKeepsTheSameDrive() {
        DriveHold.holdWithoutObdMs = 60_000L
        prefs.rememberParking = false
        connectCar()
        val newDrives = { log().split("=== CAR: Android Auto connected ===").size - 1 }
        val drivesBefore = newDrives()

        // Stall + restart: the starter's voltage dip reboots the head unit, Android Auto drops.
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        waitFor("music paused on the drop", 5_000) { currentState == PlaybackState.STATE_PAUSED }
        assertEquals(DriveHold.STATE, prefs.lastCarState)
        assertTrue(log().contains("holding the drive"))

        // The radio is back ~20 s later (shortened here): same drive, no new greeting, music back on.
        SystemClock.sleep(2_000)
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_PROJECTION) }
        waitFor("music back after the radio rebooted", 10_000) { currentState == PlaybackState.STATE_PLAYING }
        assertTrue(log().contains("same drive continues"))
        assertEquals("no new drive started", drivesBefore, newDrives())
        assertTrue(!log().contains("drive ended"))
        assertEquals("Android Auto connected", prefs.lastCarState)

        // Really leaving: the drive ends once the hold runs out.
        DriveHold.holdWithoutObdMs = 0L
        onMain { service().simulateCarStateForTest(CarConnection.CONNECTION_TYPE_NOT_CONNECTED) }
        waitFor("drive to end", 10_000) { log().contains("drive ended") }
        assertEquals("not connected", prefs.lastCarState)
    }

    @Test
    fun driveModeTabsSwipeBetweenMusicAndCar() {
        prefs.showSplash = false
        ctx.startActivity(android.content.Intent(ctx, DriveModeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Drive Mode to open", 10_000) { driveModeResumed() }
        var activity: DriveModeActivity? = null
        onMain {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<DriveModeActivity>().first()
        }
        val a = activity!!
        assertEquals(0, a.currentTab())
        val instr = InstrumentationRegistry.getInstrumentation()

        /** A real finger swipe across the screen, starting at (startX, y). */
        fun swipe(fromX: Float, toX: Float, y: Float) {
            val t0 = SystemClock.uptimeMillis()
            instr.sendPointerSync(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, fromX, y, 0))
            for (i in 1..10) {
                val x = fromX + (toX - fromX) * i / 10
                instr.sendPointerSync(android.view.MotionEvent.obtain(t0, t0 + i * 15L, android.view.MotionEvent.ACTION_MOVE, x, y, 0))
            }
            instr.sendPointerSync(android.view.MotionEvent.obtain(t0, t0 + 180, android.view.MotionEvent.ACTION_UP, toX, y, 0))
            instr.waitForIdleSync()
            SystemClock.sleep(400)
        }
        val pager = a.pagerForTest()
        val loc = IntArray(2)
        onMain { pager.getLocationOnScreen(loc) }
        val w = pager.width.toFloat()
        val midY = loc[1] + pager.height * 0.5f

        // Swipe left -> car tab.
        swipe(loc[0] + w * 0.85f, loc[0] + w * 0.1f, midY)
        assertEquals("swipe left opens the car tab", 1, a.currentTab())
        // Swipe right -> back to music.
        swipe(loc[0] + w * 0.1f, loc[0] + w * 0.85f, midY)
        assertEquals(0, a.currentTab())

        // A swipe that STARTS on a big button still switches tabs (and doesn't press it).
        var play: android.view.View? = null
        onMain {
            fun find(v: android.view.View) {
                if (v is android.widget.ImageButton && (v.contentDescription == "Play" || v.contentDescription == "Pause") && v.isShown) play = v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
            }
            find(a.window.decorView)
        }
        val b = IntArray(2)
        onMain { play!!.getLocationOnScreen(b) }
        val playCallsBefore = playCalls
        swipe(b[0] + play!!.width * 0.9f, b[0] - w * 0.6f, b[1] + play!!.height / 2f)
        assertEquals("swipe from a button switches tabs", 1, a.currentTab())
        assertEquals("...without pressing the button", playCallsBefore, playCalls)

        // Tapping a tab name switches too; the car tab shows its guidance without OBD.
        onMain { pager.goTo(0) }
        SystemClock.sleep(400)
        assertEquals(0, a.currentTab())
        onMain { pager.goTo(1) }
        SystemClock.sleep(1_500)
        var guidance = ""
        onMain {
            fun find(v: android.view.View) {
                if (v is android.widget.TextView && v.text.contains("Car data (OBD)")) guidance = v.text.toString()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
            }
            find(a.window.decorView)
        }
        assertTrue("car tab explains how to get data: '$guidance'", guidance.isNotEmpty())

        // With (long) live values, every number must fit its tile on this screen - nothing cut off.
        prefs.obdEnabled = true
        val trip = com.daand.drivemusic.obd.Trip()
        for (i in 0..200) trip.add(com.daand.drivemusic.obd.CarSnapshot(i * 5_000L, rpm = 2500, speedKmh = 88, litresPerHour = 5.6))
        com.daand.drivemusic.obd.ObdManager.setLiveForTest(com.daand.drivemusic.obd.CarSnapshot(System.currentTimeMillis(),
            coolantC = 104, rpm = 6750, speedKmh = 188, volts = 14.35, ambientC = -12, litresPerHour = 12.4,
            codes = listOf("P0420", "P0171"), checkEngine = true, extra = mapOf(0x11 to 100.0, 0x04 to 100.0, 0x0F to -8.0)), trip)
        SystemClock.sleep(3_500)
        val problems = mutableListOf<String>()
        var tiles = 0
        onMain {
            fun check(v: android.view.View) {
                if (v is android.widget.TextView && v.isShown && v.maxLines == 1 && v.text.isNotEmpty() && v.text != "—" && v.text.length >= 2 &&
                    v.autoSizeTextType == android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) {
                    tiles++
                    val fits = v.paint.measureText(v.text.toString()) <= v.width - v.paddingLeft - v.paddingRight + 1
                    val layout = v.layout
                    if (!fits || (layout != null && layout.getEllipsisCount(0) > 0)) problems += "'${v.text}' (${v.width}px)"
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) check(v.getChildAt(i))
            }
            check(a.window.decorView)
        }
        assertTrue("expected the live tiles to show values, saw $tiles", tiles >= 6)
        assertTrue("values cut off: $problems", problems.isEmpty())
        com.daand.drivemusic.obd.ObdManager.setLiveForTest(null)
        prefs.obdEnabled = false
        closeDriveMode()
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
