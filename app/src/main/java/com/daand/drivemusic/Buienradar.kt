package com.daand.drivemusic

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.pow

/**
 * The second weather source, for the Netherlands and Belgium (free, no key, no account):
 * - the rain radar: rain per 5 minutes for the next 2 hours, from the real radar - better than a
 *   15-minute model forecast for "rain in 10 minutes". Asked for a place rounded to ~5 km.
 * - the national feed: every KNMI station's current weather plus a 5-day forecast, in one file -
 *   no position is sent at all; the nearest station is picked on the phone. Used when Open-Meteo
 *   doesn't answer.
 * Parsing is pure and unit tested; only [rain] / [current] touch the network (off the main thread).
 */
object Buienradar {
    /** Rain in a 5-minute step: this many mm/h or more (a light shower; below it, a few stray drops). */
    private const val RAIN_MMH = 0.3
    private const val DRY_MMH = 0.1
    /** Look ahead as far as the other forecast does, so "rain in X minutes" means the same thing. */
    private const val AHEAD_MIN = 75

    /** Where the radar reaches (NL and BE, with a margin). Elsewhere it answers "dry" - not true. */
    fun covers(lat: Double, lon: Double) = lat in 49.4..53.8 && lon in 2.4..7.4

    private fun round5km(v: Double) = Math.round(v * 20) / 20.0

    fun rainUrl(lat: Double, lon: Double) =
        "https://gpsgadget.buienradar.nl/data/raintext?lat=${round5km(lat)}&lon=${round5km(lon)}"

    private const val FEED = "https://data.buienradar.nl/2.0/feed/json"

    /** "value|HH:mm" per 5 minutes; value 0-255, mm/h = 10^((value - 109) / 32). */
    private fun mmh(value: Int) = if (value <= 0) 0.0 else 10.0.pow((value - 109) / 32.0)

    /** (minutes until rain, minutes until dry) from the radar text; anything odd = (null, null). */
    fun parseRain(text: String): Pair<Int?, Int?> {
        val steps = text.lines().mapNotNull { l ->
            val v = l.substringBefore('|').trim()
            if (!l.contains('|')) null else v.toIntOrNull()?.takeIf { it in 0..255 }
        }
        if (steps.size < 6) return null to null
        val mm = steps.map { mmh(it) }
        val wetNow = mm[0] >= RAIN_MMH
        val ahead = mm.withIndex().filter { (i, _) -> i * 5 in 1..AHEAD_MIN }
        val rainIn = if (wetNow) null else ahead.firstOrNull { it.value >= RAIN_MMH }?.index?.times(5)
        val dryIn = if (!wetNow) null else ahead.firstOrNull { it.value < DRY_MMH }?.index?.times(5)
        return rainIn to dryIn
    }

    /** Buienradar's (Dutch) description as the weather code the rest of the app speaks (WMO). */
    fun code(description: String): Int {
        val d = description.lowercase()
        return when {
            "onweer" in d -> 95
            "ijzel" in d || "aanvriezend" in d -> 66
            "hagel" in d -> 96
            "sneeuw" in d -> 71
            "zware regen" in d || "veel regen" in d -> 65
            "motregen" in d -> 51
            "lichte regen" in d -> 61
            "bui" in d -> 80
            "regen" in d -> 63
            "mist" in d -> 45
            "onbewolkt" in d || "zonnig" in d || "helder" in d -> 0
            "zwaar bewolkt" in d -> 3
            "half bewolkt" in d || "licht bewolkt" in d || "opklaringen" in d -> 2
            "bewolkt" in d -> 3
            else -> 3
        }
    }

    /** The nearest station's weather and today's forecast, as the app's weather; null if unusable. */
    fun parseFeed(json: String, lat: Double, lon: Double, now: LocalDateTime): GreetingText.WeatherNow? = try {
        val o = JSONObject(json)
        val actual = o.getJSONObject("actual")
        val stations = actual.getJSONArray("stationmeasurements")
        val nearest = (0 until stations.length()).map { stations.getJSONObject(it) }
            .filter { it.optDouble("temperature").let { t -> t.isFinite() && t in -60.0..60.0 } }
            .minByOrNull { FuelPrices.distanceKm(lat, lon, it.optDouble("lat"), it.optDouble("lon")) }
            ?: throw IllegalStateException("no station with a temperature")
        fun ms(key: String) = nearest.optDouble(key).takeIf { it.isFinite() && it in 0.0..80.0 }?.times(3.6) ?: 0.0
        val days = o.getJSONObject("forecast").getJSONArray("fivedayforecast")
        val today: LocalDate = now.toLocalDate()
        val day = (0 until days.length()).map { days.getJSONObject(it) }
            .firstOrNull { runCatching { LocalDateTime.parse(it.getString("day")).toLocalDate() == today }.getOrDefault(false) }
            ?: days.getJSONObject(0)
        fun mid(a: String, b: String) = (day.optDouble(a) + day.optDouble(b)) / 2
        val sunrise = runCatching { LocalDateTime.parse(actual.getString("sunrise")) }.getOrNull()
        val sunset = runCatching { LocalDateTime.parse(actual.getString("sunset")) }.getOrNull()
        val temp = nearest.getDouble("temperature")
        GreetingText.WeatherNow(
            tempNow = temp,
            code = code(nearest.optString("weatherdescription")),
            windKmh = ms("windspeed"),
            tempMin = mid("mintemperatureMin", "mintemperatureMax").takeIf { it.isFinite() } ?: temp,
            tempMax = mid("maxtemperatureMin", "maxtemperatureMax").takeIf { it.isFinite() } ?: temp,
            rainChance = day.optInt("rainChance", 0).coerceIn(0, 100),
            precipMm = nearest.optDouble("precipitation").takeIf { it.isFinite() && it in 0.0..200.0 } ?: 0.0,
            gustsKmh = ms("windgusts"),
            visibilityM = nearest.optDouble("visibility").takeIf { it.isFinite() && it >= 0 },
            isDay = if (sunrise != null && sunset != null) now.isAfter(sunrise) && now.isBefore(sunset) else true,
            nowTime = "%02d:%02d".format(now.hour, now.minute),
            sunset = sunset?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
        )
    } catch (e: Exception) { null }

    /** Radar (minutes until rain / dry), or null outside its reach or when it doesn't answer. */
    fun rain(lat: Double, lon: Double): Pair<Int?, Int?>? =
        if (!covers(lat, lon)) null else parseRain(get(rainUrl(lat, lon), 8_000))

    /** Current weather from the national feed (no position sent), or null. */
    fun current(lat: Double, lon: Double): GreetingText.WeatherNow? =
        if (!covers(lat, lon)) null else parseFeed(get(FEED, 200_000), lat, lon, LocalDateTime.now())

    private fun get(url: String, maxChars: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "DriveMusic")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Buienradar returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(maxChars) }
        } finally {
            conn.disconnect()
        }
    }
}
