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
    }.take(MAX_STATIONS).ifEmpty { DEFAULTS }   // never an empty list: "last station" / ⏭ need one

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
            val name = GreetingText.cleanMeta(o.optString("name"))
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
        data class Cand(val n: Nearby, val mp3: Boolean)
        // Each station once; where it's listed with several streams, the MP3 one: Android's player
        // can't open some AAC+ streams (Radio Rijnmond AAC+ on 19 Nov: error 1/-2147483648, its MP3 plays).
        val byKey = LinkedHashMap<String, Cand>()
        val urls = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val raw = o.optString("url_resolved").trim()
            // Unencrypted addresses: tried over https (most answer; the ones that don't fail once and are hidden).
            val url = httpsVariant(raw)
            val codec = o.optString("codec").uppercase()
            val name = GreetingText.cleanMeta(o.optString("name")).take(40)
            if (name.isBlank() || !url.startsWith("https://") || !validUrl(url) || codec !in setOf("MP3", "AAC", "AAC+") || !urls.add(url)) continue
            val key = name.lowercase().filter { it.isLetterOrDigit() }.take(12)
            val km = Math.round(o.optDouble("geo_distance", 0.0).takeIf { it.isFinite() } ?: 0.0).toInt() / 1000
            val c = Cand(Nearby(Station(baseName(name), url), km), codec == "MP3")
            val had = byKey[key]
            if (had == null) byKey[key] = c else if (!had.mp3 && c.mp3) byKey[key] = c.copy(n = c.n.copy(km = had.n.km))
        }
        byKey.values.map { it.n }.take(max)
    } catch (e: Exception) { emptyList() }

    /** "Radio Rijnmond (AAC+)" -> "Radio Rijnmond": the name to look a station's other streams up by. */
    fun baseName(name: String) = name.replace(Regex("\\s*\\((AAC\\+?|MP3|HQ|LQ|\\d+\\s*k(bps)?)\\)$", RegexOption.IGNORE_CASE), "").trim()

    /**
     * The same station's MP3 stream (from a radio-browser search answer), other than [notUrl]: for when
     * Android's player can't open a station's AAC+ stream. Same name ignoring "(AAC+)", https (or tried as).
     */
    fun mp3Alternative(json: String, name: String, notUrl: String): Station? = try {
        val want = baseName(name).lowercase().filter { it.isLetterOrDigit() }
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstNotNullOfOrNull { o ->
            val url = httpsVariant(o.optString("url_resolved").trim())
            val n = baseName(GreetingText.cleanMeta(o.optString("name")))
            if (o.optString("codec").uppercase() == "MP3" && url != notUrl && validUrl(url) && url.startsWith("https://") &&
                n.lowercase().filter { it.isLetterOrDigit() } == want) Station(n.take(40), url) else null
        }
    } catch (e: Exception) { null }

    /** Off the main thread: [mp3Alternative] looked up on radio-browser by the station's name. */
    fun findMp3(st: Station): Station? = try {
        val conn = URL("https://all.api.radio-browser.info/json/stations/search?name=" + URLEncoder.encode(baseName(st.name), "UTF-8") +
            "&hidebroken=true&order=clickcount&reverse=true&limit=20").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try { if (conn.responseCode == 200) mp3Alternative(conn.inputStream.readCapped(300_000), st.name, st.url) else null }
        finally { conn.disconnect() }
    } catch (e: Exception) { null }

    /** Off the main thread. 40 km; where that finds few (the north), 80 km. */
    fun nearby(lat: Double, lon: Double): List<Nearby> {
        fun get(km: Int) = try {
            val conn = URL(nearbyUrl(lat, lon, km)).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
            try { if (conn.responseCode == 200) parseNearby(conn.inputStream.readCapped(500_000), max = 30) else emptyList() }
            finally { conn.disconnect() }
        } catch (e: Exception) { emptyList() }
        var near = checked(get(40))
        // A stop (the drive ended, the screen closed) must not send us looking again: checked() returns
        // what it has when it is interrupted, and the 80 km fallback would start a second round of
        // network work plus stream probes nobody is waiting for any more.
        if (Thread.currentThread().isInterrupted) return near.take(15)
        if (near.size < 8) near = checked(get(80)).ifEmpty { near }
        return near.take(15)
    }

    // ---- the filter: only stations that answer, in a format Android plays

    /**
     * What a stream sends: Android's player plays MP3 and plain AAC; it can't open some AAC+ (HE-AAC,
     * "audio/aacp") streams - Radio Rijnmond AAC+ on 19 Nov. Anything else isn't a radio stream.
     */
    enum class Probe { PLAYS, AAC_PLUS, NO }

    fun judge(contentType: String?): Probe {
        val ct = contentType?.lowercase()?.substringBefore(';')?.trim() ?: return Probe.NO
        return when (ct) {
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg", "audio/aac", "audio/x-aac" -> Probe.PLAYS
            "audio/aacp", "audio/x-aacp", "audio/aac+" -> Probe.AAC_PLUS
            else -> Probe.NO
        }
    }

    /** Checked streams and when: one checked in the last hour isn't checked again (30 connections per look-up). */
    private val probed = java.util.concurrent.ConcurrentHashMap<String, Pair<Probe, Long>>()
    private const val PROBE_KEEP_MS = 60 * 60_000L

    /** [probe], remembered for an hour (a failed answer for 10 minutes - a station can come back). */
    fun probeCached(url: String, now: Long = System.currentTimeMillis(), test: (String) -> Probe = ::probe): Probe {
        probed[url]?.let { (p, at) -> if (now - at < (if (p == Probe.NO) 10 * 60_000L else PROBE_KEEP_MS)) return p }
        return test(url).also { probed[url] = it to now }
    }

    /** Off the main thread: does the stream answer (within 4 s), and with what. Reads a couple of KB at most. */
    fun probe(url: String): Probe = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000; conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try {
            if (conn.responseCode !in 200..299) Probe.NO
            else judge(conn.contentType).also { if (it != Probe.NO) conn.inputStream.read(ByteArray(2048)) }   // it really sends audio
        } finally { conn.disconnect() }
    } catch (e: Exception) { Probe.NO }

    /**
     * The filter for "near you": every station is tried before it's listed (in parallel, a few seconds
     * in all). AAC+ ones are swapped for the same station's MP3 stream if it has one, else left out;
     * ones that don't answer are left out. [test] / [twin] are swappable for unit tests.
     */
    fun checked(list: List<Nearby>, test: (String) -> Probe = { probeCached(it) }, twin: (Station) -> Station? = ::findMp3): List<Nearby> {
        if (list.isEmpty()) return list
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val jobs = list.map { n ->
                pool.submit<Nearby?> {
                    when (test(n.station.url)) {
                        Probe.PLAYS -> n
                        Probe.AAC_PLUS -> twin(n.station)?.takeIf { test(it.url) == Probe.PLAYS }?.let { n.copy(station = it.copy(name = n.station.name)) }
                        Probe.NO -> null
                    }
                }
            }
            // A stop is not "the station did not answer": runCatching would swallow the interrupt (and
            // clear the flag) and the caller would keep waiting for every remaining job.
            val kept = mutableListOf<Nearby>()
            for (job in jobs) {
                val n = try {
                    job.get(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    null
                }
                if (n != null) kept += n
            }
            val dropped = list.size - kept.size
            if (dropped > 0) DriveLog.log("RADIO: near you - $dropped of ${list.size} left out (no answer, or a format Android can't play)")
            return kept.distinctBy { it.station.url }
        } finally { pool.shutdownNow() }
    }

    /** Streams that didn't play but whose MP3 stream did (this session): used instead, everywhere. */
    val substitutes: MutableMap<String, Station> = java.util.concurrent.ConcurrentHashMap()

    /** Streams that failed this session: not offered again until the app restarts. */
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun searchUrl(query: String) = "https://all.api.radio-browser.info/json/stations/search?name=" +
        URLEncoder.encode(query.trim(), "UTF-8") + "&hidebroken=true&order=votes&reverse=true&limit=15"

    /** Off the main thread. Empty when nothing is found or there's no signal. */
    fun search(query: String): List<Station> = try {
        val conn = URL(searchUrl(query)).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try { if (conn.responseCode == 200) parseSearch(conn.inputStream.readCapped(300_000)) else emptyList() }
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
    /**
     * The song title costs a second connection (NPO Radio 2: 62 KB a check - 5 MB an hour at every 45 s).
     * So: once a minute, and only while someone can see it (Drive Mode in front, or the car's media screen).
     */
    private const val META_EVERY_MS = 60_000L
    private const val META_IDLE_CHECK_MS = 10_000L
    /** Set by the app: is a screen showing the song title right now? */
    @Volatile var titleVisible: () -> Boolean = { true }
    /** A stream that hasn't started after this long is dead (or no signal): try its MP3 stream / say so. */
    private const val CONNECT_TIMEOUT_MS = 12_000L

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
                AudioManager.AUDIOFOCUS_LOSS -> stop(if (switchingToMusic()) "you switched back to music" else "another app started playing")
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
        everPlayed = false; retries = 0; triedAlternativeFor = null; switchStartedAt = 0L
        // A stream that didn't play earlier but whose MP3 stream did: straight to that one.
        val use = Radio.substitutes[st.url]?.copy(name = st.name) ?: st
        station = use; state = State.CONNECTING; problem = ""; nowPlaying = null
        Prefs(app).radioLast = use.encode()
        DriveLog.log("RADIO: tuning in to ${st.name}")
        changed()
        connect(app, use)
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
            main.postDelayed({ if (player === mp && state == State.CONNECTING) dropped(app, st, "no answer within ${CONNECT_TIMEOUT_MS / 1000} s") }, CONNECT_TIMEOUT_MS)
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
    /** The station we're trying the MP3 stream of (once per tune-in). */
    private var triedAlternativeFor: String? = null

    private fun giveUp(st: Radio.Station, why: String) {
        // Never started: Android's player can't open some AAC+ streams - try the same station's MP3 stream once.
        val app = appCtx
        if (!everPlayed && app != null && triedAlternativeFor != st.url) {
            triedAlternativeFor = st.url
            state = State.CONNECTING; problem = "trying another stream"; changed()
            Thread({
                val alt = Radio.findMp3(st)
                main.post {
                    if (station != st || state != State.CONNECTING) return@post   // you picked something else meanwhile
                    if (alt == null) { triedAlternativeFor = null; finallyGiveUp(st, why); return@post }
                    DriveLog.log("RADIO: ${st.name} - $why - trying its MP3 stream")
                    val use = alt.copy(name = st.name)
                    Radio.substitutes[st.url] = use      // the list keeps showing it; taps go to the MP3 stream
                    releasePlayer(); retries = 0
                    station = use; problem = ""
                    triedAlternativeFor = use.url
                    Prefs(app).radioLast = use.encode()
                    connect(app, use)
                }
            }, "radio-alt").start()
            return
        }
        finallyGiveUp(st, why)
    }

    private fun finallyGiveUp(st: Radio.Station, why: String) {
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
        // Nobody looking: don't spend data on it - look again shortly (the title appears within ~10 s of looking).
        if (!runCatching { titleVisible() }.getOrDefault(true)) {
            main.postDelayed({ if (station == st && state == State.PLAYING && player != null) pollMeta(st) }, META_IDLE_CHECK_MS)
            return
        }
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

    /**
     * Radio off and YouTube Music back where it was - one tap, no gap: the radio keeps playing until YouTube
     * Music takes the sound (that ends it). From cold YouTube Music can take ~10 s to start, so the radio is
     * only ended without it after [SWITCH_MS]. Meanwhile further taps are ignored: a second tap used to flip
     * straight back to the radio (log 20 Nov 18:20).
     */
    fun backToMusic(ctx: Context) {
        if (switchingToMusic()) { DriveLog.log("RADIO: already switching to YouTube Music - tap ignored"); return }
        DriveLog.log("RADIO: switching back to YouTube Music")
        val svc = DriveMonitorService.instance
        if (svc == null) { stop("you switched back to music"); return }
        val started = android.os.SystemClock.elapsedRealtime()
        switchStartedAt = started
        changed()
        svc.playByUser("Radio tab")
        main.postDelayed({
            if (switchStartedAt != started) return@postDelayed
            switchStartedAt = 0L
            if (on) stop("YouTube Music didn't start - you switched back to music") else changed()
        }, SWITCH_MS)
    }

    /** How long the radio waits for YouTube Music to take over after you tap Music. */
    const val SWITCH_MS = 12_000L
    @Volatile private var switchStartedAt = 0L
    /** You tapped Music and YouTube Music hasn't taken over yet (the button shows it's busy). */
    fun switchingToMusic(now: Long = android.os.SystemClock.elapsedRealtime()) =
        switchPending(switchStartedAt, now, on)
    /** Pure check, for tests: a switch started at [startedAt] is still under way at [now] while the radio is on. */
    fun switchPending(startedAt: Long, now: Long, radioOn: Boolean) =
        radioOn && startedAt > 0 && now - startedAt in 0 until SWITCH_MS

    /** When it last went off, and whether something else took the sound (not you) - for "was it on?". */
    @Volatile var stoppedAt = 0L; private set
    @Volatile var stoppedByOthers = false; private set
    /** On now, or taken off by something else (Android Auto dropping) in the last [ms]. */
    fun onOrJustLost(ms: Long = 5_000) = on || (stoppedByOthers && android.os.SystemClock.elapsedRealtime() - stoppedAt < ms)

    fun stop(why: String) {
        switchStartedAt = 0L
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
