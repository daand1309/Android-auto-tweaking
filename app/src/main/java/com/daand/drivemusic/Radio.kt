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

    /**
     * "Hey Google, play Radio 2 on Drive Music": the station whose name best matches the words, or
     * null (then the words go to YouTube Music). Spaces, case and "NPO"/"radio" fillers don't matter.
     */
    fun match(query: String, list: List<Station>): Station? {
        fun norm(x: String) = x.lowercase().replace("%", "procent").replace(Regex("[^a-z0-9]"), "")
        val q = norm(query.removePrefix("play ").removePrefix("speel "))
        if (q.length < 2) return null
        val core = { x: String -> norm(x).removePrefix("npo").removePrefix("radio") }
        return list.firstOrNull { norm(it.name) == q }
            ?: list.firstOrNull { core(it.name) == core(query) && core(query).isNotEmpty() }
            ?: list.filter { norm(it.name).contains(q) || (q.contains(norm(it.name)) && norm(it.name).length >= 3) }
                .minByOrNull { kotlin.math.abs(norm(it.name).length - q.length) }
    }

    /** Android blocks unencrypted (http) streams: the same address over https, which most servers also answer. */
    fun httpsVariant(url: String) = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    // ---- stations near you (radio-browser.info geo search: Europe-wide, free and made for apps)

    data class Nearby(val station: Station, val km: Int)

    /** Your position rounded to ~10 km (like the weather) - the exact spot never leaves the phone. */
    fun nearbyUrl(lat: Double, lon: Double, km: Int) = "https://all.api.radio-browser.info/json/stations/search?has_geo_info=true" +
        "&geo_lat=${Weather.round(lat)}&geo_long=${Weather.round(lon)}&geo_distance=${km * 1000}" +
        "&order=clickcount&reverse=true&hidebroken=true&limit=60"

    /**
     * Playable ones only (https MP3/AAC - Android won't play unencrypted streams), each station once
     * (no "2nd server" copies), most-listened first, at most [max]. Checked 19 Nov: in the Randstad,
     * Antwerp and Düsseldorf 12-15 of the top 15 play.
     */
    fun parseNearby(json: String, max: Int = 15): List<Nearby> = try {
        val a = JSONArray(json)
        val seen = HashSet<String>()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url_resolved").trim()
            val codec = o.optString("codec").uppercase()
            val name = o.optString("name").trim().replace(Regex("\\s+"), " ").take(40)
            val key = name.lowercase().filter { it.isLetterOrDigit() }.take(12)
            when {
                name.isBlank() || !url.startsWith("https://") || !validUrl(url) || codec !in setOf("MP3", "AAC", "AAC+") -> null
                !seen.add(key) || !seen.add(url) -> null
                else -> Nearby(Station(name, url), Math.round(o.optDouble("geo_distance", 0.0).takeIf { it.isFinite() } ?: 0.0).toInt() / 1000)
            }
        }.take(max)
    } catch (e: Exception) { emptyList() }

    /** Off the main thread. 40 km; where that finds few (the north), 80 km. */
    fun nearby(lat: Double, lon: Double): List<Nearby> {
        fun get(km: Int) = try {
            val conn = URL(nearbyUrl(lat, lon, km)).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
            try { if (conn.responseCode == 200) parseNearby(conn.inputStream.bufferedReader().use { it.readText().take(500_000) }) else emptyList() }
            finally { conn.disconnect() }
        } catch (e: Exception) { emptyList() }
        val near = get(40)
        return if (near.size >= 8) near else get(80).ifEmpty { near }
    }

    /** Streams that failed this session: not offered again until the app restarts. */
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

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

/**
 * The song a station is playing right now, from its "ICY" metadata (sent in the stream by NPO, Talpa
 * and most others). Read with a short separate request about once a minute - a few MB an hour.
 */
