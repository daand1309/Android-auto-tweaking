package com.daand.drivemusic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet radio: the stations you pick (a Dutch set to start with), played by the app itself.
 * Starting a station pauses YouTube Music (as "you paused it", so auto-resume leaves it alone);
 * pressing play on YouTube Music takes the sound back and the radio stops by itself.
 * Station lists are pure and unit tested; [Radio.search] asks radio-browser.info (a free, open
 * station directory - only the words you type are sent).
 */
object Radio {
    data class Station(val name: String, val url: String) {
        fun encode() = "${name.replace('|', '/').replace(';', ',').trim()}|${url.trim()}"
    }

    /** Checked 18 Nov 2026: each answers with an MP3 stream (redirects already followed). */
    val DEFAULTS = listOf(
        Station("NPO Radio 1", "https://icecast.omroep.nl/radio1-bb-mp3"),
        Station("NPO Radio 2", "https://icecast.omroep.nl/radio2-bb-mp3"),
        Station("NPO 3FM", "https://icecast.omroep.nl/3fm-bb-mp3"),
        Station("Qmusic", "https://audio-streaming.qmusic.nl/Qmusic_nl_live_96.mp3"),
        Station("Radio 538", "https://22543.live.streamtheworld.com/RADIO538.mp3"),
        Station("Sky Radio", "https://22543.live.streamtheworld.com/SKYRADIO.mp3"),
        Station("Radio 10", "https://22543.live.streamtheworld.com/RADIO10.mp3"),
        Station("Radio Veronica", "https://22543.live.streamtheworld.com/VERONICA.mp3"),
        Station("100% NL", "https://28973.live.streamtheworld.com/100PNL_MP3_SC"),
        Station("SLAM!", "https://22333.live.streamtheworld.com/SLAM_MP3_SC"),
        Station("NPO Klassiek", "https://icecast.omroep.nl/radio4-bb-mp3"),
        Station("FunX", "https://icecast.omroep.nl/funx-bb-mp3"),
        Station("BNR Nieuwsradio", "https://25583.live.streamtheworld.com/BNR_NIEUWSRADIO.mp3"),
    )
    const val MAX_STATIONS = 20

    /** Only http(s) streams: anything else (a file, an app link) is not a radio station. */
    fun validUrl(u: String) = Regex("""^https?://[^\s|;]+$""").matches(u.trim())

    /** Your list ("" = never changed: the Dutch set). */
    fun decode(s: String): List<Station> = if (s.isBlank()) DEFAULTS else s.split(';').mapNotNull { e ->
        val i = e.indexOf('|')
        if (i <= 0) null else Station(e.substring(0, i), e.substring(i + 1)).takeIf { validUrl(it.url) }
    }.take(MAX_STATIONS)

    fun encode(list: List<Station>) = list.take(MAX_STATIONS).joinToString(";") { it.encode() }

    /** Adding a station already there (same stream) keeps one; new ones go at the end. */
    fun add(list: List<Station>, st: Station) = (list.filterNot { it.url.trim() == st.url.trim() } + st).take(MAX_STATIONS)

    /** radio-browser.info answer -> stations that give a plain stream (most-voted first, as asked). */
    fun parseSearch(json: String): List<Station> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val codec = o.optString("codec").uppercase()
            val name = o.optString("name").trim().replace(Regex("\\s+"), " ")
            if (name.isBlank() || !validUrl(url) || (codec.isNotBlank() && codec !in setOf("MP3", "AAC", "AAC+"))) null
            else Station(name.take(40), url)
        }.distinctBy { it.url }
    } catch (e: Exception) { emptyList() }

    fun searchUrl(query: String) = "https://all.api.radio-browser.info/json/stations/search?name=" +
        URLEncoder.encode(query.trim(), "UTF-8") + "&hidebroken=true&order=votes&reverse=true&limit=15"

    /** Off the main thread. Empty when nothing is found or there's no signal. */
    fun search(query: String): List<Station> = try {
        val conn = URL(searchUrl(query)).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try { if (conn.responseCode == 200) parseSearch(conn.inputStream.bufferedReader().use { it.readText().take(300_000) }) else emptyList() }
        finally { conn.disconnect() }
    } catch (e: Exception) { emptyList() }
}

/** Plays one station at a time through the car, like any music app. Main thread. */
object RadioPlayer {
    enum class State { OFF, CONNECTING, PLAYING, ERROR }

