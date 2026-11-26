package com.daand.drivemusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

/**
 * Runs in the background as long as "Notification access" is granted.
 *
 * - Detects Android Auto connect/disconnect.
 * - On connect: opens YouTube Music and presses play, retrying until it is actually playing.
 * - While driving: watches YouTube Music's playback state, logs every pause together with
 *   what else was happening (navigation prompt, Assistant, call, network loss, Bluetooth),
 *   and optionally resumes playback automatically.
 */
class DriveMonitorService : NotificationListenerService() {

    companion object {
        // AudioManager's newer call modes, by value (named constants need a newer compile SDK for some).
        private const val MODE_CALL_SCREENING = 4
        private const val MODE_CALL_REDIRECT = 5
        private const val MODE_COMMUNICATION_REDIRECT = 6
        /** After Android Auto comes back, a pause this soon is it settling, not a real pause. */
        private const val SETTLE_MS = 15_000L
        /** A stop this soon after the app started the music is a start bounce, not a real pause (tests set 0). */
        @Volatile var startBounceMs = 6_000L
        /** See StartRules.DELIBERATE_AFTER_MS; the emulator tests pause on purpose within seconds. */
        @androidx.annotation.VisibleForTesting @Volatile var deliberateAfterMs = StartRules.DELIBERATE_AFTER_MS
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        /** Set while the listener is connected, so the UI can trigger a manual test. */
        @Volatile var instance: DriveMonitorService? = null
            private set

        const val DRIVE_MODE_NOTIFICATION = 7
        private const val DRIVE_MODE_CHANNEL = "drive_mode_silent"

        /**
         * When the car connects while the phone is in use, Android shows a banner instead of letting the
         * app take the screen. The screen going off lifts that, so Drive Mode gets one more try - but only
         * shortly after the car connected, so it doesn't leap up mid-drive.
         */
        private const val DRIVE_MODE_RETRIES = 3
        /** Two full-screen requests closer together than this are one intent, not two. */
        private const val DRIVE_MODE_MIN_GAP_MS = 3_000L
        private const val DRIVE_MODE_RETRY_WINDOW_MS = 5 * 60_000L
        private const val START_TICK_MS = 1500L
        private const val START_MAX_TICKS = 15
        private const val RESUME_WINDOW_MS = 10 * 60 * 1000L
        private const val RESUME_MAX_IN_WINDOW = 4

        /** Wait this long before calling a stop a "pause": patched builds briefly flip to ERROR
         *  (e.g. a "Premium" blip) and recover on their own within about a second. */
        private const val PAUSE_GRACE_MS = 3000L

        /** How often the ignition is checked while driving (the car's computer going quiet = switched off). */
        private const val IGNITION_CHECK_MS = 15_000L

        /** Switched off this long with Android Auto still up: the drive is over. Short stops (a queue,
         *  a level crossing, waiting to pick someone up) stay one drive. */
        private const val IGNITION_END_DRIVE_MS = 10 * 60_000L

        /** A stop into ERROR can be the same "Premium" blip recovering slower than a buffer hiccup
         *  (a few seconds, not one): wait longer before calling an ERROR stop a real pause. A user
         *  pause shows up as PAUSED, which still uses the short grace. */
        private const val ERROR_PAUSE_GRACE_MS = 12_000L

        /** Audio that never pauses music: the music itself, touch/keyboard clicks, and navigation
         *  voice (it only ducks the music, it never stops it - and it fires constantly). */
        private val NOT_INTERRUPTIONS = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        )

        /** Audio worth noting in the log (it can actually interrupt music). Navigation is excluded. */
        private val NOTABLE_AUDIO = setOf(
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ALARM,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var sessions: MediaSessionManager
    private lateinit var connectivity: ConnectivityManager
    private lateinit var listenerComponent: ComponentName

    private var carConnection: CarConnection? = null
    private var carConnected = false
    private var carConnectedAt = 0L
    /** When the app last got the music playing, and whether a stop right after was already retried. */
    private var lastStartedAt = 0L
    private var bounceRetried = false   // (kept for the bookkeeping below; StartRules decides)
    /** When the music first started this drive, and how many quick restarts it has had (StartRules). */
    private var firstStartAt = 0L
    private var quickRestarts = 0
    private var bounceRetryPending = false
    /** When Android Auto came back after a drop (stall/restart); 0 = not recently. */
    private var reconnectedAt = 0L

    /** Re-read each time so changing the app in settings takes effect immediately. */
    private var cachedMusicPkg = ""
    private var cachedMusicPkgAt = 0L
    /** The music app to control. Cached for 10s - it's read on every playback event. */
    private val musicPkg: String
        get() {
            val now = SystemClock.elapsedRealtime()
            if (cachedMusicPkg.isEmpty() || now - cachedMusicPkgAt > 10_000) {
                cachedMusicPkg = prefs.musicPackage
                cachedMusicPkgAt = now
            }
            return cachedMusicPkg
        }

    private var ytm: MediaController? = null
    private var lastYtmState = -1

    // Diagnostics for the current drive
    private var pausesThisDrive = 0
    private var resumesThisDrive = 0
    private var lastNetworkLostAt = 0L
    private var lastBtDisconnectAt = 0L
    private var lastNotableAudio = ""
    private var lastInterruption = ""
    private var lastInterruptionAt = 0L
    private var pendingPauseRunnable: Runnable? = null
    private var briefInterruptions = 0
    /** Set when you pause from the car screen, so auto-resume doesn't fight you. */
    private var userPausedViaUi = false
    private var lastPauseClue = ""
    /** When the last pause with no clues was counted (0: none pending) - see [StartRules.selfRecovered]. */
    private var unexplainedPauseAt = 0L
    private var lastAutoResumeAt = 0L
    /** Set when you pause again right after an auto-resume: we take the hint for the rest of the drive. */
    /** This drive's own auto-resume choice (a button in the car, or the app when your pauses look
     *  deliberate): on, off, or null = follow the setting. Cleared when a drive starts. */
    var resumeThisDrive: Boolean? = null
    var resumeOffThisDrive: Boolean
        get() = resumeThisDrive == false
        set(v) { resumeThisDrive = if (v) false else null }
    /** Will a pause be resumed right now? This drive's choice first, else the setting. */
    private val autoResumeNow get() = ResumeToggle.isOn(prefs.autoResume, resumeThisDrive)
    /** In the car right now (connected, or holding the drive through an Android Auto restart). */
    val inDrive get() = carConnected || holdingSince > 0
    private var firstCarEvent = true
    /** Announce "Now playing ..." once music starts (when the song wasn't known before the greeting). */
    private var announceSongAfterStart = false
    /** Our own speech can briefly pause the music; pauses before this time are attributed to it. */
    private var suppressPauseUntil = 0L  // ignore stops we/the greeting cause until this time
    private val resumeTimes = ArrayDeque<Long>()

    /** Bumped on every connect/disconnect so delayed greeting/start tasks from an old connection are ignored. */
    private var connectGen = 0
    private var startRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onListenerConnected() {
        super.onListenerConnected()
        DriveLog.init(this)
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        sessions = getSystemService(MediaSessionManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
        listenerComponent = ComponentName(this, DriveMonitorService::class.java)
        instance = this
        // The radio's song title is only fetched while a screen shows it (it costs a second connection).
        RadioPlayer.titleVisible = { DriveModeActivity.onScreen || com.daand.drivemusic.car.DriveMediaService.ownToken != null }

        DriveLog.log("Monitor started, controlling ${prefs.musicPackage} (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})")

        carConnection = CarConnection(this).also { it.type.observeForever(carObserver) }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            onSessionsChanged(sessions.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            DriveLog.log("ERROR: no access to media sessions: ${e.message}")
        }
        audio.registerAudioPlaybackCallback(playbackConfigCallback, handler)
        connectivity.registerDefaultNetworkCallback(networkCallback, handler)
        handler.postDelayed(updateCheck, 60_000)
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            // Not exported: only the system can deliver these, other apps can't send fake events.
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onListenerDisconnected() {
        DriveLog.log("Monitor stopped by the system - asking Android to restart it")
        cleanup()
        requestRebind(ComponentName(this, DriveMonitorService::class.java))
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        runCatching { musicBrowser?.disconnect() }
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance !== this) return
        // Stopped while a drive was on hold: wrap it up properly (else OBD keeps running and the app
        // keeps saying "reconnecting").
        if (holdingSince > 0) endDrive("monitor stopped")
        instance = null
        // The adapter belongs to the monitor. A rebind re-arms it with the new instance (ObdManager.start),
        // but a monitor that is really going away must not leave a polling thread behind - it would keep
        // waking the adapter with nobody listening.
        com.daand.drivemusic.obd.ObdManager.onLinked = null
        com.daand.drivemusic.obd.ObdManager.stop()
        cancelStart()
        cancelResume()
        cancelPendingPause()
        handler.removeCallbacksAndMessages(null)  // includes updateCheck and any delayed greeting/start
        carConnection?.type?.removeObserver(carObserver)
        carConnection = null
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackConfigCallback) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        detachYtm()
    }

    // Notification access is only used to get permission to control media sessions.
    // We deliberately never read, store or log the content of any notification.
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}

    /** Daily update check (the Updater skips it if checked recently, disabled, or driving). */
    private val updateCheck = object : Runnable {
        override fun run() {
            Updater.maybeCheckInBackground(this@DriveMonitorService, driving = carConnected)
            handler.postDelayed(this, 3 * 60 * 60 * 1000L)
        }
    }

    // ---------------------------------------------------------------- Android Auto

    private val carObserver = Observer<Int> { type -> onCarState(type) }

    /** Test hook: feeds a car connection state through exactly the same path as Android Auto does. */
    @androidx.annotation.VisibleForTesting
    fun simulateCarStateForTest(type: Int) {
        // A simulated monitor restart arms the flag; a normal simulated event is never a first event.
        firstCarEvent = simulatedRestart
        simulatedRestart = false
        onCarState(type)
    }

