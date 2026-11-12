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
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        /** Set while the listener is connected, so the UI can trigger a manual test. */
        @Volatile var instance: DriveMonitorService? = null
            private set

        const val DRIVE_MODE_NOTIFICATION = 7
        private const val DRIVE_MODE_CHANNEL = "drive_mode_silent"
        private const val START_TICK_MS = 1500L
        private const val START_MAX_TICKS = 15
        private const val RESUME_WINDOW_MS = 10 * 60 * 1000L
        private const val RESUME_MAX_IN_WINDOW = 4

        /** Wait this long before calling a stop a "pause": patched builds briefly flip to ERROR
         *  (e.g. a "Premium" blip) and recover on their own within about a second. */
        private const val PAUSE_GRACE_MS = 3000L

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
    private var lastAutoResumeAt = 0L
    /** Set when you pause again right after an auto-resume: we take the hint for the rest of the drive. */
    var resumeOffThisDrive = false
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
    }

    override fun onListenerDisconnected() {
        DriveLog.log("Monitor stopped by the system - asking Android to restart it")
        cleanup()
        requestRebind(ComponentName(this, DriveMonitorService::class.java))
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance !== this) return
        instance = null
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
        firstCarEvent = false
        onCarState(type)
    }

    private fun onCarState(type: Int) {
        val name = when (type) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> "Android Auto connected"
            CarConnection.CONNECTION_TYPE_NATIVE -> "Android Automotive"
            else -> "not connected"
        }
        prefs.lastCarState = name
        val nowConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        val wasFirstEvent = firstCarEvent
        firstCarEvent = false
        if (nowConnected == carConnected) return
        carConnected = nowConnected
        val gen = ++connectGen

        if (nowConnected) {
            carConnectedAt = SystemClock.elapsedRealtime()
            pausesThisDrive = 0
            resumesThisDrive = 0
            briefInterruptions = 0
            lastNotableAudio = ""
            cancelPendingPause()
            resumeTimes.clear()
            resumeOffThisDrive = false
            lastAutoResumeAt = 0L
            lastPauseClue = ""
            userPausedViaUi = false
            announceSongAfterStart = false
            DriveLog.log("=== CAR: $name ===")
            logEnvironment()
            Parking.clear(this)  // you're driving: the old parked spot is no longer where the car is
            DriveUpdates.newDrive()
            driveDataStart = appBytes()
            com.daand.drivemusic.obd.ObdManager.start(this) { alert -> handler.post { onCarAlert(alert, connectGen, 0) } }
            handler.removeCallbacks(driveUpdate)
            handler.postDelayed(driveUpdate, DriveUpdates.FIRST_CHECK_MS)
            if (prefs.autoOpenDriveMode) openDriveMode()
            if (wasFirstEvent && findYtm() != null) {
                // The monitor was (re)started by Android in the middle of a drive. Don't touch playback.
                DriveLog.log("Monitor started mid-drive - not changing what's playing")
                return
            }
            applyConnectVolume()
            val other = otherAppPlaying()
            val startAction: () -> Unit = start@{
                if (gen != connectGen) return@start  // stale: the car disconnected/reconnected since
                when {
                    !prefs.autoStart -> DriveLog.log("Auto-start is turned off in settings")
                    other != null -> DriveLog.log("START: skipped - $other is already playing, not taking over")
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
                        val song = currentSong()
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
            val mins = (SystemClock.elapsedRealtime() - carConnectedAt) / 60000
            val brief = if (briefInterruptions > 0) ", $briefInterruptions brief blip(s) ignored" else ""
            DriveLog.log("=== CAR: disconnected after ${mins} min. Pauses: $pausesThisDrive, auto-resumes: $resumesThisDrive$brief ===")
            prefs.lastDrive = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) +
                " - ${mins} min, $pausesThisDrive pause(s), $resumesThisDrive auto-resume(s)" +
                if (lastPauseClue.isNotEmpty()) "\nLast pause: $lastPauseClue" else ""
            Parking.capture(this)
            if (driveDataStart >= 0) {
                val used = appBytes() - driveDataStart
                if (used >= 0) DriveLog.log("Data used by Drive Music this drive: ${used / 1024} KB")
                driveDataStart = -1
            }
            Briefing.endNow(this)  // don't leave the welcome pop-up behind if you left mid-greeting
            News.clearCache()  // free the memory until the next drive
            com.daand.drivemusic.obd.ObdManager.stop()
            UpdatePrompt.cancel(this)
            handler.removeCallbacks(driveUpdate)
            cancelStart()
            cancelResume()
            cancelPendingPause()
            announceSongAfterStart = false
            suppressPauseUntil = 0L
            getSystemService(NotificationManager::class.java).cancel(DRIVE_MODE_NOTIFICATION)
            if (prefs.pauseOnDisconnect) {
                val c = findYtm()
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    c.transportControls.pause()
                    DriveLog.log("Paused music because you left the car")
                }
            }
        }
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
        if (!carConnected || gen != connectGen) return
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.ENGINE_WARM && !prefs.obdWarmup) return
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.LOW_FUEL && !prefs.obdFuel) return
        if (alert.kind == com.daand.drivemusic.obd.CarAlerts.Kind.READY_TO_DRIVE && prefs.obdReadySeconds == 0) return
        val blocker = resumeBlocker() ?: if (WelcomeAnnouncer.isBusy || UpdatePrompt.question != null) "something else is speaking" else null
        // "Ready to drive" waits for the greeting to finish (a few seconds), then says it - or, if the
        // moment has passed (~45 s of waiting), not at all.
        if (blocker != null) {
            when {
                alert.timely && attempt < 15 -> handler.postDelayed({ onCarAlert(alert, gen, attempt + 1) }, 3_000L)
                alert.timely -> DriveLog.log("OBD: '${alert.kind}' no longer useful - skipped")
                attempt < 20 -> handler.postDelayed({ onCarAlert(alert, gen, attempt + 1) }, if (alert.urgent) 15_000L else 120_000L)
            }
            return
        }
        val text = com.daand.drivemusic.obd.CarAlerts.message(alert, prefs.greetingDutch, prefs.butler)
        DriveLog.log("OBD: \"$text\"")
        suppressPauseUntil = SystemClock.elapsedRealtime() + 60_000L
        if (alert.urgent) {
            val token = Briefing.start(this, if (prefs.greetingDutch) "Auto" else "Car", text)
            safeSpeak(text) { Briefing.end(this, token); suppressPauseUntil = 0L }
        } else safeSpeak(text) { suppressPauseUntil = 0L }
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
                else safeSpeak(DriveUpdates.declinedLine(dutch, butler)) { suppressPauseUntil = 0L }
            }
        }
    }

    private fun speakUpdate(text: String, title: String) {
        DriveLog.log("UPDATE: \"$text\"")
        val token = Briefing.start(this, title, text)
        safeSpeak(text) {
            Briefing.end(this, token)
            suppressPauseUntil = 0L
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
                        } else {
                            DriveLog.log("START: pressing play (state=${stateName(state)})")
                            c.transportControls.play()
                        }
                        playSentAtTick = tick
                    }
                    tick == 0 -> launchYtm()
                    tick == 2 || tick == 6 -> {
                        // No YouTube Music session yet: a media PLAY key wakes the last-used music app.
                        DriveLog.log("START: no YouTube Music session yet - sending media PLAY key")
                        sendPlayKey()
                    }
                    tick == 4 -> launchYtm()
                    else -> DriveLog.log("START: waiting for YouTube Music to be ready...")
                }
                tick++
                if (tick >= START_MAX_TICKS) {
                    DriveLog.log("START: FAILED - YouTube Music not playing after ${START_MAX_TICKS * START_TICK_MS / 1000}s (state=${stateName(ytm?.playbackState?.state)})")
                    if (System.currentTimeMillis() - prefs.lastMusicChangeAt < 7 * 24 * 3600_000L) {
                        DriveLog.log("START: the music app was updated/changed recently - that may have changed how it starts; send this log")
                    }
                    startRunnable = null
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
        handler.postDelayed({
            val song = currentSong()
            if (song != null && carConnected && gen == connectGen) {
                suppressPauseUntil = SystemClock.elapsedRealtime() + 15_000
                SongAnnouncer.announce(this@DriveMonitorService, song, prefs.greetingDutch)
            }
        }, 2_500)
    }

    private fun cancelStart() {
        startRunnable?.let { handler.removeCallbacks(it) }
        startRunnable = null
    }

    private fun launchYtm() {
        val intent = packageManager.getLaunchIntentForPackage(musicPkg)
        if (intent == null) {
            DriveLog.log("START: music app $musicPkg is not installed! Pick the right app in Drive Music settings.")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DriveLog.log("START: asked Android to open YouTube Music")
        } catch (e: Exception) {
            DriveLog.log("START: could not open YouTube Music: ${e.message}")
        }
    }

    private fun sendPlayKey() {
        val now = SystemClock.uptimeMillis()
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
            val others = list.filter { it.packageName != musicPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
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
        userPausedViaUi = false
        musicController()?.transportControls?.skipToNext()
        DriveLog.log("Car screen: next track")
    }

    fun skipPrevious() {
        userPausedViaUi = false
        musicController()?.transportControls?.skipToPrevious()
        DriveLog.log("Car screen: previous track")
    }

    /** Toggle play/pause from the car screen. A pause here won't be auto-resumed. */
    /** Pause pressed on a car screen: you meant it, so auto-resume leaves it alone. */
    fun pauseByUser(where: String) {
        userPausedViaUi = true
        musicController()?.transportControls?.pause()
        DriveLog.log("$where: pause (you asked - won't auto-resume)")
    }

    /** Play pressed on a car screen. */
    fun playByUser(where: String) {
        userPausedViaUi = false
        val c = musicController()
        if (c == null) startMusic("$where play", requireCar = false) else c.transportControls.play()
        DriveLog.log("$where: play")
    }

    fun playPause() {
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

    /** Fires a full-screen-intent notification that opens Drive Mode on the phone when the car
     *  connects. No overlay permission needed - the system launches it (or shows a heads-up to tap). */
    private fun openDriveMode() {
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
            val fullScreenOk = android.os.Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            DriveLog.log(if (fullScreenOk) "Drive Mode: opening on connect"
                else "Drive Mode: shown as a tap notification - allow 'full-screen notifications' to make it pop up by itself")
        } catch (e: Exception) {
            DriveLog.log("Drive Mode: could not open (${e.message})")
        }
    }

    /** The song YouTube Music has loaded (playing or queued), if it tells us. */
    fun currentSong(): GreetingText.Song? {
        val md = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.metadata ?: return null
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val t = GreetingText.cleanMeta(title)
        return GreetingText.Song(t, GreetingText.cleanMeta(artist)).takeIf { t.isNotBlank() }
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
                cancelStart()
                onMusicStarted()
            }
            if (!carConnected) return

            val extra = if (s == PlaybackState.STATE_ERROR) " error='${state.errorMessage}'" else ""
            DriveLog.log("YouTube Music: ${stateName(prev)} -> ${stateName(s)}$extra")

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
                    if (resumeRunnable != null) DriveLog.log("Music came back by itself - auto-resume not needed")
                    cancelResume()
                }
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR,
                PlaybackState.STATE_NONE -> {
                    if (userPausedViaUi) {
                        // You paused it from the car screen / Drive Mode: not an interruption at all.
                    } else if ((prev == PlaybackState.STATE_PLAYING || prev == PlaybackState.STATE_BUFFERING) &&
                        pendingPauseRunnable == null && resumeRunnable == null &&
                        SystemClock.elapsedRealtime() >= suppressPauseUntil
                    ) {
                        // Wait out a possible self-recovering blip before treating it as a real pause.
                        val r = Runnable {
                            pendingPauseRunnable = null
                            val cur = (ytm?.takeIf { isMusic(it) } ?: findYtm())?.playbackState?.state
                            if (cur != PlaybackState.STATE_PLAYING) onConfirmedPause()
                        }
                        pendingPauseRunnable = r
                        handler.postDelayed(r, PAUSE_GRACE_MS)
                    }
                }
            }
        }

        override fun onSessionDestroyed() {
            DriveLog.log("YouTube Music session destroyed (app closed or killed by the system?)")
            detachYtm()
        }
    }

    /** A stop that lasted past the grace period: a genuine pause. */
    private fun onConfirmedPause() {
        if (userPausedViaUi) {
            userPausedViaUi = false
            DriveLog.log("Paused from the car screen - leaving it paused")
            return
        }
        pausesThisDrive++
        val clues = diagnose()
        lastPauseClue = clues.joinToString("; ").ifEmpty { "no clues (pause button / steering wheel / Hey Google?)" }
        DriveLog.log("!!! PAUSE #$pausesThisDrive while driving. Clues: $lastPauseClue")
        val sinceResume = SystemClock.elapsedRealtime() - lastAutoResumeAt
        if (lastAutoResumeAt > 0 && sinceResume < 60_000 && clues.isEmpty() && !resumeOffThisDrive) {
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

    private fun scheduleResume() {
        if (!prefs.autoResume) {
            DriveLog.log("Auto-resume is off - leaving it paused")
            return
        }
        if (resumeOffThisDrive) {
            DriveLog.log("Auto-resume paused for this drive - leaving it paused")
            return
        }
        cancelResume()
        val delay = prefs.resumeDelaySec.coerceIn(2, 120) * 1000L
        var waits = 0
        val r = object : Runnable {
            override fun run() {
                if (!carConnected || !prefs.autoResume || resumeOffThisDrive) { resumeRunnable = null; return }
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
        DriveLog.log("RESUME: will try to resume in ${delay / 1000}s if still paused")
        handler.postDelayed(r, delay)
    }

    private fun cancelResume() {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = null
    }

    /** Returns a reason we shouldn't resume right now, or null if it's fine. */
    private fun resumeBlocker(): String? {
        when (audio.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> return "phone/VoIP call in progress"
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
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> clues += "CALL in progress"
            AudioManager.MODE_RINGTONE -> clues += "PHONE RINGING"
        }
        val usages = activeUsages()
        if (usages.isNotEmpty()) clues += "other audio playing: " + usages.joinToString { usageName(it) }
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
