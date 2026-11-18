package com.daand.drivemusic.car

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService
import com.daand.drivemusic.DriveLog
import com.daand.drivemusic.DriveMonitorService
import com.daand.drivemusic.DriveUpdates
import com.daand.drivemusic.Greeter
import com.daand.drivemusic.News
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.R
import com.daand.drivemusic.Radio
import com.daand.drivemusic.RadioPlayer
import com.daand.drivemusic.ResumeToggle
import com.daand.drivemusic.WelcomeAnnouncer

/**
 * Drive Music as a *media* app in Android Auto. Android Auto only lists sideloaded apps of a few
 * types (media is one; the templated car screen is not), so this is how Drive Music gets an icon in
 * the car's app list.
 *
 * It plays nothing itself: it mirrors what YouTube Music is playing, sends every button to YouTube
 * Music, and adds a short menu (welcome briefing, auto-resume, weather, news).
 */
class DriveMediaService : MediaBrowserService() {

    companion object {
        /** Our own mirror session, so the monitor never mistakes it for YouTube Music. */
        @Volatile var ownToken: MediaSession.Token? = null
    }

    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var mirrored: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        DriveLog.init(this)
        session = MediaSession(this, "DriveMusicCar").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        ownToken = session.sessionToken
        RadioPlayer.addListener(radioChanged)
        mirrorTick.run()
    }

    /** The radio started / stopped / changed station: the car screen shows it, and its menu updates. */
    private val radioChanged: () -> Unit = {
        publishMetadata(); publishState()
        notifyChildrenChanged(MediaMenu.RADIO)
    }

    override fun onDestroy() {
        ownToken = null
        RadioPlayer.removeListener(radioChanged)
        handler.removeCallbacksAndMessages(null)
        mirrored?.unregisterCallback(mirrorCallback)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ who may browse

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!MediaMenu.trustedCaller(clientPackageName, clientUid, Process.myUid())) {
            DriveLog.log("Car media: refused browser $clientPackageName")
            return null
        }
        return BrowserRoot(MediaMenu.ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val p = Prefs(this)
        if (parentId == MediaMenu.RADIO) {
            val playing = RadioPlayer.station.takeIf { RadioPlayer.on }
            result.sendResult(Radio.decode(p.radioStations).mapIndexed { i, st ->
                MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(MediaMenu.radioId(i)).setTitle("📻  ${st.name}")
                    .setSubtitle(if (st == playing) "On now" else "Internet radio").build(), MediaBrowser.MediaItem.FLAG_PLAYABLE)
            }.toMutableList())
            return
        }
        if (parentId != MediaMenu.ROOT) { result.sendResult(mutableListOf()); return }
        val items = MediaMenu.items(
            monitorRunning = DriveMonitorService.instance != null,
            autoResume = ResumeToggle.isOnNow(this),
            weather = p.lastWeatherSummary.takeIf { System.currentTimeMillis() - p.lastWeatherAt < 3 * 60 * 60 * 1000L },
            butler = p.butler,
            car = if (p.obdEnabled) com.daand.drivemusic.obd.ObdManager.live()?.let { com.daand.drivemusic.obd.CarStatusText.describe(it, p.greetingDutch).replace("\n", " · ") } ?: "No data yet" else null,
        ).map { (id, title, subtitle) ->
            MediaBrowser.MediaItem(
                MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
                if (id == MediaMenu.RADIO) MediaBrowser.MediaItem.FLAG_BROWSABLE else MediaBrowser.MediaItem.FLAG_PLAYABLE,
            )
        }.toMutableList()
        result.sendResult(items)
    }

    // ------------------------------------------------------------------ buttons -> YouTube Music

    private fun monitor() = DriveMonitorService.instance
    /** YouTube Music - never our own mirror (that would mirror itself in an endless loop). */
    private fun yt() = monitor()?.musicController()?.takeIf { it.sessionToken != session.sessionToken }

    /** Who pressed: Android Auto (wheel or car screen), the car's Bluetooth, the Assistant, the phone... */
    private fun origin(): String {
        val pkg = runCatching { session.currentControllerInfo.packageName }.getOrNull().orEmpty()
        return when (pkg) {
            "com.google.android.projection.gearhead" -> "Android Auto"
            "com.android.bluetooth" -> "car Bluetooth"
            "com.android.systemui" -> "phone"
            "com.google.android.googlequicksearchbox", "com.google.android.carassistant" -> "Assistant"
            packageName -> "Drive Music"
            "" -> "unknown"
            else -> pkg
        }
    }

    /** One line per press, so drive logs show what the wheel / car screen really sends. */
    private fun press(what: String, did: String) = DriveLog.log("BUTTON: $what from ${origin()} -> $did")

    /** The radio was stopped from the car (wheel pause): the next play brings the radio back, not YouTube Music. */
    private var radioPausedFromCar = false

    private val callback = object : MediaSession.Callback() {
        /**
         * Raw key presses (steering wheel, car buttons) before they become play / next / ...: logged with
         * the key, long-press repeats and the device it came from, to learn what this car's buttons send.
         */
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            val ev: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            if (ev != null && (ev.action == KeyEvent.ACTION_DOWN || ev.repeatCount == 0)) {
                val device = runCatching { android.view.InputDevice.getDevice(ev.deviceId)?.name }.getOrNull() ?: "id ${ev.deviceId}"
                DriveLog.log("BUTTON KEY: ${KeyEvent.keyCodeToString(ev.keyCode)} ${if (ev.action == KeyEvent.ACTION_DOWN) "down" else "up"}" +
                    (if (ev.repeatCount > 0) " (held, repeat ${ev.repeatCount})" else "") +
                    (if (ev.isLongPress) " LONG" else "") + " · from ${origin()} · device '$device' · source 0x${Integer.toHexString(ev.source)}")
            }
            return super.onMediaButtonEvent(intent)
        }
        override fun onPlay() {
            if (RadioPlayer.on) { press("play", "radio already on"); return }
            if (radioPausedFromCar) {
                radioPausedFromCar = false
                val st = RadioPlayer.lastStation(this@DriveMediaService)
                press("play", "radio back on (${st.name})"); RadioPlayer.play(this@DriveMediaService, st); return
            }
            press("play", "YouTube Music"); monitor()?.playByUser("Car media")
        }
        // Through the monitor, so auto-resume knows you paused on purpose (it would restart the music otherwise).
        override fun onPause() {
            if (RadioPlayer.on) { press("pause", "radio off"); radioPausedFromCar = true; RadioPlayer.stop("you pressed pause in the car"); return }
            press("pause", "YouTube Music"); monitor()?.pauseByUser("Car media")
        }
        override fun onSkipToNext() {
            radioPausedFromCar = false
            if (RadioPlayer.on) { press("next", "next station"); RadioPlayer.step(this@DriveMediaService, 1) }
            else { press("next", "next song"); monitor()?.skipNext() }
        }
        override fun onSkipToPrevious() {
            radioPausedFromCar = false
            if (RadioPlayer.on) { press("previous", "previous station"); RadioPlayer.step(this@DriveMediaService, -1) }
            else { press("previous", "previous song"); monitor()?.skipPrevious() }
        }
        override fun onStop() { onPause() }
        override fun onSeekTo(pos: Long) { if (!RadioPlayer.on) yt()?.transportControls?.seekTo(pos) }
        override fun onFastForward() { press("fast-forward (held next?)", "nothing yet") }
        override fun onRewind() { press("rewind (held previous?)", "nothing yet") }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { menu(mediaId ?: return) }
        /** "Hey Google, play <song> on Drive Music" -> YouTube Music searches and plays it. */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            // "Play Radio 2 on Drive Music": one of your stations, if the words name one.
            Radio.match(query.orEmpty(), Radio.decode(Prefs(this@DriveMediaService).radioStations))?.let { st ->
                radioPausedFromCar = false
                press("voice", "radio ${st.name}"); RadioPlayer.play(this@DriveMediaService, st); return
            }
            DriveLog.log("Car media: voice search")
            MediaMenu.playFromSearch(yt(), monitor(), query, extras)
        }
        override fun onCustomAction(action: String, extras: Bundle?) { menu(action) }
    }

    /** A menu item or custom button was tapped. */
    private fun menu(id: String) {
        val p = Prefs(this)
        DriveLog.log("Car media: tapped $id")
        MediaMenu.radioIndex(id)?.let { i ->
            radioPausedFromCar = false
            Radio.decode(p.radioStations).getOrNull(i)?.let { RadioPlayer.toggle(this, it) }
            return
        }
        when (id) {
            MediaMenu.WELCOME -> WelcomeAnnouncer.run(this, monitor()?.currentSong(), recordAsGreeted = false)
            MediaMenu.RESUME -> {
                ResumeToggle.tapNow(this, "Car media")   // for this drive only
                notifyChildrenChanged(MediaMenu.ROOT)
                publishState()
            }
            MediaMenu.WEATHER -> p.lastWeatherSummary.takeIf { it.isNotBlank() }?.let {
                speakOnce(MediaMenu.weatherLine(it, p.greetingDutch, p.butler), p.greetingDutch)
            }
            MediaMenu.NEWS -> if (!MediaMenu.claimVoice()) DriveLog.log("Car media: busy speaking - ignored") else Thread {
                val items = News.fetchAll(p.greetingDutch, p.weatherPlace) {} +
                    (if (p.followActive) News.fetchFollow(p.greetingDutch) {} else emptyList())
                val story = News.pick(items, System.currentTimeMillis(), emptyList())
                val followed = if (p.followActive) News.pickFollowed(items, News.keywords(p.followTopic), System.currentTimeMillis(), emptyList()) else null
                val text = DriveUpdates.compose(emptyList(), story, p.greetingDutch, p.butler, p.greetingName,
                    followed = followed, source = followed?.source ?: "")
                    ?: MediaMenu.noNewsLine(p.greetingDutch, p.butler)
                handler.post {
                    try { Greeter(this).speak(text, p.greetingDutch) { MediaMenu.releaseVoice() } }
                    catch (e: Exception) { MediaMenu.releaseVoice() }
                }
            }.start()
            MediaMenu.PLAY -> callback.onPlay()
            MediaMenu.CAR -> speakOnce(com.daand.drivemusic.obd.CarStatusText.spoken(com.daand.drivemusic.obd.ObdManager.live(),
                p.greetingDutch, p.butler, com.daand.drivemusic.obd.ObdManager.trip), p.greetingDutch)
        }
    }

    /** One spoken menu item at a time (and never over the greeting): mashing a button can't start a choir. */
    private fun speakOnce(text: String, dutch: Boolean) {
        if (!MediaMenu.claimVoice()) { DriveLog.log("Car media: busy speaking - ignored"); return }
        try { Greeter(this).speak(text, dutch) { MediaMenu.releaseVoice() } }
        catch (e: Exception) { MediaMenu.releaseVoice() }
    }

    // ------------------------------------------------------------------ mirror YouTube Music

    private val mirrorCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { publishMetadata() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { publishState() }
    }

    /** YouTube Music's session can come and go: re-attach every few seconds while we're open. */
    private val mirrorTick = object : Runnable {
        override fun run() {
            val c = yt()
            if (c?.sessionToken != mirrored?.sessionToken) {
                mirrored?.unregisterCallback(mirrorCallback)
                mirrored = c
                c?.registerCallback(mirrorCallback, handler)
                publishMetadata()
                publishState()
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun publishMetadata() {
        RadioPlayer.station?.takeIf { RadioPlayer.on }?.let { st ->
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, when {
                    RadioPlayer.state == RadioPlayer.State.CONNECTING -> if (RadioPlayer.problem.isNotBlank()) "Signal lost - ${RadioPlayer.problem}" else "Tuning in…"
                    else -> RadioPlayer.nowPlaying ?: "Internet radio"
                })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Drive Music radio").build())
            return
        }
        val md = mirrored?.metadata
        session.setMetadata(
            if (md != null) MediaMetadata.Builder(md).build()
            else MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (monitor() == null) "Open Drive Music on your phone" else "Nothing playing")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drive Music")
                .build()
        )
    }

    private fun publishState() {
        val src = mirrored?.playbackState
        val resumeOn = ResumeToggle.isOnNow(this)
        val radio = RadioPlayer.on
        val b = PlaybackState.Builder()
        if (radio) b.setState(if (RadioPlayer.state == RadioPlayer.State.CONNECTING) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PLAYING,
            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f, android.os.SystemClock.elapsedRealtime())
        // Keep YouTube Music's own timestamp, or the car's progress bar drifts by however old the update is.
        else b.setState(src?.state ?: PlaybackState.STATE_PAUSED, src?.position ?: 0L, src?.playbackSpeed ?: 0f,
                src?.lastPositionUpdateTime?.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())
        val state = b
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
            )
            .addCustomAction(PlaybackState.CustomAction.Builder(MediaMenu.WELCOME, "Welcome briefing", R.drawable.ic_stat_note).build())
            .addCustomAction(PlaybackState.CustomAction.Builder(MediaMenu.RESUME,
                if (resumeOn) "Auto-resume: on" else "Auto-resume: off",
                if (resumeOn) R.drawable.ic_answer_yes else R.drawable.ic_answer_no).build())
            .build()
        session.setPlaybackState(state)
    }
}