object IcyMeta {
    /** "StreamTitle='Artist - Song';StreamUrl='';" -> "Artist - Song" (null when empty or just an ad marker). */
    fun parse(block: String): String? {
        val t = Regex("""StreamTitle='(.*?)';""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: return null
        val clean = GreetingText.cleanMeta(t).trim(' ', '-')
        return clean.takeIf { it.length >= 3 && !it.equals("advertisement", true) && !it.startsWith("adw_") }
    }

    /** Off the main thread. Reads up to the first metadata block (at most ~130 KB), then hangs up. */
    fun read(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("Icy-MetaData", "1")
        conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try {
            val every = conn.getHeaderField("icy-metaint")?.toIntOrNull()?.takeIf { it in 1..131_072 } ?: return null
            val input = java.io.DataInputStream(conn.inputStream.buffered())
            input.skipNBytesCompat(every.toLong())
            val len = input.read() * 16
            if (len <= 0) null else ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8).let { parse(it) }
        } finally { conn.disconnect() }
    } catch (e: Exception) { null }

    private fun java.io.InputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) { val k = skip(left); if (k <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= k }
    }
}

/** Plays one station at a time through the car, like any music app. Main thread. */
object RadioPlayer {
    enum class State { OFF, CONNECTING, PLAYING, ERROR }

    @Volatile var state = State.OFF; private set
    @Volatile var station: Radio.Station? = null; private set
    @Volatile var problem = ""; private set
    /** The song the station says it's playing (null: not sent / not known yet). */
    @Volatile var nowPlaying: String? = null; private set
    val on get() = state == State.CONNECTING || state == State.PLAYING

    /** Dropped streams (a tunnel, a dead zone): this many tries, this far apart, before giving up. */
    private val RETRY_MS = longArrayOf(2_000, 4_000, 8_000, 15_000)
    private const val META_EVERY_MS = 45_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var appCtx: Context? = null
    /** It has played this session: a drop is then a reconnect, and giving up hands back to YouTube Music. */
    private var everPlayed = false
    private var retries = 0
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
        releasePlayer(); abandonFocus()
        everPlayed = false; retries = 0
        station = st; state = State.CONNECTING; problem = ""; nowPlaying = null
        Prefs(app).radioLast = st.encode()
        DriveLog.log("RADIO: tuning in to ${st.name}")
        changed()
        connect(app, st)
    }

    private fun connect(app: Context, st: Radio.Station) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            // Keeps the phone's processor awake while it plays: with the screen off / phone locked the
            // stream would otherwise stall after a while.
            mp.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(st.url)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                if (focus == null) {
                    // Ready: hand over. YouTube Music off - counted as your choice, so it isn't auto-resumed over the radio.
                    DriveMonitorService.instance?.let { if (it.isPlaying()) it.pauseByUser("Radio", stopVoice = false) }
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusListener, main).setWillPauseWhenDucked(false).build()
                    if (app.getSystemService(AudioManager::class.java).requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                        giveUp(st, "the car's audio is busy (a call?)"); return@setOnPreparedListener
                    }
                    focus = req
                }
                p.start(); state = State.PLAYING; problem = ""
                DriveLog.log(if (everPlayed) "RADIO: ${st.name} back (reconnected)" else "RADIO: playing ${st.name}")
                everPlayed = true; retries = 0
                changed()
                pollMeta(st)
            }
            mp.setOnErrorListener { p, what, extra -> if (player === p) dropped(app, st, "the stream stopped ($what/$extra)"); true }
            mp.setOnCompletionListener { if (player === it) dropped(app, st, "the stream ended") }
            mp.prepareAsync()
            // A stream that never starts (dead station, no signal): don't hang on it.
            main.postDelayed({ if (player === mp && state == State.CONNECTING) dropped(app, st, "no answer within 20 s") }, 20_000)
        } catch (e: Exception) { dropped(app, st, e.message ?: e.javaClass.simpleName) }
    }

    /**
     * The stream went away. Playing before (a tunnel, a dead zone): try again a few times, keeping the
     * sound ours so YouTube Music doesn't jump in. Never played: one more try, then say so.
     */
    private fun dropped(app: Context, st: Radio.Station, why: String) {
        releasePlayer()
        val tries = if (everPlayed) RETRY_MS.size else 1
        if (retries < tries) {
            val wait = RETRY_MS[retries]
            retries++
            state = State.CONNECTING; problem = "reconnecting ($retries/$tries)"
            DriveLog.log("RADIO: ${st.name} - $why - trying again in ${wait / 1000}s ($retries/$tries)")
            changed()
            main.postDelayed({ if (station == st && state == State.CONNECTING && player == null) connect(app, st) }, wait)
            return
        }
        giveUp(st, "$why - no signal?")
    }

    /** Couldn't (re)connect. If it had been playing, YouTube Music takes over so the car doesn't go silent. */
    private fun giveUp(st: Radio.Station, why: String) {
        DriveLog.log("RADIO: ${st.name} - $why")
        // Never started: hide it from "near you" today. One that played and then lost the signal (a
        // tunnel) is fine - it stays.
        if (!everPlayed) Radio.failed += st.url
        val hadPlayed = everPlayed
        releasePlayer(); abandonFocus()
        station = st; state = State.ERROR; problem = why; nowPlaying = null
        changed()
        if (hadPlayed) DriveMonitorService.instance?.let {
            DriveLog.log("RADIO: lost ${st.name} - back to YouTube Music")
            it.playByUser("Radio lost")
        }
    }

    /** About once a minute while playing: the song title, if the station sends it. */
    private fun pollMeta(st: Radio.Station) {
        Thread({
            val t = IcyMeta.read(st.url)
            main.post {
                if (station != st || state != State.PLAYING) return@post
                if (t != nowPlaying) { nowPlaying = t; changed() }
                main.postDelayed({ if (station == st && state == State.PLAYING && player != null) pollMeta(st) }, META_EVERY_MS)
            }
        }, "radio-meta").start()
    }

    /** The station you had last (or the first of your list) - for the one-tap switch back to radio. */
    fun lastStation(ctx: Context): Radio.Station {
        val p = Prefs(ctx)
        val list = Radio.decode(p.radioStations)
        return Radio.decode(p.radioLast).firstOrNull()?.takeIf { it in list } ?: list.first()
    }

    /** ⏮ / ⏭ while the radio is on: the station before / after in your list (wrapping round). */
    fun step(ctx: Context, by: Int) {
        val mine = Radio.decode(Prefs(ctx).radioStations)
        // Listening to a station near you (not one of yours): ⏮ ⏭ go through the ones near you.
        val near = RadioNearby.current()?.map { it.station }.orEmpty()
        val list = if (station != null && station !in mine && station in near) near else mine
        val i = list.indexOf(station)
        play(ctx, list[Math.floorMod(if (i < 0) (if (by > 0) -1 else 0) + by else i + by, list.size)])
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

    /** When it last went off, and whether something else took the sound (not you) - for "was it on?". */
    @Volatile var stoppedAt = 0L; private set
    @Volatile var stoppedByOthers = false; private set
    /** On now, or taken off by something else (Android Auto dropping) in the last [ms]. */
    fun onOrJustLost(ms: Long = 5_000) = on || (stoppedByOthers && android.os.SystemClock.elapsedRealtime() - stoppedAt < ms)

    fun stop(why: String) {
        if (state == State.OFF) return
        stoppedAt = android.os.SystemClock.elapsedRealtime()
        stoppedByOthers = why.startsWith("another app")
        station?.let { DriveLog.log("RADIO: ${it.name} off ($why)") }
        releasePlayer(); abandonFocus()
        state = State.OFF; problem = ""; nowPlaying = null
        changed()
    }

    private fun releasePlayer() {
        player?.let { p -> player = null; runCatching { p.stop() }; runCatching { p.release() } }
    }

    private fun abandonFocus() {
        focus?.let { f -> appCtx?.getSystemService(AudioManager::class.java)?.let { runCatching { it.abandonAudioFocusRequest(f) } } }
        focus = null
    }
}
