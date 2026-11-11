package com.daand.drivemusic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** Builds the spoken welcome text. Pure logic, unit tested. */
object GreetingText {

    data class WeatherNow(
        val tempNow: Double,
        val code: Int,
        val windKmh: Double,
        val tempMin: Double,
        val tempMax: Double,
        val rainChance: Int,
        // Road-condition details (defaults = "nothing to report").
        val precipMm: Double = 0.0,
        val snowfallCm: Double = 0.0,
        val gustsKmh: Double = 0.0,
        val visibilityM: Double? = null,
        val isDay: Boolean = true,
        val nowTime: String = "",   // local "HH:mm" at the weather location
        val sunset: String = "",    // local "HH:mm"
    )

    /** Song to announce: "Up first: <title> by <artist>". */
    data class Song(val title: String, val artist: String)

    fun build(
        name: String, dutch: Boolean, cal: Calendar, weather: WeatherNow?, place: String,
        random: Random = Random.Default, roads: Boolean = false, song: Song? = null,
    ): String {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val parts = mutableListOf<String>()

        val who = name.trim()
        parts += if (who.isEmpty()) timeGreeting(hour, dutch) + "." else "${timeGreeting(hour, dutch)}, $who."
        parts += if (dutch) "Welkom terug." else "Welcome back."

        dayLine(day, hour, dutch)?.let { parts += it }
        val pool = if (dutch) LINES_NL else LINES_EN
        pool.shuffled(random).take(2).forEach { parts += it }

        if (weather != null) {
            parts += weatherSentence(weather, place, dutch, withAdvice = !roads)
            if (roads) parts += roadSentence(weather, dutch)
        }
        if (song != null) parts += songSentence(song, dutch, upFirst = true)
        parts += if (dutch) "Ik zet je muziek aan. Goede reis!" else "Starting your music. Have a great drive!"
        return parts.joinToString(" ")
    }

    fun timeGreeting(hour: Int, dutch: Boolean) = when (hour) {
        in 5..11 -> if (dutch) "Goedemorgen" else "Good morning"
        in 12..17 -> if (dutch) "Goedemiddag" else "Good afternoon"
        in 18..22 -> if (dutch) "Goedenavond" else "Good evening"
        else -> if (dutch) "Goedenacht" else "Good night"
    }

    private fun dayLine(day: Int, hour: Int, dutch: Boolean): String? = when {
        hour >= 23 || hour < 5 -> if (dutch) "Het is laat, rij voorzichtig." else "It's late, so drive extra carefully."
        day == Calendar.MONDAY -> if (dutch) "Ah, maandag. We maken er een goede van." else "Ah, Monday. Let's make it a good one."
        day == Calendar.FRIDAY -> if (dutch) "Het is vrijdag. Het weekend zit praktisch al op de passagiersstoel." else "It's Friday. The weekend is practically in the passenger seat."
        day == Calendar.SATURDAY || day == Calendar.SUNDAY -> if (dutch) "Weekendmodus is geactiveerd." else "Weekend mode is activated."
        else -> null
    }

    fun describe(code: Int, dutch: Boolean): String = when (code) {
        0 -> if (dutch) "een heldere lucht" else "clear skies"
        1 -> if (dutch) "het meeste zon" else "mostly clear skies"
        2 -> if (dutch) "wisselende bewolking" else "partly cloudy skies"
        3 -> if (dutch) "bewolkt weer" else "overcast skies"
        45, 48 -> if (dutch) "mist" else "fog"
        51, 53, 55 -> if (dutch) "motregen" else "drizzle"
        56, 57, 66, 67 -> if (dutch) "ijzel" else "freezing rain"
        61 -> if (dutch) "lichte regen" else "light rain"
        63 -> if (dutch) "regen" else "rain"
        65 -> if (dutch) "zware regen" else "heavy rain"
        71, 73, 75, 77 -> if (dutch) "sneeuw" else "snow"
        80, 81, 82 -> if (dutch) "regenbuien" else "rain showers"
        85, 86 -> if (dutch) "sneeuwbuien" else "snow showers"
        95, 96, 99 -> if (dutch) "onweer" else "thunderstorms"
        else -> if (dutch) "wisselend weer" else "mixed weather"
    }

    fun songSentence(song: Song, dutch: Boolean, upFirst: Boolean): String {
        val t = song.title.trim()
        val a = song.artist.trim()
        val what = when {
            a.isEmpty() -> t
            dutch -> "$t van $a"
            else -> "$t by $a"
        }
        return if (dutch) (if (upFirst) "Als eerste: $what." else "Nu speelt: $what.")
        else (if (upFirst) "Up first: $what." else "Now playing: $what.")
    }