/** Shares the untrusted-text cleaner with the menu (one line, no markup, sane length). */
private object GreetingTextBridge { fun clean(s: String) = com.daand.drivemusic.GreetingText.cleanMeta(s) }

/** The car media menu and who may open it. Pure logic, unit tested. */
object MediaMenu {
    const val ROOT = "drive_music_root"
    const val PLAY = "play_youtube_music"
    const val WELCOME = "welcome_briefing"
    const val RESUME = "toggle_auto_resume"
    const val WEATHER = "weather"
    const val NEWS = "news"
    const val CAR = "car_status"
    const val RADIO = "radio"
    private const val RADIO_PREFIX = "radio:"
    fun radioId(i: Int) = "$RADIO_PREFIX$i"
    fun radioIndex(id: String): Int? = if (id.startsWith(RADIO_PREFIX)) id.removePrefix(RADIO_PREFIX).toIntOrNull()?.takeIf { it >= 0 } else null

    /** Android Auto, the system, and ourselves. Other apps can't browse or trigger the menu. */
    private val TRUSTED = setOf(
        "com.google.android.projection.gearhead",       // Android Auto
        "com.google.android.carassistant",              // Assistant for cars
        "com.google.android.googlequicksearchbox",      // Assistant ("play Drive Music")
        "com.android.systemui", "com.android.bluetooth",
    )

