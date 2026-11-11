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
        const val YTM = "com.google.android.apps.youtube.music"
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        /** Set while the listener is connected, so the UI can trigger a manual test. */
        @Volatile var instance: DriveMonitorService? = null
            private set

        private const val START_TICK_MS = 1500L
        private const val START_MAX_TICKS = 15
        private const val RESUME_WINDOW_MS = 10 * 60 * 1000L
        private const val RESUME_MAX_IN_WINDOW = 4
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

    private var ytm: MediaController? = null
    private var lastYtmState = -1

    // Diagnostics for the current drive
    private var pausesThisDrive = 0
    private var resumesThisDrive = 0
    private var lastNetworkLostAt = 0L
    private var lastBtDisconnectAt = 0L
    private var lastAudioUsages = ""
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

        DriveLog.log("Monitor started (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})")

        carConnection = CarConnection(this).also { it.type.observeForever(carObserver) }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            onSessionsChanged(sessions.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            DriveLog.log("ERROR: no access to media sessions: ${e.message}")
        }
        audio.registerAudioPlaybackCallback(playbackConfigCallback, handler)
        connectivity.registerDefaultNetworkCallback(networkCallback, handler)
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED
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
        carConnection?.type?.removeObserver(carObserver)
        carConnection = null
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackConfigCallback) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(btReceiver) }
        detachYtm()
    }

    // ---------------------------------------------------------------- Android Auto

    private val carObserver = Observer<Int> { type ->
        val name = when (type) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> "Android Auto connected"
            CarConnection.CONNECTION_TYPE_NATIVE -> "Android Automotive"
            else -> "not connected"
        }
        prefs.lastCarState = name
        val nowConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        if (nowConnected == carConnected) return@Observer
        carConnected = nowConnected

        if (nowConnected) {
            carConnectedAt = SystemClock.elapsedRealtime()
            pausesThisDrive = 0
            resumesThisDrive = 0
            resumeTimes.clear()
            DriveLog.log("=== CAR: $name ===")
            logEnvironment()
            if (prefs.autoStart) startMusic("Android Auto connected")
            else DriveLog.log("Auto-start is turned off in settings")
        } else {
            val mins = (SystemClock.elapsedRealtime() - carConnectedAt) / 60000
            DriveLog.log("=== CAR: disconnected after ${mins} min. Pauses: $pausesThisDrive, auto-resumes: $resumesThisDrive ===")
            cancelStart()
            cancelResume()
        }
    }

    // ---------------------------------------------------------------- start music

    /** Opens YouTube Music and presses play, retrying until it is really playing. */
    fun startMusic(reason: String) {
        cancelStart()
        DriveLog.log("START: trying to start YouTube Music ($reason)")
        var tick = 0
        var playSentAtTick = -10
        val r = object : Runnable {
            override fun run() {
                val c = ytm ?: findYtm()?.also { attachYtm(it) }
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
        handler.post(r)
    }

    private fun cancelStart() {
        startRunnable?.let { handler.removeCallbacks(it) }
        startRunnable = null
    }

    private fun launchYtm() {
        val intent = packageManager.getLaunchIntentForPackage(YTM)
        if (intent == null) {
            DriveLog.log("START: YouTube Music is not installed!")
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
            DriveLog.log("Media apps active: " + list.joinToString { "${it.packageName}(${stateName(it.playbackState?.state)})" }.ifEmpty { "none" })
        }
        val found = list.firstOrNull { it.packageName == YTM }
        if (found == null) {
            if (ytm != null) {
                DriveLog.log("YouTube Music media session closed")
                detachYtm()
            }
        } else if (ytm?.sessionToken != found.sessionToken) {
            attachYtm(found)
        }
    }

    private fun findYtm(): MediaController? = try {
        sessions.getActiveSessions(listenerComponent).firstOrNull { it.packageName == YTM }
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
            if (!carConnected) return

            val extra = if (s == PlaybackState.STATE_ERROR) " error='${state.errorMessage}'" else ""
            DriveLog.log("YouTube Music: ${stateName(prev)} -> ${stateName(s)}$extra")

            when (s) {
                PlaybackState.STATE_PLAYING -> {
                    if (resumeRunnable != null) DriveLog.log("Music came back by itself - auto-resume not needed")
                    cancelResume()
                }
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR,
                PlaybackState.STATE_NONE -> {
                    if (prev == PlaybackState.STATE_PLAYING || prev == PlaybackState.STATE_BUFFERING) {
                        pausesThisDrive++
                        DriveLog.log("!!! PAUSE #$pausesThisDrive while driving. ${diagnose()}")
                        scheduleResume()
                    }
                }
            }
        }

        override fun onSessionDestroyed() {
            DriveLog.log("YouTube Music session destroyed (app closed or killed by the system?)")
            detachYtm()
        }
    }

    // ---------------------------------------------------------------- auto resume

    private fun scheduleResume() {
        if (!prefs.autoResume) {
            DriveLog.log("Auto-resume is off - leaving it paused")
            return
        }
        cancelResume()
        val delay = prefs.resumeDelaySec.coerceIn(2, 120) * 1000L
        var waits = 0
        val r = object : Runnable {
            override fun run() {
                if (!carConnected) { resumeRunnable = null; return }
                val c = ytm ?: findYtm()
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) { resumeRunnable = null; return }

                val blocker = resumeBlocker()
                if (blocker != null && waits < 20) {
                    waits++
                    if (waits == 1 || waits % 5 == 0) DriveLog.log("RESUME: waiting - $blocker")
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
        val otherPlaying = try {
            sessions.getActiveSessions(listenerComponent)
                .firstOrNull { it.packageName != YTM && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        } catch (e: SecurityException) { null }
        if (otherPlaying != null) return "another app is playing (${otherPlaying.packageName})"
        return null
    }

    // ---------------------------------------------------------------- diagnostics

    /** Builds a one-line guess at why music paused. */
    private fun diagnose(): String {
        val now = SystemClock.elapsedRealtime()
        val clues = mutableListOf<String>()
        when (audio.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> clues += "CALL in progress"
            AudioManager.MODE_RINGTONE -> clues += "PHONE RINGING"
        }
        val usages = activeUsages()
        if (usages.isNotEmpty()) clues += "other audio playing: " + usages.joinToString { usageName(it) }
        if (!isOnline()) clues += "NO INTERNET right now"
        if (lastNetworkLostAt > 0 && now - lastNetworkLostAt < 60_000) clues += "internet dropped ${(now - lastNetworkLostAt) / 1000}s ago"
        if (lastBtDisconnectAt > 0 && now - lastBtDisconnectAt < 30_000) clues += "Bluetooth device disconnected ${(now - lastBtDisconnectAt) / 1000}s ago"
        try {
            sessions.getActiveSessions(listenerComponent)
                .filter { it.packageName != YTM && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .forEach { clues += "${it.packageName} started playing" }
        } catch (_: SecurityException) {}
        if (lastAudioUsages.isNotEmpty() && clues.none { it.startsWith("other audio") }) clues += "recent audio: $lastAudioUsages"
        return if (clues.isEmpty()) "Clues: none found (pause button / steering wheel / Hey Google / app itself?)"
        else "Clues: " + clues.joinToString("; ")
    }

    private fun logEnvironment() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        fun ver(pkg: String) = try { packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { "not installed" }
        DriveLog.log("Info: YouTube Music ${ver(YTM)}, Android Auto ${ver(ANDROID_AUTO)}")
        DriveLog.log("Info: battery unrestricted? YTM=${pm.isIgnoringBatteryOptimizations(YTM)}, " +
            "AndroidAuto=${pm.isIgnoringBatteryOptimizations(ANDROID_AUTO)}, this app=${pm.isIgnoringBatteryOptimizations(packageName)}; " +
            "power saving=${pm.isPowerSaveMode}; internet=${isOnline()}")
    }

    private val playbackConfigCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val names = configs.map { usageName(it.audioAttributes.usage) }.distinct().sorted().joinToString()
            if (names == lastAudioUsages) return
            lastAudioUsages = names
            if (carConnected) DriveLog.log("Audio streams now: ${names.ifEmpty { "silence" }}")
        }
    }

    private fun activeUsages(): Set<Int> =
        audio.activePlaybackConfigurations.map { it.audioAttributes.usage }
            .filter { it != AudioAttributes.USAGE_MEDIA && it != AudioAttributes.USAGE_UNKNOWN }
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