    /** Road conditions derived from live weather: ice, snow, fog, heavy rain, gusts and daylight. */
    fun roadSentence(w: WeatherNow, dutch: Boolean): String {
        val issues = mutableListOf<String>()
        val wet = w.precipMm > 0.0 || w.code in 51..67 || w.code in 80..82
        when {
            w.snowfallCm > 0.0 || w.code in 71..77 || w.code in 85..86 ->
                issues += if (dutch) "Er ligt mogelijk sneeuw op de weg, rij rustig" else "there may be snow on the roads, so take it slow"
            w.code in setOf(56, 57, 66, 67) || (w.tempNow <= 1.0 && wet) ->
                issues += if (dutch) "er is kans op ijzel, vooral op bruggen" else "black ice is possible, especially on bridges"
            w.tempNow <= 1.0 ->
                issues += if (dutch) "het vriest bijna, pas op voor gladheid" else "it's close to freezing, so watch out for slippery patches"
        }
        val vis = w.visibilityM
        if ((vis != null && vis < 1000) || w.code in setOf(45, 48)) {
            issues += if (vis != null && vis < 1000) {
                val m = ((vis / 50).roundToInt() * 50).coerceAtLeast(50)
                if (dutch) "het zicht is maar $m meter" else "visibility is only $m metres"
            } else if (dutch) "het is mistig" else "it's foggy"
        }
        if (w.precipMm >= 4.0 || w.code in setOf(65, 82)) {
            issues += if (dutch) "het regent hard, let op voor aquaplaning" else "it's raining hard, so watch out for aquaplaning"
        } else if (wet && issues.isEmpty()) {
            issues += if (dutch) "de wegen zijn nat" else "the roads are wet"
        }
        if (w.gustsKmh >= 60) {
            issues += if (dutch) "er staan windstoten tot ${w.gustsKmh.roundToInt()} kilometer per uur" else "there are gusts up to ${w.gustsKmh.roundToInt()} kilometres per hour"
        }
        val lights = when {
            !w.isDay -> if (dutch) "Het is donker, dus lichten aan." else "It's dark out, so lights on."
            minutesUntil(w.nowTime, w.sunset)?.let { it in 0..90 } == true ->
                if (dutch) "De zon gaat om ${w.sunset} onder." else "The sun sets at ${w.sunset}, so you'll need your lights soon."
            else -> null
        }
        val head = if (issues.isEmpty()) {
            if (dutch) "Wegcondities: droog en goed zicht." else "Road conditions look good: dry with good visibility."
        } else {
            (if (dutch) "Wegcondities: " else "Road conditions: ") + issues.joinToString(", ") + "."
        }
        return if (lights != null) "$head $lights" else head
    }

    /** Minutes from "HH:mm" a to "HH:mm" b on the same day, or null if unparseable. */
    fun minutesUntil(a: String, b: String): Int? {
        fun mins(t: String) = Regex("""(\d{1,2}):(\d{2})""").find(t)?.destructured?.let { (h, m) -> h.toInt() * 60 + m.toInt() }
        val x = mins(a) ?: return null
        val y = mins(b) ?: return null
        return y - x
    }

    fun weatherSentence(w: WeatherNow, place: String, dutch: Boolean, withAdvice: Boolean = true): String {
        val now = w.tempNow.roundToInt()
        val lo = w.tempMin.roundToInt()
        val hi = w.tempMax.roundToInt()
        val desc = describe(w.code, dutch)
        val sb = StringBuilder()
        if (dutch) {
            sb.append(if (place.isNotBlank()) "In $place is het nu $now graden met $desc. " else "Het is nu $now graden met $desc. ")
            sb.append("Vandaag tussen de $lo en $hi graden, met ${w.rainChance} procent kans op regen.")
        } else {
            sb.append(if (place.isNotBlank()) "In $place it's currently $now degrees with $desc. " else "It's currently $now degrees with $desc. ")
            sb.append("Today between $lo and $hi degrees, with a ${w.rainChance} percent chance of rain.")
        }
        if (withAdvice) advice(w, dutch)?.let { sb.append(' ').append(it) }
        return sb.toString()
    }