    fun trustedCaller(pkg: String, uid: Int, myUid: Int): Boolean =
        uid == myUid || uid == android.os.Process.SYSTEM_UID || pkg in TRUSTED

    /** (id, title, subtitle) for each menu row. */
    fun items(monitorRunning: Boolean, autoResume: Boolean, weather: String?, butler: Boolean, car: String? = null): List<Triple<String, String, String>> {
        if (!monitorRunning) return listOf(Triple(PLAY, "Open Drive Music on your phone", "Finish setup to control your music here"))
        return listOfNotNull(
            Triple(PLAY, "▶  Play YouTube Music", "Continue where you left off"),
            Triple(RADIO, "📻  Radio", "Your stations - the wheel changes station"),
            Triple(WELCOME, if (butler) "🎩  Welcome briefing" else "🔊  Welcome briefing", "Greeting, weather and roads"),
            Triple(RESUME, if (autoResume) "↻  Auto-resume: On" else "↻  Auto-resume: Off", "Tap to switch"),
            Triple(WEATHER, "☁  Weather", weather ?: "Updated on your next drive"),
            Triple(NEWS, "📰  Any news?", "Only what you need to know"),
            car?.let { Triple(CAR, "🔧  Car status", it) },
        )
    }

    /** Voice search: hand the words to YouTube Music; nothing said ("play Drive Music") = just play. */
    fun playFromSearch(yt: MediaController?, monitor: DriveMonitorService?, query: String?, extras: Bundle?) {
        val q = query?.trim()?.take(200).orEmpty()
        when {
            yt == null -> monitor?.startMusic("voice search", requireCar = false)
            q.isEmpty() -> yt.transportControls.play()
            else -> yt.transportControls.playFromSearch(q, cleanSearchExtras(extras))
        }
    }