    /**
     * Test hook: the ignition check decided the car is off (Android Auto still connected). Same path the
     * check takes, so the emulator can prove what happens to the radio.
     */
    @androidx.annotation.VisibleForTesting
    fun simulateIgnitionOffForTest() = ignitionOff(IGNITION_END_DRIVE_MS)

    /** Test hook: pretend the monitor just (re)started, so the next car state is its first event. */
    @androidx.annotation.VisibleForTesting
    fun simulateMonitorRestartForTest() {
        simulatedRestart = true
    }

    /** Set by [simulateMonitorRestartForTest], consumed by the next [simulateCarStateForTest]. */
    private var simulatedRestart = false

    private fun onCarState(type: Int) {
        val name = when (type) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> "Android Auto connected"
            CarConnection.CONNECTION_TYPE_NATIVE -> "Android Automotive"
            else -> "not connected"
        }
        // A fresh instance that hears "connected" as its very first event didn't see the car
        // connect: Android just re-delivered the current state on rebind (mid-drive).
        prefs.lastCarState = name
        val nowConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        val wasFirstEvent = firstCarEvent
        firstCarEvent = false
        val startedMidDrive = wasFirstEvent && nowConnected
        if (nowConnected == carConnected) return
        carConnected = nowConnected
        com.daand.drivemusic.obd.ObdManager.carScreenLostAt = if (nowConnected) 0L else System.currentTimeMillis()
        val gen = ++connectGen