    private fun advice(w: WeatherNow, dutch: Boolean): String? = when {
        w.code in setOf(56, 57, 66, 67) || w.tempNow <= 1 -> if (dutch) "Let op, het kan glad zijn op de weg." else "Careful, the roads could be slippery."
        w.code in setOf(45, 48) -> if (dutch) "Vergeet je mistlampen niet." else "Don't forget your fog lights."
        w.code in 95..99 -> if (dutch) "Rij rustig, er is onweer." else "Take it easy, there's thunder about."
        w.windKmh >= 50 -> if (dutch) "Het waait flink, houd het stuur stevig vast." else "It's very windy, keep a firm grip on the wheel."
        w.rainChance >= 60 || w.code in 51..82 -> if (dutch) "De ruitenwissers krijgen vandaag wat te doen." else "Your wipers might get a workout today."
        w.tempNow >= 28 -> if (dutch) "Het is warm, zet de airco maar aan." else "It's a hot one, so crank up the air conditioning."
        w.code <= 1 -> if (dutch) "Zonnebril op, het is een mooie dag." else "Sunglasses on, it's a beautiful day."
        else -> null
    }

    val LINES_EN = listOf(
        "All systems are go. Your chariot awaits.",
        "Seatbelt on, sunglasses optional, legend status confirmed.",
        "Traffic has been notified of your arrival.",
        "Today's forecast: a one hundred percent chance of excellent driving.",
        "Mirrors checked? Excellent. Let's roll.",
        "Driving mode: legendary.",
        "Please keep your hands on the wheel and your dance moves to a minimum.",
        "Your playlist has been approved by the committee of good taste.",
        "Reminder: indicators are not optional accessories.",
        "Fuel up on good vibes. Petrol sold separately.",
        "The car missed you. It told me so.",
        "Another day, another excellent drive.",
        "Navigation is ready, the music is ready, and frankly, so am I.",
        "A warm hello to any passengers on board. Especially if it's Mum.",
        "Mum, if you're listening: your son is an excellent driver.",
        "Your personal driving assistant is fully caffeinated and ready to go.",
        "Speed limits are a suggestion. Just kidding. They are the law.",
        "Smooth roads and green lights, that's the plan.",
        "Welcome aboard. The captain has switched on the good music sign.",
        "The engine is purring. It's clearly happy to see you.",
        "Destination: wherever you like. Vibe: immaculate.",
        "Remember to stay hydrated and keep it classy.",
        "I've polished the virtual dashboard just for you.",
        "Ready when you are, boss.",
        "Enjoy the ride. I'll handle the soundtrack.",
    )

    val LINES_NL = listOf(
        "Alle systemen staan klaar. Je koets wacht op je.",
        "Gordel om, zonnebril optioneel, legendestatus bevestigd.",
        "Het verkeer is op de hoogte gebracht van je komst.",
        "De voorspelling van vandaag: honderd procent kans op uitstekend rijden.",
        "Spiegels gecheckt? Top. We gaan.",
        "Rijmodus: legendarisch.",
        "Houd je handen aan het stuur en je dansmoves tot een minimum.",
        "Je afspeellijst is goedgekeurd door de commissie van goede smaak.",
        "Ter herinnering: richtingaanwijzers zijn geen optionele accessoires.",
        "Tank vol met goede vibes. Benzine wordt apart verkocht.",
        "De auto heeft je gemist. Dat heeft hij me zelf verteld.",
        "Weer een dag, weer een uitstekende rit.",
        "De navigatie is klaar, de muziek is klaar, en eerlijk gezegd ben ik dat ook.",
        "Een warme groet aan alle passagiers. Vooral als het mama is.",
        "Mam, als je meeluistert: je zoon is een uitstekende chauffeur.",
        "Je persoonlijke rijassistent heeft koffie gehad en is er klaar voor.",
        "Snelheidslimieten zijn een suggestie. Grapje. Het is de wet.",
        "Gladde wegen en groene lichten, dat is het plan.",
        "Welkom aan boord. De kapitein heeft het lampje voor goede muziek aangezet.",
        "De motor spint. Hij is duidelijk blij je te zien.",
        "Bestemming: waar je maar wilt. Sfeer: perfect.",
        "Blijf gehydrateerd en blijf stijlvol.",
        "Ik heb het virtuele dashboard speciaal voor jou gepoetst.",
        "Klaar wanneer jij het bent, baas.",
        "Geniet van de rit. Ik regel de soundtrack.",
    )
}