    @Volatile var state = State.OFF; private set
    @Volatile var station: Radio.Station? = null; private set
    @Volatile var problem = ""; private set
    val on get() = state == State.CONNECTING || state == State.PLAYING

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var appCtx: Context? = null
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                // Another music app (you pressed play on YouTube Music): it has the sound now.
                AudioManager.AUDIOFOCUS_LOSS -> stop("another app started playing")
                // Our own voice, a navigation prompt or a call: quieter, or silent until it's over.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.25f, 0.25f)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.setVolume(0f, 0f)
                AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
            }
        }
    }

    fun toggle(ctx: Context, st: Radio.Station) = if (on && station == st) stop("you pressed stop") else play(ctx, st)

    /**
     * Tune in. YouTube Music keeps playing while the station connects; only once the radio actually
     * sounds is YouTube Music paused (in the background, at the same song and spot) - no silent gap.
     */
    fun play(ctx: Context, st: Radio.Station) {
        val app = ctx.applicationContext
        appCtx = app
        release()
        station = st; state = State.CONNECTING; problem = ""
        Prefs(app).radioLast = st.encode()
        DriveLog.log("RADIO: tuning in to ${st.name}")
        changed()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(st.url)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                // Ready: hand over. YouTube Music off - counted as your choice, so it isn't auto-resumed over the radio.
                DriveMonitorService.instance?.let { if (it.isPlaying()) it.pauseByUser("Radio", stopVoice = false) }
                val audio = app.getSystemService(AudioManager::class.java)
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, main).setWillPauseWhenDucked(false).build()
                focus = req
                if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) { fail(st, "the car's audio is busy (a call?)"); return@setOnPreparedListener }
                p.start(); state = State.PLAYING
                DriveLog.log("RADIO: playing ${st.name}")
                changed()
            }
            mp.setOnErrorListener { p, what, extra -> if (player === p) fail(st, "the stream stopped ($what/$extra) - no signal?"); true }
            mp.setOnCompletionListener { if (player === it) fail(st, "the stream ended") }
            mp.prepareAsync()
            // A stream that never starts (dead station, no signal): give up after 20 s instead of hanging.
            main.postDelayed({ if (player === mp && state == State.CONNECTING) fail(st, "it didn't answer within 20 s") }, 20_000)
        } catch (e: Exception) { fail(st, e.message ?: e.javaClass.simpleName) }
    }

    /** The station you had last (or the first of your list) - for the one-tap switch back to radio. */
    fun lastStation(ctx: Context): Radio.Station {
        val p = Prefs(ctx)
        val list = Radio.decode(p.radioStations)
        return Radio.decode(p.radioLast).firstOrNull()?.takeIf { it in list } ?: list.first()
    }

    /** ⏮ / ⏭ while the radio is on: the station before / after in your list (wrapping round). */
    fun step(ctx: Context, by: Int) {
        val list = Radio.decode(Prefs(ctx).radioStations)
        val i = list.indexOf(station).coerceAtLeast(0)
        play(ctx, list[Math.floorMod(i + by, list.size)])
    }

    /** Radio off and YouTube Music back where it was - one tap, no gap (it takes the sound and the radio stops). */
    fun backToMusic(ctx: Context) {
        DriveLog.log("RADIO: switching back to YouTube Music")
        val svc = DriveMonitorService.instance
        if (svc == null) { stop("you switched back to music"); return }
        svc.playByUser("Radio tab")
        // YouTube Music's own focus request ends the radio; if it never takes it, end it anyway.
        main.postDelayed({ if (on) stop("you switched back to music") }, 2_500)
    }

    private fun fail(st: Radio.Station, why: String) {
        DriveLog.log("RADIO: ${st.name} - $why")
        release()
        station = st; state = State.ERROR; problem = why
        changed()
    }

    fun stop(why: String) {
        if (state == State.OFF) return
        station?.let { DriveLog.log("RADIO: ${it.name} off ($why)") }
        release()
        state = State.OFF; problem = ""
        changed()
    }

    private fun release() {
        player?.let { p -> player = null; runCatching { p.stop() }; runCatching { p.release() } }
        focus?.let { f -> appCtx?.getSystemService(AudioManager::class.java)?.let { runCatching { it.abandonAudioFocusRequest(f) } } }
        focus = null
    }
}