        if (nowConnected && holdingSince > 0) {
            resumeHeldDrive(name)
            return
        }
        if (nowConnected) {
            carConnectedAt = SystemClock.elapsedRealtime()
            driveEnded = false
            driveModeShown = false
            driveModeRetries = 0
            carOffLogged = false
            pausesThisDrive = 0
            resumesThisDrive = 0
            briefInterruptions = 0
            premiumHintGiven = false; premiumResetTried = false; selfRecoveredAt = 0L; stopVoice = null
            lastNotableAudio = ""
            cancelPendingPause()
            resumeTimes.clear()
            resumeThisDrive = null
            firstStartAt = 0L; quickRestarts = 0
            lastAutoResumeAt = 0L
            lastPauseClue = ""
            unexplainedPauseAt = 0L
            userPausedViaUi = false
            announceSongAfterStart = false
            musicStartedThisDrive = false
            DriveLog.log("=== CAR: $name ===")
            logEnvironment()
            Parking.clear(this)  // you're driving: the old parked spot is no longer where the car is
            DriveUpdates.newDrive()
            driveDataStart = appBytes()
            com.daand.drivemusic.obd.AdapterReminder.clear(this)   // driving again: last time's reminder is out of date
            startObd()
            handler.removeCallbacks(driveUpdate)
            handler.postDelayed(driveUpdate, DriveUpdates.FIRST_CHECK_MS)
            handler.removeCallbacks(ignitionCheck)
            handler.postDelayed(ignitionCheck, IGNITION_CHECK_MS)
            if (prefs.autoOpenDriveMode) {
                if (startedMidDrive) {
                    // Mid-drive service restart: the connection never ended, so Drive Mode was
                    // already offered when it did. Don't pop it over the user again.
                    DriveLog.log("Drive Mode: monitor started mid-drive - not opening it again")
                    driveModeOffered = true
                } else offerDriveMode("Android Auto connected")
            }
            if (wasFirstEvent && findYtm() != null) {
                // The monitor was (re)started by Android in the middle of a drive. Don't touch playback.
                DriveLog.log("Monitor started mid-drive - not changing what's playing")
                return
            }
            applyConnectVolume()
            val other = otherAppPlaying()
            val startAction: () -> Unit = start@{
                if (gen != connectGen) return@start  // stale: the car disconnected/reconnected since
                // A greeting cut short at the end of the drive still continues (it has to release what
                // it holds): this is the line that stops it starting the music.
                if (driveEnded) {
                    DriveLog.log("START: not starting - the drive is over")
                    return@start
                }
                when {
                    !prefs.autoStart -> DriveLog.log("Auto-start is turned off in settings")
                    other != null -> DriveLog.log("START: skipped - $other is already playing, not taking over")
                    prefs.radioWasOn && prefs.radioResume -> {
                        // The radio was on when you parked: that again, not YouTube Music.
                        prefs.radioWasOn = false
                        DriveLog.log("START: the radio was on when you parked - starting it again")
                        RadioPlayer.play(this, RadioPlayer.lastStation(this))
                    }
                    else -> startMusic("Android Auto connected")
                }
            }
            val recentlyGreeted = System.currentTimeMillis() - prefs.lastGreetingAt < 10 * 60 * 1000L
            when {
                !prefs.greetingEnabled -> handler.postDelayed(startAction, prefs.startDelaySec * 1000L)
                recentlyGreeted -> {
                    DriveLog.log("GREETING: skipped (already greeted in the last 10 min)")
                    handler.postDelayed(startAction, prefs.startDelaySec * 1000L)
                }
                else -> handler.postDelayed({
                    // Greeting first, then the music (startMusic checks we're still in the car).
                    if (carConnected && gen == connectGen) {
                        // Radio coming back: no "up first: <YouTube Music song>".
                        val song = if (prefs.radioWasOn && prefs.radioResume) null else currentSong()
                        announceSongAfterStart = song == null && prefs.announceSong && prefs.autoStart && other == null
                        if (song != null) DriveLog.log("Queued song: ${song.title} - ${song.artist}")
                        // If Android Auto auto-started music, pause it so the welcome is heard clearly,
                        // and don't count that stop as a pause.
                        suppressPauseUntil = SystemClock.elapsedRealtime() + 90_000L
                        val c = ytm?.takeIf { isMusic(it) } ?: findYtm()
                        val pausedForWelcome = c?.playbackState?.state == PlaybackState.STATE_PLAYING
                        if (pausedForWelcome) {
                            DriveLog.log("GREETING: music already playing (Android Auto auto-start?) - pausing it for the welcome")
                            c?.transportControls?.pause()
                        }
                        WelcomeAnnouncer.run(this, song) {
                            suppressPauseUntil = 0L
                            if (!carConnected || gen != connectGen) return@run
                            if (pausedForWelcome && (!prefs.autoStart || other != null)) {
                                // Auto-start won't run, so put back the music we paused for the welcome.
                                musicController()?.transportControls?.play()
                                DriveLog.log("GREETING: done - resumed the music we paused")
                            } else startAction()
                        }
                    }
                }, prefs.startDelaySec * 1000L)
            }
        } else {
            // Android Auto dropped. Stop what needs the car right now, but don't end the drive yet:
            // a stall + restart reboots the head unit (voltage dip) and Android Auto comes back.
            Briefing.endNow(this)
            UpdatePrompt.cancel(this)
            handler.removeCallbacks(driveUpdate)
            handler.removeCallbacks(ignitionCheck)
            cancelStart()
            cancelResume()
            cancelPendingPause()
            announceSongAfterStart = false
            suppressPauseUntil = 0L
            getSystemService(NotificationManager::class.java).cancel(DRIVE_MODE_NOTIFICATION)
            pausedAtDrop = false
            // The radio is a car thing: off when you leave, whatever the YouTube Music setting says.
            // Android Auto dropping takes the sound a moment before we hear about the drop: count that as on.
            prefs.radioWasOn = RadioPlayer.onOrJustLost()
            RadioPlayer.stop("you left the car (or Android Auto dropped)")
            // The greeting talks to whoever is in the car, and nobody is. Its continuation still runs -
            // that is where WelcomeAnnouncer releases its busy flag - but it cannot start music:
            // carConnected is false and connectGen moved on.
            Voice.stop("the car disconnected")
            // ...and if it had not started yet, it never will: the welcome prepares its text first.
            WelcomeAnnouncer.cancel("the car disconnected")
            if (prefs.pauseOnDisconnect) {
                val c = findYtm()
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    c.transportControls.pause()
                    pausedAtDrop = true
                    DriveLog.log("Paused music because you left the car (or Android Auto dropped)")
                }
            }
            if (driveEnded) {
                // Already wrapped up (the car was switched off while Android Auto stayed up): there is
                // no drive left to hold, so don't start a reconnect watch that would only end it again.
                holdingSince = 0L
                prefs.lastCarState = "not connected"
                handler.removeCallbacks(holdCheck)
                DriveLog.log("CAR: Android Auto dropped after the drive had already ended")
                return
            }
            holdingSince = SystemClock.elapsedRealtime()
            Parking.rememberDropSpot(this)  // where the car is NOW (by the time the hold ends you may have walked off)
            prefs.lastCarState = DriveHold.STATE
            DriveLog.log("CAR: Android Auto dropped - holding the drive in case it's a stall/restart " +
                if (com.daand.drivemusic.obd.ObdManager.isConnected()) "(OBD will tell if the ignition is off)" else "(no OBD: waiting ${DriveHold.holdWithoutObdMs / 1000}s)")
            handler.removeCallbacks(holdCheck)
            handler.post(holdCheck)
        }
    }

    // ---------------------------------------------------------------- stall / head-unit reboot safeguard

    /** >0 while Android Auto is gone but the drive is kept (stall + restart reboots the radio). */
    private var holdingSince = 0L
    /** We paused the music when Android Auto dropped - play it again if the drive continues. */
    private var pausedAtDrop = false
    /** The music has played at some point this drive. */
    private var musicStartedThisDrive = false
    /** The drive is wrapped up (so a late drop / cleanup doesn't wrap it up a second time). */
    private var driveEnded = false

    /**
     * Drive Mode has been brought up for this drive already. The car reaches us twice - the adapter
     * powers on with the ignition, Android Auto announces itself a moment later - and it must open
     * once, not twice.
     */
    private var driveModeOffered = false
    /** When the last full-screen request was posted (see [DRIVE_MODE_MIN_GAP_MS]). */
    private var lastDriveModePostAt = 0L
    /**
     * Times we tried to put Drive Mode on screen while the phone was in use (where Android only shows a
     * banner). Once the screen goes off we get one more try, which is when it really can take over.
     */
    private var driveModeRetries = 0
    private var driveModeOfferedAt = 0L
    /** Drive Mode has actually been on screen this drive (so it is never forced up twice). */
    private var driveModeShown = false

    /** Called by [DriveModeActivity] when it comes to the front: you've seen it, so stop pushing it. */
    fun noteDriveModeOnScreen() { driveModeShown = true }

    /**
     * The car can be switched off while Android Auto stays up (head unit on accessory power, radio
     * still on, door not opened yet). Then nothing else tells us the car is off, so the adapter's view
     * of the ignition is watched: the adapter still answers, its car doesn't.
     */
    private var carOffLogged = false
    private val ignitionCheck = object : Runnable {
        override fun run() {
            if (!carConnected || holdingSince > 0 || driveEnded) return
            val obd = com.daand.drivemusic.obd.ObdManager
            val off = obd.carOffForMs()
            if (off == 0L) {
                // Back on (or never off): the drive simply continues.
                if (carOffLogged) {
                    // A spot remembered while it stood still is not where the car is now: otherwise a
                    // short stop would be saved as the parking place at the end of the drive.
                    carOffLogged = false
                    Parking.forgetDropSpot()
                }
            } else {
                if (!carOffLogged) {
                    carOffLogged = true
                    DriveLog.log("=== CAR: switched off (the car's computer has been quiet for ${off / 1000}s) ===")
                    // Save where the car is standing now: by the time the drive is wrapped up you may
                    // have walked off, and a fresh fix then would be the wrong spot.
                    Parking.rememberDropSpot(this@DriveMonitorService)
                }
                if (off >= IGNITION_END_DRIVE_MS) { ignitionOff(off); return }
            }
            handler.postDelayed(this, IGNITION_CHECK_MS)
        }
    }

    /** Switched off long enough that the drive is over, even though Android Auto never dropped. */
    private fun ignitionOff(silentMs: Long) {
        DriveLog.log("=== CAR: quiet for ${silentMs / 60_000} min - wrapping up the drive (Android Auto stayed connected) ===")
        if (prefs.pauseOnDisconnect) {
            val c = findYtm()
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
                DriveLog.log("Paused music because the car was switched off")
            }
        }
        Briefing.endNow(this)
        UpdatePrompt.cancel(this)
        handler.removeCallbacks(driveUpdate)
        cancelStart()
        cancelResume()
        cancelPendingPause()
        announceSongAfterStart = false
        suppressPauseUntil = 0L
        getSystemService(NotificationManager::class.java).cancel(DRIVE_MODE_NOTIFICATION)
        carConnected = false   // out of the car: a new drive starts when Android Auto says so again
        connectGen++
        endDrive("ignition is off")
    }

    private val holdCheck = object : Runnable {
        override fun run() {
            if (holdingSince == 0L) return
            val since = SystemClock.elapsedRealtime() - holdingSince
            val obd = com.daand.drivemusic.obd.ObdManager
            when (val d = DriveHold.decide(since, obd.isConnected(), obd.ecuSilentMs(), obd.connectedThisDrive)) {
                is DriveHold.Decision.End -> endDrive(d.reason)
                DriveHold.Decision.Hold -> handler.postDelayed(this, 3_000)
            }
        }
    }

    /** Android Auto is back within the hold: same drive, nothing restarts. */
    private fun resumeHeldDrive(name: String) {
        val secs = (SystemClock.elapsedRealtime() - holdingSince) / 1000
        holdingSince = 0L
        handler.removeCallbacks(holdCheck)
        prefs.lastCarState = name
        DriveLog.log("=== CAR: Android Auto is back after ${secs}s - same drive continues (no new greeting) ===")
        reconnectedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(driveUpdate, DriveUpdates.INTERVAL_MS)
        // The ignition watcher stopped while the drive was on hold: it has to watch again now.
        handler.removeCallbacks(ignitionCheck)
        handler.postDelayed(ignitionCheck, IGNITION_CHECK_MS)
        when {
            pausedAtDrop -> {
                pausedAtDrop = false
                // Give Android Auto a moment to settle, then put the music back.
                handler.postDelayed({
                    if (carConnected) { musicController()?.transportControls?.play(); DriveLog.log("Music resumed after Android Auto came back") }
                }, 5_000)
            }
            // The radio was on when Android Auto dropped: the radio again, not YouTube Music.
            prefs.radioWasOn && prefs.radioResume -> handler.postDelayed({
                if (carConnected && !RadioPlayer.on) {
                    prefs.radioWasOn = false
                    DriveLog.log("START: the radio was on before Android Auto dropped - back on")
                    RadioPlayer.play(this, RadioPlayer.lastStation(this))
                }
            }, 3_000)
            // Stalled before the music had even started (e.g. during the greeting): start it now.
            !musicStartedThisDrive && prefs.autoStart && !userPausedViaUi && otherAppPlaying() == null ->
                handler.postDelayed({ if (carConnected) startMusic("Android Auto came back") }, 3_000)
        }
    }

    /** You really left (ignition off, or Android Auto stayed away): wrap up the drive. */
    private fun endDrive(reason: String) {
        val alreadyEnded = driveEnded
        driveEnded = true
        // Whatever is being said belongs to this drive. Stopping also runs the continuation (the
        // WelcomeAnnouncer/media-menu release); startAction refuses the music because driveEnded is
        // set. Idempotent, so the disconnect path and the skip button are not disturbed.
        Voice.stop("the drive ended")
        WelcomeAnnouncer.cancel("the drive ended")
        // The radio is a car thing too, and it is not only sound: its MediaPlayer holds a partial wake
        // lock, so a radio left running after the ignition goes off keeps playing into an empty car and
        // keeps the phone awake. Remember it was on, so the next drive can bring it back.
        if (RadioPlayer.onOrJustLost()) prefs.radioWasOn = true
        RadioPlayer.stop("the drive ended")
        DriveModeActivity.closeBecauseDriveEnded()   // even when it's in the background (it used to wait until you unlocked)
        resumeThisDrive = null   // this drive's auto-resume choice ends with it
        runCatching { musicBrowser?.disconnect() }; musicBrowser = null
        handler.removeCallbacks(ownSpeechCheck)   // nothing from this drive may fire into the next one
        driveModeOffered = false      // a new drive gets its own pop-up
        // The gap guard is about one drive's burst of requests, not about the clock: drive and park
        // again within three seconds and the next car would have been told "already on its way".
        lastDriveModePostAt = 0L
        driveModeRetries = 0
        driveModeShown = false
        reconnectedAt = 0L
        holdingSince = 0L
        pausedAtDrop = false
        handler.removeCallbacks(holdCheck)
        handler.removeCallbacks(ignitionCheck)
        prefs.lastCarState = "not connected"
        if (alreadyEnded) return   // wrapped up already: only the state above still needs fixing
        val mins = (SystemClock.elapsedRealtime() - carConnectedAt) / 60000
        val brief = if (briefInterruptions > 0) ", $briefInterruptions brief blip(s) ignored" else ""
        DriveLog.log("=== CAR: drive ended ($reason) after ${mins} min. Pauses: $pausesThisDrive, auto-resumes: $resumesThisDrive$brief ===")
        Rdw.checkIfDue(this)   // APK date / recalls, at most once a week (only the plate is sent)
        val tripLine = com.daand.drivemusic.obd.ObdManager.trip.summary(dutch = false).takeIf { it.isNotEmpty() }?.let { "\nCar: $it" } ?: ""
        prefs.lastDrive = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) +
            " - ${mins} min, $pausesThisDrive pause(s), $resumesThisDrive auto-resume(s)" +
            (if (lastPauseClue.isNotEmpty()) "\nLast pause: $lastPauseClue" else "") + tripLine
        Parking.captureAtDrop(this)
        if (driveDataStart >= 0) {
            val used = appBytes() - driveDataStart
            if (used >= 0) DriveLog.log("Data used by Drive Music this drive: ${used / 1024} KB")
            driveDataStart = -1
        }
        News.clearCache()  // free the memory until the next drive
        val adapterUsed = com.daand.drivemusic.obd.ObdManager.connectedThisDrive
        // For the battery log: when the car was parked, and (until you answer) the adapter state is unknown.
        prefs.lastDriveEndAt = System.currentTimeMillis()
        prefs.adapterWhileParked = com.daand.drivemusic.obd.BatteryLog.ADAPTER_UNKNOWN
        com.daand.drivemusic.obd.ObdManager.stop()
        if (com.daand.drivemusic.obd.AdapterReminder.shouldRemind(prefs.obdUnplugReminder, adapterUsed))
            com.daand.drivemusic.obd.AdapterReminder.post(this)
    }

    /** Mobile/Wi-Fi bytes this app sent + received (Android's own count), or -1 if unavailable. */
    private var driveDataStart = -1L
    private fun appBytes(): Long {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        return if (rx < 0 || tx < 0) -1 else rx + tx
    }

    // ---------------------------------------------------------------- car health (OBD)

    /**
     * Something about the car worth saying. Never over a call or the Assistant; urgent ones
     * (overheating, check-engine light) retry every 15 s, others every 2 min, for a while.
     */
    private fun onCarAlert(alert: com.daand.drivemusic.obd.CarAlerts.Alert, gen: Int, attempt: Int) {
        if (gen != connectGen) return
        if (!carConnected) {
            // Android Auto dropped out for a moment (restart / radio reboot): keep it until it's back.
            if (holdingSince > 0 && attempt < 40) handler.postDelayed({ onCarAlert(alert, gen, attempt + 1) }, 3_000L)
            else DriveLog.log("OBD: '${alert.kind}' not said - the drive ended")
            return
        }
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.ENGINE_WARM && !prefs.obdWarmup) return
        // Waited behind the greeting and you've started meanwhile: "start the engine" would be nonsense.
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.BATTERY_CHECK_READY &&
            com.daand.drivemusic.obd.ObdManager.live()?.engineRunning == true) { DriveLog.log("OBD: battery check prompt skipped - the engine is already running"); return }
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.LOW_FUEL && !prefs.obdFuel) return
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.READY_TO_DRIVE && prefs.obdReadySeconds == 0) return
        val blocker = resumeBlocker() ?: if (WelcomeAnnouncer.isBusy || UpdatePrompt.question != null) "something else is speaking" else null
        // "Ready to drive" waits for the greeting to finish (a few seconds), then says it - or, if the
        // moment has passed (~45 s of waiting), not at all.
        if (blocker != null) {
            // "Ready to drive" stays useful until you drive off - even after a long greeting (the butler's
            // can take ~50 s): say it right after, unless the car is already moving.
            val moving = (com.daand.drivemusic.obd.ObdManager.live()?.speedKmh ?: 0) > 5
            when {
                alert.timely && moving -> DriveLog.log("OBD: '${alert.kind}' skipped - you're already driving")
                alert.timely && attempt < 60 -> handler.postDelayed({ onCarAlert(alert, gen, attempt + 1) }, 3_000L)
                alert.timely -> DriveLog.log("OBD: '${alert.kind}' no longer useful - skipped")
                attempt < 20 -> handler.postDelayed({ onCarAlert(alert, gen, attempt + 1) }, if (alert.urgent) 15_000L else 120_000L)
            }
            return
        }
        val text = com.daand.drivemusic.obd.CarAlerts.message(alert, prefs.greetingDutch, prefs.butler)
        DriveLog.log("OBD: \"$text\"")
        ownSpeechStarts()
        suppressPauseUntil = SystemClock.elapsedRealtime() + 60_000L
        if (alert.urgent) {
            val token = Briefing.start(this, if (prefs.greetingDutch) "Auto" else "Car", text)
            safeSpeak(text) { Briefing.end(this, token); ownSpeechDone("car alert") }
        } else safeSpeak(text) { ownSpeechDone("car alert") }
    }

    // ---------------------------------------------------------------- updates during the drive

    /** Every so often during a drive: weather/road changes and breaking news (silent if nothing). */
    private val driveUpdate = object : Runnable {
        override fun run() {
            if (!carConnected) return
            val gen = connectGen
            if (prefs.updatesWeather || prefs.updatesNews || prefs.followActive) {
                Thread {
                    val found = try { DriveUpdates.check(this@DriveMonitorService) } catch (e: Throwable) {
                        DriveLog.log("UPDATE CHECK: failed (${e.javaClass.simpleName}: ${e.message})"); null
                    }
                    if (found != null) handler.post { deliverUpdate(found, gen, attempt = 0) }
                }.start()
            }
            handler.postDelayed(this, DriveUpdates.INTERVAL_MS)
        }
    }

    /**
     * Delivers an update over the (ducked) music - never over a call, the Assistant or the greeting.
     * Butler-polite: asks first ("I do apologise, sir, but there is an update"), with green / red
     * buttons; red = skip it, green or no answer = read it.
     */
    private fun deliverUpdate(found: DriveUpdates.Found, gen: Int, attempt: Int) {
        if (!carConnected || gen != connectGen) return
        val blocker = resumeBlocker() ?: if (WelcomeAnnouncer.isBusy || UpdatePrompt.question != null) "something else is speaking" else null
        if (blocker != null) {
            if (attempt < 5) {
                DriveLog.log("UPDATE: waiting - $blocker")
                handler.postDelayed({ deliverUpdate(found, gen, attempt + 1) }, 2 * 60 * 1000L)
            } else DriveLog.log("UPDATE: dropped - $blocker for too long")
            return
        }
        val dutch = prefs.greetingDutch
        val butler = prefs.butler
        ownSpeechStarts()
        suppressPauseUntil = SystemClock.elapsedRealtime() + 90_000L  // a brief duck/pause isn't "a pause"
        if (!prefs.askBeforeUpdates) {
            speakUpdate(DriveUpdates.compose(found.weather, found.news, dutch, butler, prefs.greetingName,
                followed = found.followed, source = found.source)!!, found.title(dutch))
            return
        }
        val question = DriveUpdates.askLine(dutch, butler, followedOnly = found.followed != null && found.news == null && found.weather.isEmpty())
        DriveLog.log("UPDATE: asking - \"$question\"")
        UpdatePrompt.open(this, found.title(dutch), question)
        safeSpeak(question) {
            UpdatePrompt.awaitAnswer(this) { yes ->
                if (!carConnected || gen != connectGen) return@awaitAnswer
                if (yes) speakUpdate(DriveUpdates.compose(found.weather, found.news, dutch, butler, prefs.greetingName, withIntro = false,
                    followed = found.followed, source = found.source)!!, found.title(dutch))
                else safeSpeak(DriveUpdates.declinedLine(dutch, butler)) { ownSpeechDone("update") }
            }
        }
    }

    /**
     * Something you asked for after the music started (the weather forecast): said over the music,
     * which dips while it talks - not counted as a pause, and put back if the music app stopped for it.
     * Waits its turn if the voice is busy (e.g. "Now playing").
     */
    fun sayOverMusic(text: String, title: String, what: String, waited: Int = 0) {
        if (!carConnected) return
        if (Voice.speaking && waited < 40) { handler.postDelayed({ sayOverMusic(text, title, what, waited + 1) }, 500); return }
        ownSpeechStarts()
        // Just after the start YouTube Music may be in one of its own brief stops: it was meant to be
        // playing, so put it back after the forecast all the same.
        if (firstStartAt > 0 && SystemClock.elapsedRealtime() - firstStartAt < StartRules.SETTLE_WINDOW_MS) playingBeforeOwnSpeech = true
        suppressPauseUntil = SystemClock.elapsedRealtime() + 90_000L
        val token = Briefing.start(this, title, text)
        safeSpeak(text) {
            Briefing.end(this, token)
            handler.post { ownSpeechDone(what) }
        }
    }

    private fun speakUpdate(text: String, title: String) {
        DriveLog.log("UPDATE: \"$text\"")
        val token = Briefing.start(this, title, text)
        safeSpeak(text) {
            Briefing.end(this, token)
            ownSpeechDone("update")
        }
    }

    /**
     * Our own speech is over. While it ran, stops were ignored (a duck or a short pause is ours, not an
     * interruption) - but the music app sometimes stays stopped afterwards (seen on real drives right
     * after "Now playing" and "Ready to drive"). So look once more: if the music the app started is
     * still not playing, treat that like any other stop (grace, then auto-resume).
     */
    /** The music was playing when we started talking - only then may [checkAfterOwnSpeech] put it back. */
    private var playingBeforeOwnSpeech = false
    private fun ownSpeechStarts() { playingBeforeOwnSpeech = isPlaying() }

    /** When our own voice last finished while the music had been playing (0 = not since): see [onConfirmedPause]. */
    private var ownSpeechEndedAt = 0L

    private fun ownSpeechDone(what: String) {
        suppressPauseUntil = 0L
        ownSpeechEndedAt = if (playingBeforeOwnSpeech) SystemClock.elapsedRealtime() else 0L
        postOwnSpeechCheck(what, 1_500L)
    }

    /**
     * One pending check at a time, and it can be cancelled: three speeches in a drive used to leave
     * three timers behind, and nothing could take them away when the drive ended.
     */
    @Volatile private var ownSpeechWhat = ""
    @Volatile private var ownSpeechGen = 0
    private val ownSpeechCheck = Runnable { checkAfterOwnSpeech(ownSpeechWhat, ownSpeechGen) }

    private fun postOwnSpeechCheck(what: String, delayMs: Long) {
        ownSpeechWhat = what
        ownSpeechGen = connectGen
        handler.removeCallbacks(ownSpeechCheck)
        handler.postDelayed(ownSpeechCheck, delayMs)
    }

    private fun checkAfterOwnSpeech(what: String, gen: Int) {
        if (!carConnected || gen != connectGen || !musicStartedThisDrive || userPausedViaUi) return
        // Paused (by you, or never playing) before we started talking: not ours to restart.
        if (!playingBeforeOwnSpeech) return
        if (SystemClock.elapsedRealtime() < suppressPauseUntil) return   // something else is speaking now
        if (pendingPauseRunnable != null || resumeRunnable != null || startRunnable != null) return
        val state = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.playbackState?.state ?: return
        if (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) {
            // It stopped while the app itself was talking - almost always the stop YouTube Music makes
            // ~1 s after every start (normally caught by the start bounce, but stops are ignored while
            // we speak). That is never you pausing: back on in 1.5 s, not counted. Real drive (11:17):
            // counted as "PAUSE #1, no clues" and 28 s of silence before it came back.
            DriveLog.log("Music stopped while we were speaking ($what) - putting it back on (not counted)")
            scheduleResume(1_500L)
        }
    }

    /** Speaks over ducked music; [then] always runs, even if speech fails. */
    private fun safeSpeak(text: String, then: () -> Unit) {
        try {
            Greeter(this).speak(text, prefs.greetingDutch, pauseOthers = false) { then() }
        } catch (e: Exception) {
            DriveLog.log("UPDATE: couldn't speak (${e.message})")
            then()
        }
    }

    // ---------------------------------------------------------------- start music

    /** Opens YouTube Music and presses play, retrying until it is really playing. */
    fun startMusic(reason: String, delayMs: Long = 0L, requireCar: Boolean = true) {
        cancelStart()
        // The radio is playing: only you (a play button) may switch back to YouTube Music.
        if (RadioPlayer.on && listOf("play", "voice", "manual").none { it in reason }) { DriveLog.log("START: skipped ($reason) - the radio is on"); return }
        DriveLog.log("START: trying to start YouTube Music ($reason)" + if (delayMs > 0) " in ${delayMs / 1000}s" else "")
        var tick = 0
        var playSentAtTick = -10
        val r = object : Runnable {
            override fun run() {
                if (requireCar && !carConnected) { startRunnable = null; return }
                val c = ytm?.takeIf { isMusic(it) } ?: findYtm()?.also { attachYtm(it) }
                val state = c?.playbackState?.state
                when {
                    state == PlaybackState.STATE_PLAYING -> {
                        DriveLog.log("START: success - YouTube Music is playing (after ~${tick * START_TICK_MS / 1000}s)")
                        startRunnable = null
                        onMusicStarted()
                        return
                    }
                    c != null && (state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING) &&
                        tick - playSentAtTick < 4 -> {
                        DriveLog.log("START: buffering... (waiting for network)")
                    }
                    c != null -> {
                        if (tick >= 6 && tick % 3 == 0) {
                            // Plain play() did nothing (empty queue?). Ask it to play "anything".
                            DriveLog.log("START: play() didn't work, sending playFromSearch(\"\") (state=${stateName(state)})")
                            c.transportControls.playFromSearch("", null)
                        } else if (StartRules.premiumReset(state == PlaybackState.STATE_ERROR, premiumResetTried,
                                c.playbackState?.errorMessage?.toString())) {
                            // Drives that started from YouTube Music's "needs Premium" error stopped every few
                            // seconds (24 Nov 12:15, 18:15, 20:28); drives that started from STOPPED played on
                            // (20:47: 35 min, no stops). So from that error: stop first, then play - logged, to compare.
                            premiumResetTried = true
                            DriveLog.log("START: YouTube Music is in its 'needs Premium' error - stopping it first, then play (test)")
                            c.transportControls.stop()
                            // Only if this start is still the one running: the car may have gone, or you
                            // may have pressed pause or picked the radio in these 0.7 s.
                            val self = this
                            handler.postDelayed({
                                if (startRunnable === self && (!requireCar || carConnected)) runCatching { c.transportControls.play() }
                            }, 700L)
                        } else {
                            DriveLog.log("START: pressing play (state=${stateName(state)})")
                            c.transportControls.play()
                        }
                        playSentAtTick = tick
                    }
                    // No player yet. Wake it without its screen first; only open the app if that fails.
                    tick == 0 -> { if (!wakeMusicQuietly()) { DriveLog.log("START: no YouTube Music session yet - sending media PLAY key"); sendPlayKey() } }
                    tick == 2 || tick == 6 -> {
                        // A PLAY key wakes YouTube Music without its screen (sent to it directly, see sendPlayKey).
                        DriveLog.log("START: no YouTube Music session yet - sending media PLAY key")
                        sendPlayKey()
                    }
                    tick == 4 || tick == 9 -> launchYtm()
                    else -> DriveLog.log("START: waiting for YouTube Music to be ready...")
                }
                tick++
                if (tick >= START_MAX_TICKS) {
                    DriveLog.log("START: FAILED - YouTube Music not playing after ${START_MAX_TICKS * START_TICK_MS / 1000}s (state=${stateName(ytm?.playbackState?.state)})")
                    if (System.currentTimeMillis() - prefs.lastMusicChangeAt < 7 * 24 * 3600_000L) {
                        DriveLog.log("START: the music app was updated/changed recently - that may have changed how it starts; send this log")
                    }
                    logStartDiagnosis()
                    startRunnable = null
                    // Started by the app (not your play button): rather the radio than silence.
                    if (prefs.radioFallback && !RadioPlayer.on && carConnected && "play" !in reason && "manual" !in reason) {
                        val st = RadioPlayer.lastStation(this@DriveMonitorService)
                        DriveLog.log("START: YouTube Music won't start - playing the radio instead (${st.name})")
                        RadioPlayer.play(this@DriveMonitorService, st)
                    }
                } else {
                    handler.postDelayed(this, START_TICK_MS)
                }
            }
        }
        startRunnable = r
        handler.postDelayed(r, delayMs)
    }

    /** Called once when a start attempt succeeds: announces the song if the greeting couldn't. */
    private fun onMusicStarted() {
        if (!announceSongAfterStart) return
        announceSongAfterStart = false
        val gen = connectGen
        // Give the player a moment to publish the track info, then announce it.
        var waits = 0
        handler.postDelayed(object : Runnable { override fun run() {
            // The forecast you just said yes to may be talking: announce the song after it.
            if (Voice.speaking && waits++ < 40) { handler.postDelayed(this, 500); return }
            val song = currentSong()
            if (song != null && carConnected && gen == connectGen) {
                suppressPauseUntil = SystemClock.elapsedRealtime() + 15_000
                playingBeforeOwnSpeech = true   // the music has just started: this is what we announce
                postOwnSpeechCheck("song announcement", 16_000L)   // fallback, if "done" never comes
                SongAnnouncer.announce(this@DriveMonitorService, song, prefs.greetingDutch) {
                    handler.post { if (gen == connectGen) ownSpeechDone("song announcement") }
                }
            }
        } }, 2_500)
    }

    private fun cancelStart() {
        startRunnable?.let { handler.removeCallbacks(it) }
        startRunnable = null
    }

    /**
     * Wakes the music app without showing it, the way Android Auto does: connecting to its media
     * browser service starts the app in the background and makes it publish its player, which we then
     * press play on. Its screen doesn't come up, so Drive Mode stays where it is.
     */
    private var musicBrowser: android.media.browse.MediaBrowser? = null
    private fun wakeMusicQuietly(): Boolean {
        val svc = packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService").setPackage(musicPkg), 0
        ).firstOrNull()?.serviceInfo ?: return false
        runCatching { musicBrowser?.disconnect() }
        return try {
            val b = android.media.browse.MediaBrowser(this, ComponentName(svc.packageName, svc.name),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { DriveLog.log("START: music app woken in the background") }
                    override fun onConnectionFailed() { DriveLog.log("START: music app didn't let us connect (it may still have started)") }
                }, null)
            musicBrowser = b
            b.connect()
            DriveLog.log("START: waking the music app in the background (no screen)")
            true
        } catch (e: Exception) {
            DriveLog.log("START: couldn't wake the music app in the background (${e.javaClass.simpleName})")
            false
        }
    }

    /** Last resort: open the music app's screen. Drive Mode, if it was up, is put straight back on top. */
    private fun launchYtm() {
        val intent = packageManager.getLaunchIntentForPackage(musicPkg)
        if (intent == null) {
            DriveLog.log("START: music app $musicPkg is not installed! Pick the right app in Drive Music settings.")
            return
        }
        try {
            val driveModeUp = DriveModeActivity.onScreen
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DriveLog.log("START: asked Android to open YouTube Music")
            if (driveModeUp) {
                // Same moment, while we're still on screen (which is what allows it): Drive Mode back on top.
                startActivity(Intent(this, DriveModeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                DriveLog.log("START: Drive Mode kept in front of YouTube Music")
            }
        } catch (e: Exception) {
            DriveLog.log("START: could not open YouTube Music: ${e.message}")
        }
    }

    /**
     * PLAY for YouTube Music while it has no player yet. Sent to YouTube Music's own media-button
     * receiver: Android's general media key goes to whichever app played last - and since the radio
     * that's Drive Music itself, so YouTube Music never woke (18 Nov 16:09, 18:07, 18:21). Android's
     * route is only the last resort, when the music app has no such receiver.
     */
    private fun sendPlayKey(viaAndroid: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val receivers = try {
            packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(musicPkg), 0)
        } catch (e: Exception) { emptyList() }
        if (receivers.isNotEmpty() && !viaAndroid) {
            val r = receivers.first().activityInfo
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setClassName(r.packageName, r.name)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
            }
            DriveLog.log("START: PLAY sent to YouTube Music itself")
            return
        }
        if (receivers.isEmpty()) DriveLog.log("START: YouTube Music has no media button receiver - using Android's media key")
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    // ---------------------------------------------------------------- media sessions

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list ?: emptyList())
    }

    private fun onSessionsChanged(list: List<MediaController>) {
        if (carConnected) {
            // Only note other apps that are actually playing (ignore idle sessions like VLC sitting at NONE).
            val others = list.filter { it.packageName != musicPkg && it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (others.isNotEmpty()) DriveLog.log("Other media playing: " + others.joinToString { it.packageName })
        }
        val found = list.firstOrNull { isMusic(it) }
        if (found == null) {
            if (ytm != null) {
                DriveLog.log("YouTube Music media session closed")
                detachYtm()
            }
        } else if (ytm?.sessionToken != found.sessionToken) {
            attachYtm(found)
        }
    }

    // ---- media controls (used by the car screen) ----
    /** Is this YouTube Music's session? (Never Drive Music's own car-media mirror.) */
    private fun isMusic(c: MediaController) =
        c.packageName == musicPkg && c.sessionToken != com.daand.drivemusic.car.DriveMediaService.ownToken

    /** YouTube Music's controller (also used by the car media screen to mirror and control it). */
    internal fun musicController(): MediaController? =
        ytm?.takeIf { isMusic(it) } ?: findYtm()

    fun isPlaying(): Boolean = musicController()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun skipNext() {
        Voice.stop("you pressed next")
        userPausedViaUi = false
        musicController()?.transportControls?.skipToNext()
        DriveLog.log("Car screen: next track")
    }

    fun skipPrevious() {
        Voice.stop("you pressed previous")
        userPausedViaUi = false
        musicController()?.transportControls?.skipToPrevious()
        DriveLog.log("Car screen: previous track")
    }

    /**
     * Thumbs up / down for the song playing, the way YouTube Music offers it in Android Auto (its own
     * custom actions); else a thumb rating. Down also skips it. False when the music app offers neither.
     */
    fun rate(up: Boolean, where: String): Boolean {
        val c = musicController() ?: return false
        val actions = c.playbackState?.customActions.orEmpty()
        fun matches(a: android.media.session.PlaybackState.CustomAction): Boolean {
            val k = (a.action + " " + a.name).lowercase()
            val down = "down" in k || "dislike" in k
            return if (up) !down && ("thumb" in k || "like" in k) else down
        }
        val custom = actions.firstOrNull { matches(it) }
        val ok = when {
            custom != null -> runCatching { c.transportControls.sendCustomAction(custom, null) }.isSuccess
            c.ratingType == android.media.Rating.RATING_THUMB_UP_DOWN ->
                runCatching { c.transportControls.setRating(android.media.Rating.newThumbRating(up)) }.isSuccess
            else -> false
        }
        DriveLog.log("$where: ${if (up) "like" else "dislike"} - " + when {
            custom != null -> "sent '${custom.name}'"
            ok -> "sent as a rating"
            else -> "the music app offers no like button (it offers: ${actions.joinToString { it.name.toString() }.ifEmpty { "nothing" }})"
        })
        if (!up && ok) {
            // YouTube Music usually skips a disliked song itself; only skip if it's still on.
            val before = currentSong()?.title
            handler.postDelayed({ if (before != null && currentSong()?.title == before) skipNext() }, 1_500)
        }
        return ok
    }

    /** Toggle play/pause from the car screen. A pause here won't be auto-resumed. */
    /** Pause pressed on a car screen: you meant it, so auto-resume leaves it alone. */
    fun pauseByUser(where: String, stopVoice: Boolean = true) {
        if (stopVoice) Voice.stop("you pressed pause")
        userPausedViaUi = true
        musicController()?.transportControls?.pause()
        DriveLog.log("$where: pause (you asked - won't auto-resume)")
    }

    /** Play pressed on a car screen. */
    fun playByUser(where: String) {
        userPausedViaUi = false
        val c = musicController()
        // An empty player (state NONE: YouTube Music closed in the background) ignores a plain play - 26 Nov
        // 12:18, two taps and nothing. The full start opens it and retries until it really plays.
        if (c == null || StartRules.needsFullStart(c.playbackState?.state)) startMusic("$where play", requireCar = false)
        else c.transportControls.play()
        DriveLog.log("$where: play")
    }

    fun playPause() {
        Voice.stop("you pressed play")
        val c = musicController()
        if (c == null) { startMusic("car screen play", requireCar = false); return }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            userPausedViaUi = true
            c.transportControls.pause()
            DriveLog.log("Car screen: pause (you asked - won't auto-resume)")
        } else {
            userPausedViaUi = false
            c.transportControls.play()
            DriveLog.log("Car screen: play")
        }
    }

    /**
     * Brings Drive Mode up once per drive. Both the adapter and Android Auto announce the car; whichever
     * comes first opens it and the second one is ignored, so it never pops twice.
     */
    private fun offerDriveMode(reason: String) {
        if (!prefs.autoOpenDriveMode) return
        if (driveModeOffered) {
            DriveLog.log("Drive Mode: already opened for this drive ($reason)")
            return
        }
        driveModeOffered = true
        driveModeOfferedAt = SystemClock.elapsedRealtime()
        DriveLog.log("Drive Mode: opening ($reason)")
        openDriveMode()
    }

    /** Fires a full-screen-intent notification that opens Drive Mode on the phone when the car
     *  connects. No overlay permission needed - the system launches it (or shows a heads-up to tap). */
    private fun openDriveMode(afterScreenOff: Boolean = false) {
        // The screen going off right after a connect used to fire a second full-screen request while the
        // first was still being delivered, so Drive Mode started twice (two activities, two ticks).
        if (SystemClock.elapsedRealtime() - lastDriveModePostAt < DRIVE_MODE_MIN_GAP_MS) {
            DriveLog.log("Drive Mode: request already on its way - not asking again")
            return
        }
        lastDriveModePostAt = SystemClock.elapsedRealtime()
        // One line per attempt, and it says which attempt it is. The connect line used to be written by
        // the notification path only, so going straight to the screen first left the log without it.
        DriveLog.log((if (afterScreenOff) "Drive Mode: opening after the screen went off" else "Drive Mode: opening on connect") +
            if (CompanionLink.supported(this)) (if (CompanionLink.linked(this)) " (linked device: may open by itself)" else " (not linked: a banner if the phone is in use)") else "")
        // Straight onto the screen where Android allows it; Android doesn't say when it refuses, so look
        // a moment later and fall back to the full-screen notification (which it always allows to try).
        val opened = runCatching {
            startActivity(Intent(this, DriveModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.isSuccess
        val gen = connectGen
        handler.postDelayed({
            if (gen != connectGen || driveEnded) return@postDelayed
            if (opened && (DriveModeActivity.onScreen || driveModeShown)) DriveLog.log("Drive Mode: opened directly")
            else postDriveModeNotification()
        }, if (opened) 1_200L else 0L)
    }

    private fun postDriveModeNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            // Silent: it still pops up (high importance is what allows that), but no ding in the car -
            // which also used to show up in the log as a "notification sound" pause clue.
            // Channel sound can't be changed once created, hence the new channel id.
            nm.deleteNotificationChannel("drivemode")
            nm.createNotificationChannel(
                NotificationChannel(DRIVE_MODE_CHANNEL, "Drive Mode", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            val intent = Intent(this, DriveModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                this, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, DRIVE_MODE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle("Drive Mode")
                .setContentText("Tap to open the big controls")
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            if (!nm.areNotificationsEnabled()) {
                DriveLog.log("Drive Mode: can't open - notifications are off for Drive Music (allow them in the app checklist)")
                return
            }
            // Android only fires a full-screen pop-up for a NEW notification, never for an update.
            // If last drive's banner is still in the shade, remove it first so this one is new.
            nm.cancel(DRIVE_MODE_NOTIFICATION)
            nm.notify(DRIVE_MODE_NOTIFICATION, n)
            // No "opening on connect" here any more: openDriveMode() already said it, once per attempt.
            val fullScreenOk = android.os.Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            if (!fullScreenOk) DriveLog.log(
                "Drive Mode: shown as a tap notification - allow 'full-screen notifications' to make it pop up by itself")
        } catch (e: Exception) {
            DriveLog.log("Drive Mode: could not open (${e.message})")
        }
    }

    /**
     * The phone is in your hand or on with the screen lit when the car connects: Android shows a banner
     * instead of letting the app take the screen. When the screen goes off (phone back in the holder,
     * which is moments later) that restriction lifts - so try once more then, and then leave it alone.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            if (!prefs.autoOpenDriveMode || !driveModeOffered || driveEnded) return
            if (driveModeShown || DriveModeActivity.onScreen) return
            if (SystemClock.elapsedRealtime() - driveModeOfferedAt > DRIVE_MODE_RETRY_WINDOW_MS) return
            if (driveModeRetries >= DRIVE_MODE_RETRIES) return
            driveModeRetries++
            DriveLog.log("Drive Mode: screen went off with Drive Mode not on screen - opening it now")
            openDriveMode(afterScreenOff = true)
        }
    }

    /** Connects the OBD adapter for this drive (if set up). Safe to call again: a running one is left alone. */
    fun startObd() {
        // The adapter powers on with the ignition, usually before Android Auto announces itself. Either
        // one is enough to bring Drive Mode up - offerDriveMode() makes sure it only happens once.
        com.daand.drivemusic.obd.ObdManager.onLinked = {
            handler.post { if (!driveEnded) offerDriveMode("adapter connected") }
        }
        com.daand.drivemusic.obd.ObdManager.start(this) { alert -> handler.post { onCarAlert(alert, connectGen, 0) } }
    }

    /** The adapter was chosen / OBD switched on while driving: connect now instead of next drive. */
    fun startObdIfDriving() {
        if (carConnected || holdingSince > 0) { DriveLog.log("OBD: set up during a drive - connecting now"); startObd() }
    }

    /** The song YouTube Music has loaded (playing or queued), if it tells us. */
    fun currentSong(): GreetingText.Song? {
        val md = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.metadata ?: return null
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val t = GreetingText.cleanMeta(title)
        if (t.isBlank()) return null
        return GreetingText.songFromMeta(t, GreetingText.cleanMeta(artist).removeSuffix(" - Topic"))
    }

    /** The current song's cover, if the music app gives one (see [CoverArt]). */
    fun currentArt(): android.graphics.Bitmap? {
        val md = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.metadata
        val song = md?.let { (it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" + (it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "") }
        return coverCache.get(song?.takeIf { it != "|" }) { CoverArt.from(md) }
    }
    private val coverCache = CoverArt.Cache()

    /**
     * YouTube Music never showed up: which players Android does list, and the app's own state - so a
     * log tells "not running / stopped / switched off" apart from "running but we don't see it".
     */
    private fun logStartDiagnosis() {
        val seen = try { sessions.getActiveSessions(listenerComponent).map { it.packageName }.distinct() } catch (e: SecurityException) { null }
        val app = try { packageManager.getApplicationInfo(musicPkg, 0) } catch (e: Exception) { null }
        DriveLog.log("START: diagnosis - players Android lists: " + (seen?.joinToString().takeUnless { it.isNullOrEmpty() } ?: if (seen == null) "none visible (notification access off?)" else "none") +
            "; $musicPkg " + when {
                app == null -> "not installed"
                !app.enabled -> "switched off (disabled)"
                app.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0 -> "force-stopped (open it once by hand)"
                else -> "installed and allowed to run"
            })
    }

    private fun findYtm(): MediaController? = try {
        sessions.getActiveSessions(listenerComponent)
            .firstOrNull { isMusic(it) }
    } catch (e: SecurityException) {
        null
    }

    private fun attachYtm(c: MediaController) {
        detachYtm()
        ytm = c
        lastYtmState = c.playbackState?.state ?: -1
        c.registerCallback(ytmCallback, handler)
        DriveLog.log("Watching YouTube Music (state=${stateName(lastYtmState)})")
    }

    private fun detachYtm() {
        ytm?.unregisterCallback(ytmCallback)
        ytm = null
        lastYtmState = -1
    }

    private val ytmCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val s = state?.state ?: return
            val prev = lastYtmState
            if (s == prev) return
            lastYtmState = s
            if (s == PlaybackState.STATE_PLAYING && startRunnable != null) {
                // Finish the start routine the moment music plays, so it can never
                // "fight" a pause that happens right after (pause handling deals with that).
                DriveLog.log("START: success - YouTube Music is playing")
                // A fresh start gets one quick retry; the retry itself must not hand out another one,
                // or something that keeps stopping the music (or you) would be fought forever.
                lastStartedAt = SystemClock.elapsedRealtime()
                if (firstStartAt == 0L) firstStartAt = lastStartedAt
                if (bounceRetryPending) bounceRetryPending = false else bounceRetried = false
                cancelStart()
                onMusicStarted()
            }
            if (!carConnected) return
            if (s == PlaybackState.STATE_PLAYING) musicStartedThisDrive = true

            val extra = if (s == PlaybackState.STATE_ERROR) " error='${state.errorMessage}'" else ""
            DriveLog.log("YouTube Music: ${stateName(prev)} -> ${stateName(s)}$extra")
            if (s == PlaybackState.STATE_ERROR && !premiumHintGiven && StartRules.needsPremium(state.errorMessage?.toString())) {
                premiumHintGiven = true
                DriveLog.log("HINT: YouTube Music says this needs Premium - YouTube checking the patched app. It goes with " +
                    "the short stops while you drive; Drive Music puts the music back on. If a newer Morphe patch " +
                    "comes out, updating may stop them.")
            }

            when (s) {
                PlaybackState.STATE_PLAYING -> {
                    userPausedViaUi = false
                    if (pendingPauseRunnable != null) {
                        // It stopped and came right back (the Morphe "Premium" blip, a buffer hiccup):
                        // never counted as a pause.
                        cancelPendingPause()
                        briefInterruptions++
                        DriveLog.log("Brief interruption - playback recovered on its own (not counted)")
                    }
                    if (resumeRunnable != null) {
                        val since = SystemClock.elapsedRealtime() - unexplainedPauseAt
                        if (unexplainedPauseAt > 0 && StartRules.selfRecovered(since)) {
                            // Nobody pressed anything and it played on within seconds: the music app's own
                            // hiccup (buffering, the Morphe "Premium" check), not you - uncount it.
                            pausesThisDrive = maxOf(0, pausesThisDrive - 1)
                            briefInterruptions++
                            DriveLog.log("Music came back by itself after ${since / 1000}s - a music app hiccup, not counted as a pause after all")
                            selfRecoveredAt = SystemClock.elapsedRealtime()
                        } else DriveLog.log("Music came back by itself - auto-resume not needed")
                    }
                    unexplainedPauseAt = 0L
                    cancelResume()
                }
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR,
                PlaybackState.STATE_NONE -> {
                    if (userPausedViaUi) {
                        // You paused it from the car screen / Drive Mode: not an interruption at all.
                    } else if (pendingPauseRunnable != null) {
                        // Already waiting to see whether this stop recovers. An ERROR is the slower
                        // "Premium" blip, so it gets the full longer grace from here instead of the
                        // short one that is still running.
                        if (s == PlaybackState.STATE_ERROR) schedulePendingPause(s)
                    } else if ((prev == PlaybackState.STATE_PLAYING || prev == PlaybackState.STATE_BUFFERING) &&
                        resumeRunnable == null &&
                        SystemClock.elapsedRealtime() >= suppressPauseUntil
                    ) {
                        // Wait out a possible self-recovering blip before treating it as a real pause.
                        schedulePendingPause(s)
                    }
                }
            }
        }

        override fun onSessionDestroyed() {
            DriveLog.log("YouTube Music session destroyed (app closed or killed by the system?)")
            detachYtm()
        }
    }

    /** Waits out the grace for a stop before calling it a real pause (ERROR stops get longer). */
    /**
     * What else is making sound right now (its kind, not the app - Android doesn't say which): to learn what
     * pauses YouTube Music ~1 s after it starts (21 Nov 12:02: twice in a row).
     */
    private fun logOtherSound() = runCatching {
        val playing = getSystemService(android.media.AudioManager::class.java).activePlaybackConfigurations
            .map { c ->
                when (c.audioAttributes.usage) {
                    android.media.AudioAttributes.USAGE_MEDIA -> "music"
                    android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation voice"
                    android.media.AudioAttributes.USAGE_ASSISTANT -> "assistant"
                    android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "call"
                    android.media.AudioAttributes.USAGE_NOTIFICATION, android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "notification"
                    android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "system sound"
                    else -> "other (${c.audioAttributes.usage})"
                }
            }
        DriveLog.log("SOUND: also playing now: " + (playing.takeIf { it.isNotEmpty() }?.joinToString() ?: "nothing"))
        // A voice at the moment of the stop (Waze, an assistant, a call) explains it, even when it has
        // finished by the time the stop counts (24 Nov 18:45: a Waze prompt ended as "you paused - deliberate").
        stopVoice = playing.firstOrNull { it in StartRules.VOICES }
        stopVoiceAt = SystemClock.elapsedRealtime()
    }
    @Volatile private var stopVoice: String? = null
    private val VOICE_CLUE = " was speaking when it stopped"
    @Volatile private var stopVoiceAt = 0L
    /** When the music last came back by itself after an unexplained stop (see [onConfirmedPause]). */
    private var selfRecoveredAt = 0L
    /** "Needs Premium" from YouTube Music: explained once per drive. */
    private var premiumHintGiven = false
    /** The stop-then-play start from that error: once per drive. */
    private var premiumResetTried = false

    private fun schedulePendingPause(stopState: Int) {
        cancelPendingPause()
        // What else is sounding at the moment of the stop (a Waze prompt? an assistant?): the clue for stops
        // that come back by themselves or stay unexplained.
        if (carConnected && SystemClock.elapsedRealtime() >= suppressPauseUntil) logOtherSound()
        val r = Runnable {
            pendingPauseRunnable = null
            val cur = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.playbackState?.state
            if (cur != PlaybackState.STATE_PLAYING) onConfirmedPause()
        }
        pendingPauseRunnable = r
        handler.postDelayed(r, if (stopState == PlaybackState.STATE_ERROR) ERROR_PAUSE_GRACE_MS else PAUSE_GRACE_MS)
    }

    /** A stop that lasted past the grace period: a genuine pause. */
    private fun onConfirmedPause() {
        if (userPausedViaUi) {
            userPausedViaUi = false
            DriveLog.log("Paused from the car screen - leaving it paused")
            return
        }
        // Seen twice on the ASX: the music stops ~1 s after the app started it (YouTube Music / Android
        // Auto settling). Press play again quickly, once per start - not a real pause.
        val nowMs = SystemClock.elapsedRealtime()
        if (lastStartedAt > 0 && StartRules.quickRestart(nowMs - firstStartAt, nowMs - lastStartedAt, quickRestarts, startBounceMs)) {
            quickRestarts++; bounceRetried = true; bounceRetryPending = true
            DriveLog.log("Music stopped right after starting - pressing play again in 1.5s (not counted, $quickRestarts of ${StartRules.MAX_QUICK_RESTARTS})")
            scheduleResume(1_500L)
            return
        }
        // YouTube Music (Morphe) often stops once more ~1 s after it came back by itself (24 Nov 18:19, 18:38,
        // 18:41, 18:45): that used to wait the full 8 s. Same settling stop - back on in 1.5 s, not counted.
        if (StartRules.afterSelfRecovery(nowMs - selfRecoveredAt, selfRecoveredAt > 0)) {
            selfRecoveredAt = 0L
            DriveLog.log("Music stopped again right after coming back by itself - pressing play in 1.5s (not counted)")
            scheduleResume(1_500L)
            return
        }
        // The same ~1 s stop after YouTube Music comes back by itself when our voice is done (24 Nov 12:16:
        // back at :18.0, stopped at :19.1, counted as "PAUSE #1, no clues" and 11 s of silence).
        if (StartRules.afterOwnVoice(nowMs - ownSpeechEndedAt, ownSpeechEndedAt > 0)) {
            ownSpeechEndedAt = 0L   // once per speech: a second stop is a normal pause
            DriveLog.log("Music stopped right after our voice finished - pressing play again in 1.5s (not counted)")
            scheduleResume(1_500L)
            return
        }
        // Android Auto pauses the music once more while it settles after coming back (seen on the ASX
        // after a restart): not a real pause - put it back quickly and don't count it.
        if (reconnectedAt > 0 && SystemClock.elapsedRealtime() - reconnectedAt < SETTLE_MS) {
            reconnectedAt = 0L   // once per reconnect: a second stop is a normal pause
            DriveLog.log("Pause while Android Auto settles after reconnecting - resuming in 2s (not counted)")
            scheduleResume(2_000L)
            return
        }
        pausesThisDrive++
        val clues = diagnose()
        lastPauseClue = clues.joinToString("; ").ifEmpty { "no clues (pause button / steering wheel / Hey Google?)" }
        DriveLog.log("!!! PAUSE #$pausesThisDrive while driving. Clues: $lastPauseClue")
        // A voice heard at the stop (Waze) still lets a self-recovery uncount it - it's not you pausing.
        unexplainedPauseAt = if (clues.isEmpty() || clues.all { it.endsWith(VOICE_CLUE) }) SystemClock.elapsedRealtime() else 0L
        val sinceResume = SystemClock.elapsedRealtime() - lastAutoResumeAt
        val sinceFirstStart = if (firstStartAt > 0) SystemClock.elapsedRealtime() - firstStartAt else Long.MAX_VALUE
        if (lastAutoResumeAt > 0 && StartRules.looksDeliberate(sinceResume, clues.isEmpty(), sinceFirstStart, deliberateAfterMs) && !resumeOffThisDrive) {
            resumeOffThisDrive = true
            DriveLog.log("You paused again ${sinceResume / 1000}s after an auto-resume - looks deliberate. Auto-resume OFF until the next drive.")
        }
        scheduleResume()
    }

    private fun cancelPendingPause() {
        pendingPauseRunnable?.let { handler.removeCallbacks(it) }
        pendingPauseRunnable = null
    }

    // ---------------------------------------------------------------- auto resume

    private fun scheduleResume(delayOverrideMs: Long? = null) {
        if (RadioPlayer.on) { DriveLog.log("The radio is on - leaving YouTube Music paused"); return }
        if (!autoResumeNow) {
            DriveLog.log(if (resumeThisDrive == false) "Auto-resume paused for this drive - leaving it paused"
                else "Auto-resume is off - leaving it paused")
            return
        }
        cancelResume()
        val delay = delayOverrideMs ?: (prefs.resumeDelaySec.coerceIn(2, 120) * 1000L)
        var waits = 0
        val r = object : Runnable {
            override fun run() {
                if (!carConnected || !autoResumeNow) { resumeRunnable = null; return }
                val c = findYtm()
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) { resumeRunnable = null; return }

                val blocker = resumeBlocker()
                if (blocker != null) {
                    // Never resume over a call / Assistant / another app. Wait up to ~15 min, then give up.
                    waits++
                    if (waits > 300) {
                        DriveLog.log("RESUME: gave up - $blocker for too long")
                        resumeRunnable = null
                        return
                    }
                    if (waits == 1 || waits % 20 == 0) DriveLog.log("RESUME: waiting - $blocker")
                    handler.postDelayed(this, 3000)
                    return
                }
                val now = SystemClock.elapsedRealtime()
                while (resumeTimes.isNotEmpty() && now - resumeTimes.first() > RESUME_WINDOW_MS) resumeTimes.removeFirst()
                if (resumeTimes.size >= RESUME_MAX_IN_WINDOW) {
                    DriveLog.log("RESUME: skipped - already auto-resumed $RESUME_MAX_IN_WINDOW times in 10 min (maybe you paused on purpose?)")
                    resumeRunnable = null
                    return
                }
                resumeTimes.addLast(now)
                lastAutoResumeAt = now
                resumesThisDrive++
                resumeRunnable = null
                DriveLog.log("RESUME: auto-resuming YouTube Music")
                startMusic("auto-resume after pause")
            }
        }
        resumeRunnable = r
        DriveLog.log("RESUME: will try to resume in ${if (delay % 1000 == 0L) "${delay / 1000}" else "%.1f".format(java.util.Locale.ROOT, delay / 1000.0)}s if still paused")
        handler.postDelayed(r, delay)
    }

    private fun cancelResume() {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = null
    }

    /** Returns a reason we shouldn't resume right now, or null if it's fine. */
    private fun resumeBlocker(): String? {
        when (audio.mode) {
            // + call screening (4, Android 11+: the assistant answers for you) and redirected calls (5/6, 13+).
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, MODE_CALL_SCREENING, MODE_CALL_REDIRECT,
            MODE_COMMUNICATION_REDIRECT -> return "phone/VoIP call in progress"
            AudioManager.MODE_RINGTONE -> return "phone is ringing"
        }
        val usages = activeUsages()
        if (AudioAttributes.USAGE_ASSISTANT in usages) return "Google Assistant is talking"
        if (AudioAttributes.USAGE_VOICE_COMMUNICATION in usages) return "voice call audio active"
        otherAppPlaying()?.let { return "another app is playing ($it)" }
        return null
    }

    /** Package of another media app that is currently playing, if any. */
    private fun otherAppPlaying(): String? = try {
        sessions.getActiveSessions(listenerComponent)
            // Not YouTube Music, and not our own car media screen (which only mirrors YouTube Music).
            .firstOrNull { it.packageName != musicPkg && it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.packageName
    } catch (e: SecurityException) { null }

    private fun applyConnectVolume() {
        if (!prefs.setVolumeOnConnect) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val idx = Math.round(max * prefs.connectVolumePct / 100f).coerceIn(1, max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
            DriveLog.log("Set media volume to ${prefs.connectVolumePct}% ($idx/$max)")
        } catch (e: SecurityException) {
            DriveLog.log("Could not set volume (Do Not Disturb?): ${e.message}")
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Collects clues about why music just paused. Empty = nothing suspicious found. */
    private fun diagnose(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val clues = mutableListOf<String>()
        if (now < suppressPauseUntil) clues += "Drive Music's own song announcement"
        when (audio.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, MODE_CALL_SCREENING, MODE_CALL_REDIRECT,
            MODE_COMMUNICATION_REDIRECT -> clues += "CALL in progress"
            AudioManager.MODE_RINGTONE -> clues += "PHONE RINGING"
        }
        val usages = activeUsages()
        if (usages.isNotEmpty()) clues += "other audio playing: " + usages.joinToString { usageName(it) }
        else if (stopVoice != null && now - stopVoiceAt < 15_000) clues += "$stopVoice$VOICE_CLUE"
        else if (lastInterruptionAt > 0 && now - lastInterruptionAt < 15_000) {
            clues += "$lastInterruption played ${(now - lastInterruptionAt) / 1000}s ago"
        }
        if (!isOnline()) clues += "NO INTERNET right now"
        if (lastNetworkLostAt > 0 && now - lastNetworkLostAt < 60_000) clues += "internet dropped ${(now - lastNetworkLostAt) / 1000}s ago"
        if (lastBtDisconnectAt > 0 && now - lastBtDisconnectAt < 30_000) clues += "Bluetooth device disconnected ${(now - lastBtDisconnectAt) / 1000}s ago"
        try {
            sessions.getActiveSessions(listenerComponent)
                .filter { it.packageName != musicPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .forEach { clues += "${it.packageName} started playing" }
        } catch (_: SecurityException) {}
        return clues
    }

    /**
     * Notices when the music app was updated or replaced since the last drive (e.g. a Morphe update)
     * and says so in the log - if playback misbehaves right after, that's the first suspect.
     */
    private fun checkMusicAppChanged() {
        val pkg = musicPkg
        val now = "$pkg ${MusicApps.versionOf(this, pkg)}"
        val before = prefs.lastSeenMusicApp
        if (!MusicApps.isInstalled(packageManager, pkg)) {
            DriveLog.log("!!! MUSIC APP NOT FOUND: $pkg isn't installed - open Drive Music → Music & driving and pick your music app")
        } else if (before.isNotEmpty() && before != now) {
            val (oldPkg, oldVer) = before.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "?" } }
            DriveLog.log(if (oldPkg != pkg) "Music app CHANGED: $oldPkg -> $pkg (the old one was removed or renamed)"
                else "Music app UPDATED: $oldVer -> ${now.substringAfter(' ')} (if anything misbehaves now, this update is the likely cause)")
            prefs.lastMusicChangeAt = System.currentTimeMillis()
        }
        prefs.lastSeenMusicApp = now
    }

    private fun logEnvironment() {
        checkMusicAppChanged()
        val pm = getSystemService(android.os.PowerManager::class.java)
        fun ver(pkg: String) = try { packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { "not installed" }
        val microG = MusicApps.findMicroG(this)
        DriveLog.log("Info: music app $musicPkg ${ver(musicPkg)}, Android Auto ${ver(ANDROID_AUTO)}, MicroG ${microG ?: "not found"}")
        if (microG != null) DriveLog.log("Info: MicroG battery unrestricted? ${pm.isIgnoringBatteryOptimizations(microG)}")
        DriveLog.log("Info: battery unrestricted? music app=${pm.isIgnoringBatteryOptimizations(musicPkg)}, " +
            "AndroidAuto=${pm.isIgnoringBatteryOptimizations(ANDROID_AUTO)}, this app=${pm.isIgnoringBatteryOptimizations(packageName)}; " +
            "power saving=${pm.isPowerSaveMode}; internet=${isOnline()}")
    }

    private val playbackConfigCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (!carConnected) return  // this data is only used while driving - skip the work otherwise
            val usages = configs.map { it.audioAttributes.usage }.distinct()
            val interrupts = usages.filter { it !in NOT_INTERRUPTIONS }
            if (interrupts.isNotEmpty()) {
                lastInterruption = interrupts.joinToString { usageName(it) }
                lastInterruptionAt = SystemClock.elapsedRealtime()
            }
            // Only log audio that can actually interrupt music (Assistant, call, notification...).
            // Navigation-voice ducking fires constantly and never pauses music, so it's left out.
            val notable = usages.filter { it in NOTABLE_AUDIO }.map { usageName(it) }.sorted().joinToString()
            if (carConnected && notable != lastNotableAudio) {
                lastNotableAudio = notable
                if (notable.isNotEmpty()) DriveLog.log("Audio: $notable")
            }
        }
    }

    private fun activeUsages(): Set<Int> =
        audio.activePlaybackConfigurations.map { it.audioAttributes.usage }
            .filter { it !in NOT_INTERRUPTIONS }
            .toSet()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (carConnected) DriveLog.log("Network: internet back")
        }

        override fun onLost(network: Network) {
            lastNetworkLostAt = SystemClock.elapsedRealtime()
            if (carConnected) DriveLog.log("Network: internet LOST (no signal?)")
        }
    }

    private fun isOnline(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val name = try { dev?.name ?: dev?.address } catch (e: SecurityException) { "device" }
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                lastBtDisconnectAt = SystemClock.elapsedRealtime()
                DriveLog.log("Bluetooth: disconnected from $name")
            } else {
                DriveLog.log("Bluetooth: connected to $name")
            }
        }
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING"
        null, -1 -> "no session"
        else -> "state$s"
    }

    private fun usageName(u: Int) = when (u) {
        AudioAttributes.USAGE_MEDIA -> "music/media"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION voice"
        AudioAttributes.USAGE_ASSISTANT -> "GOOGLE ASSISTANT"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "CALL audio"
        AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT -> "NOTIFICATION sound"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "system sound"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility/TTS"
        else -> "other($u)"
    }
}