/** Weather from Open-Meteo (free, no account or API key). Call off the main thread. */
object Weather {
    /** Coordinates are rounded to ~10 km before being sent, for privacy. */
    fun round(v: Double) = (v * 10).roundToInt() / 10.0

    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${round(lat)}&longitude=${round(lon)}" +
            "&current=temperature_2m,weather_code,wind_speed_10m,wind_gusts_10m,precipitation,snowfall,visibility,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunset" +
            "&forecast_days=1&timezone=auto"

    fun parse(json: String): GreetingText.WeatherNow {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return GreetingText.WeatherNow(
            tempNow = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            tempMin = daily.getJSONArray("temperature_2m_min").getDouble(0),
            tempMax = daily.getJSONArray("temperature_2m_max").getDouble(0),
            rainChance = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
            precipMm = cur.optDouble("precipitation", 0.0),
            snowfallCm = cur.optDouble("snowfall", 0.0),
            gustsKmh = cur.optDouble("wind_gusts_10m", 0.0),
            visibilityM = cur.optDouble("visibility").takeIf { !it.isNaN() },
            isDay = cur.optInt("is_day", 1) == 1,
            nowTime = cur.optString("time").substringAfter('T', ""),
            sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T', "") ?: "",
        )
    }

    fun fetch(lat: Double, lon: Double): GreetingText.WeatherNow = parse(httpGet(url(lat, lon)))

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** Looks up a city name, e.g. "Utrecht". */
    fun findPlace(query: String, dutch: Boolean): Place? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&format=json&language=${if (dutch) "nl" else "en"}")
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "DriveMusic")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("weather service returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } finally {
            conn.disconnect()
        }
    }
}

/** Speaks the welcome through the car speakers, then calls [onDone] (always, even if speech fails). */
class Greeter(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var finished = false
    private var focus: AudioFocusRequest? = null

    fun speak(text: String, dutch: Boolean, onDone: () -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)  // plays through the car like nav prompts
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun finish(reason: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            DriveLog.log("GREETING: $reason")
            focus?.let { audio.abandonAudioFocusRequest(it) }
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            onDone()
        }

        // Never let the greeting block the music for long.
        handler.postDelayed({ finish("timed out - continuing") }, 45_000)

        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) { finish("no text-to-speech engine available"); return@post }
                val locale = if (dutch) Locale("nl", "NL") else Locale.UK
                val res = engine.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    DriveLog.log("GREETING: voice for $locale not installed, using the phone's default voice")
                }
                engine.setAudioAttributes(attrs)
                engine.setSpeechRate(0.95f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { finish("spoken") } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { finish("speech failed") } }
                    override fun onError(id: String?, code: Int) { handler.post { finish("speech failed ($code)") } }
                })
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build().also { audio.requestAudioFocus(it) }
                if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "welcome") != TextToSpeech.SUCCESS) {
                    finish("speech could not start")
                }
            }
        }
    }
}

/** Fetches the weather (if enabled), builds the welcome text and speaks it, then calls [then]. */
object WelcomeAnnouncer {
    fun run(context: Context, song: GreetingText.Song? = null, then: () -> Unit = {}) {
        val ctx = context.applicationContext
        val p = Prefs(ctx)
        val main = Handler(Looper.getMainLooper())
        DriveLog.log("GREETING: preparing")
        Thread {
            val lat = p.weatherLat
            val lon = p.weatherLon
            val weather = when {
                !p.weatherEnabled -> null
                lat.isNaN() || lon.isNaN() -> { DriveLog.log("GREETING: no weather location set"); null }
                else -> try {
                    Weather.fetch(lat, lon)
                } catch (e: Exception) {
                    DriveLog.log("GREETING: weather unavailable (${e.message})"); null
                }
            }
            val text = GreetingText.build(
                p.greetingName, p.greetingDutch, Calendar.getInstance(), weather, p.weatherPlace,
                roads = p.roadConditionsEnabled, song = song.takeIf { p.announceSong },
            )
            p.lastGreetingAt = System.currentTimeMillis()
            main.post {
                DriveLog.log("GREETING: \"$text\"")
                Greeter(ctx).speak(text, p.greetingDutch, then)
            }
        }.start()
    }
}

/** Short "Now playing: X by Y" announcement (used when the song wasn't known before the music started). */
object SongAnnouncer {
    fun announce(context: Context, song: GreetingText.Song, dutch: Boolean) {
        val text = GreetingText.songSentence(song, dutch, upFirst = false)
        DriveLog.log("GREETING: \"$text\"")
        Greeter(context.applicationContext).speak(text, dutch) {}
    }
}
