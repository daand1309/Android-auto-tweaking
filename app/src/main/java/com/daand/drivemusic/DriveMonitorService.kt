package com.daand.drivemusic

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
    private val musicPkg get() = prefs.musicPackage

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
    private var lastPauseClue = ""
    private var lastAutoResumeAt = 0L
    /** Set when you pause again right after an auto-resume: we take the hint for the rest of the drive. */
    var resumeOffThisDrive = false
    private var firstCarEvent = true
    /** Announce "Now playing ..." once music starts (when the song wasn't known before the greeting). */
    private var announceSongAfterStart = false
    /** Our own speech can briefly pause the music; pauses before this time are attributed to it. */
    private var ownAnnouncementUntil = 0L
    private val resumeTimes = ArrayDeque<Long>()

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
        handler.removeCallbacks(updateCheck)
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
            DriveLog.log("=== CAR: $name ===")
            logEnvironment()
            if (wasFirstEvent && findYtm() != null) {
                // The monitor was (re)started by Android in the middle of a drive. Don't touch playback.
                DriveLog.log("Monitor started mid-drive - not changing what's playing")
                return
            }
            applyConnectVolume()
            val other = otherAppPlaying()
            val startAction: () -> Unit = {
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
                    if (carConnected) {
                        val song = currentSong()
                        announceSongAfterStart = song == null && prefs.announceSong && prefs.autoStart && other == null
                        if (song != null) DriveLog.log("Queued song: ${song.title} - ${song.artist}")
                        WelcomeAnnouncer.run(this, song) { if (carConnected) startAction() }
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
            cancelStart()
            cancelResume()
            cancelPendingPause()
            if (prefs.pauseOnDisconnect) {
                val c = findYtm()
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    c.transportControls.pause()
                    DriveLog.log("Paused music because you left the car")
                }
            }
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
                val c = ytm?.takeIf { it.packageName == musicPkg } ?: findYtm()?.also { attachYtm(it) }
                val state = c?.playbackState?.state
                when {
                    state == PlaybackState.STATE_PLAYING -> {
                        DriveLog.log("START: success - YouTube Music is playing (after ~${tick * START_TICK_MS / 1000}s)")
                        startRunnable = null
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
                    startRunnable = null
                } else {
                    handler.postDelayed(this, START_TICK_MS)
                }
            }
        }
        startRunnable = r
        handler.postDelayed(r, delayMs)
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
        val found = list.firstOrNull { it.packageName == musicPkg }
        if (found == null) {
            if (ytm != null) {
                DriveLog.log("YouTube Music media session closed")
                detachYtm()
            }
        } else if (ytm?.sessionToken != found.sessionToken) {
            attachYtm(found)
        }
    }

    /** The song YouTube Music has loaded (playing or queued), if it tells us. */
    fun currentSong(): GreetingText.Song? {
        val md = (ytm?.takeIf { it.packageName == musicPkg } ?: findYtm())?.metadata ?: return null
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        return GreetingText.Song(title, artist).takeIf { title.isNotBlank() }
    }

    private fun findYtm(): MediaController? = try {
        sessions.getActiveSessions(listenerComponent).firstOrNull { it.packageName == musicPkg }
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
                if (announceSongAfterStart) {
                    announceSongAfterStart = false
                    // Give the player a moment to publish the track info, then announce it.
                    handler.postDelayed({
                        val song = currentSong()
                        if (song != null && carConnected) {
                            ownAnnouncementUntil = SystemClock.elapsedRealtime() + 15_000
                            SongAnnouncer.announce(this@DriveMonitorService, song, prefs.greetingDutch)
                        }
                    }, 2_500)
                }
            }
            if (!carConnected) return

            val extra = if (s == PlaybackState.STATE_ERROR) " error='${state.errorMessage}'" else ""
            DriveLog.log("YouTube Music: ${stateName(prev)} -> ${stateName(s)}$extra")

            when (s) {
                PlaybackState.STATE_PLAYING -> {
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
                    if ((prev == PlaybackState.STATE_PLAYING || prev == PlaybackState.STATE_BUFFERING) &&
                        pendingPauseRunnable == null && resumeRunnable == null
                    ) {
                        // Wait out a possible self-recovering blip before treating it as a real pause.
                        val r = Runnable {
                            pendingPauseRunnable = null
                            val cur = (ytm?.takeIf { it.packageName == musicPkg } ?: findYtm())?.playbackState?.state
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
            .firstOrNull { it.packageName != musicPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
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
        if (now < ownAnnouncementUntil) clues += "Drive Music's own song announcement"
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

    private fun logEnvironment() {
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