    /** The only search hints we pass on (all plain text). */
    private val SEARCH_KEYS = listOf(
        android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.EXTRA_MEDIA_ARTIST,
        android.provider.MediaStore.EXTRA_MEDIA_ALBUM, android.provider.MediaStore.EXTRA_MEDIA_TITLE,
        android.provider.MediaStore.EXTRA_MEDIA_GENRE, android.provider.MediaStore.EXTRA_MEDIA_PLAYLIST,
    )

    /**
     * Voice search can be sent by any app, with any data attached. Never pass that on as-is (it could
     * crash YouTube Music): copy only the known plain-text hints into a fresh bundle.
     */
    fun cleanSearchExtras(extras: Bundle?): Bundle {
        val out = Bundle()
        if (extras == null) return out
        for (k in SEARCH_KEYS) {
            val v = try { extras.getString(k) } catch (e: Exception) { null } ?: continue
            out.putString(k, GreetingTextBridge.clean(v))
        }
        return out
    }

    /** Spoken menu items: one at a time, and never while the greeting is speaking. */
    private val voiceBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun claimVoice(): Boolean = !WelcomeAnnouncer.isBusy && voiceBusy.compareAndSet(false, true)
    fun releaseVoice() = voiceBusy.set(false)

    /** "4° · overcast · 20% rain" is for the screen; spoken it becomes "4 degrees, overcast, 20% rain". */
    fun weatherLine(summary: String, dutch: Boolean, butler: Boolean): String {
        val spoken = summary.replace(Regex("""(-?\d+)°"""), if (dutch) "$1 graden" else "$1 degrees").replace(" · ", ", ")
        return when {
            butler && dutch -> "Het weer, meneer: $spoken."
            butler -> "The weather, sir: $spoken."
            dutch -> "Het weer: $spoken."
            else -> "The weather: $spoken."
        }
    }

    fun noNewsLine(dutch: Boolean, butler: Boolean) = when {
        butler && dutch -> "Geen nieuws dat u hoeft te weten, meneer. Een rustige dag."
        butler -> "Nothing you need to know, sir. A refreshingly quiet day."
        dutch -> "Geen belangrijk nieuws op dit moment."
        else -> "No important news right now."
    }
}
