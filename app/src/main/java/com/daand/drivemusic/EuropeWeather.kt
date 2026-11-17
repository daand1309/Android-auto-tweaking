package com.daand.drivemusic

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The third and fourth weather sources, for all of Europe: tried in turn when Open-Meteo and (in
 * NL/BE) Buienradar don't answer, so the greeting is never without weather because one service is
 * down. Parsing is pure and unit tested; [MetNorway.fetch] / [Wttr.fetch] touch the network (off the
 * main thread). Both are asked for the place rounded to ~10 km, like Open-Meteo.
 */

/**
 * MET Norway (the Norwegian weather institute, behind yr.no): free for apps that identify themselves
 * with a contact - the app's name and its GitHub page (no e-mail, nothing about you).
 */
object MetNorway {
    const val USER_AGENT = "DriveMusic/1.0 https://github.com/daand1309/Android-auto-tweaking"
    private const val AHEAD_HOURS = 18

    fun url(lat: Double, lon: Double) =
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${Weather.round(lat)}&lon=${Weather.round(lon)}"

    /** "lightrainshowers_day" -> a WMO weather code like the rest of the app speaks. */
    fun code(symbol: String): Int {
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> 95
            "sleet" in s -> 66
            "snow" in s -> 71
            "showers" in s && "heavy" in s -> 82
            "showers" in s -> 80
            "heavyrain" in s -> 65
            "lightrain" in s -> 61
            "rain" in s -> 63
            s == "fog" -> 45
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            else -> 3
        }
    }

    fun isDay(symbol: String, fallback: Boolean) = when {
        symbol.endsWith("_night") -> false
        symbol.endsWith("_day") || symbol.endsWith("_polartwilight") -> true
        else -> fallback
    }

    /**
     * Now = the first step; today's low and high over the next [AHEAD_HOURS] hours; "chance of rain" =
     * the share of those hours with rain (MET gives no probability for the Netherlands).
     */
    fun parse(json: String): GreetingText.WeatherNow? = try {
        val steps = JSONObject(json).getJSONObject("properties").getJSONArray("timeseries")
        val list = (0 until minOf(steps.length(), AHEAD_HOURS)).map { steps.getJSONObject(it).getJSONObject("data") }
        require(list.isNotEmpty())
        fun temp(d: JSONObject) = d.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            .also { require(it.isFinite() && it in -60.0..60.0) }
        fun mm(d: JSONObject) = d.optJSONObject("next_1_hours")?.optJSONObject("details")?.optDouble("precipitation_amount")
            ?.takeIf { it.isFinite() && it >= 0 }
        val now = list[0]
        val details = now.getJSONObject("instant").getJSONObject("details")
        val symbol = now.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
        val temps = list.map { temp(it) }
        val hours = list.mapNotNull { mm(it) }
        val fog = details.optDouble("fog_area_fraction").takeIf { it.isFinite() } ?: 0.0
        GreetingText.WeatherNow(
            tempNow = temps[0],
            code = if (fog >= 50) 45 else code(symbol),
            windKmh = details.optDouble("wind_speed").takeIf { it.isFinite() && it in 0.0..80.0 }?.times(3.6) ?: 0.0,
            tempMin = temps.min(), tempMax = temps.max(),
            rainChance = if (hours.isEmpty()) 0 else hours.count { it >= 0.1 } * 100 / hours.size,
            precipMm = mm(now) ?: 0.0,
            gustsKmh = details.optDouble("wind_speed_of_gust").takeIf { it.isFinite() && it in 0.0..90.0 }?.times(3.6) ?: 0.0,
            visibilityM = if (fog >= 50) 500.0 else null,
            isDay = isDay(symbol, fallback = true),
        )
    } catch (e: Exception) { null }

    fun fetch(lat: Double, lon: Double): GreetingText.WeatherNow? = parse(EuropeHttp.get(url(lat, lon), USER_AGENT))
}

/** wttr.in: a free community service for the whole world - no key, no account, no contact. */
object Wttr {
    fun url(lat: Double, lon: Double) = "https://wttr.in/${Weather.round(lat)},${Weather.round(lon)}?format=j1"

    /** World Weather Online condition codes (what wttr.in reports) -> WMO codes. */
    fun code(c: Int): Int = when (c) {
        113 -> 0
        116 -> 2
        119, 122 -> 3
        143, 248, 260 -> 45
        263, 266, 293, 296 -> if (c == 263 || c == 266) 51 else 61
        176, 353 -> 80
        356 -> 81
        359 -> 82
        299, 302 -> 63
        305, 308 -> 65
        179, 182, 185, 281, 284, 311, 314, 317, 320, 350, 362, 365, 374, 377 -> 66
        227, 230, 323, 326, 329, 332, 335, 338, 368, 371 -> 71
        200, 386, 389, 392, 395 -> 95
        else -> 3
    }

    /** "04:45 PM" -> minutes since midnight. */
    private fun minutes(t: String): Int? = Regex("""(\d{1,2}):(\d{2})\s*([AP]M)""").find(t.trim())?.destructured?.let { (h, m, ap) ->
        (h.toInt() % 12 + if (ap == "PM") 12 else 0) * 60 + m.toInt()
    }

    /** [nowMinutes] = local time of day in minutes (for day/night). */
    fun parse(json: String, nowMinutes: Int): GreetingText.WeatherNow? = try {
        val o = JSONObject(json)
        val cur = o.getJSONArray("current_condition").getJSONObject(0)
        val today = o.getJSONArray("weather").getJSONObject(0)
        val temp = cur.getString("temp_C").toDouble().also { require(it.isFinite() && it in -60.0..60.0) }
        val hourly = today.optJSONArray("hourly")
        val hours = (0 until (hourly?.length() ?: 0)).map { hourly!!.getJSONObject(it) }
        val astro = today.optJSONArray("astronomy")?.optJSONObject(0)
        val rise = astro?.optString("sunrise")?.let { minutes(it) }
        val set = astro?.optString("sunset")?.let { minutes(it) }
        fun num(o: JSONObject, k: String) = o.optString(k).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        GreetingText.WeatherNow(
            tempNow = temp,
            code = code(cur.optString("weatherCode").toIntOrNull() ?: 0),
            windKmh = num(cur, "windspeedKmph")?.coerceAtMost(300.0) ?: 0.0,
            tempMin = today.optString("mintempC").toDoubleOrNull() ?: temp,
            tempMax = today.optString("maxtempC").toDoubleOrNull() ?: temp,
            rainChance = hours.mapNotNull { it.optString("chanceofrain").toIntOrNull() }.maxOrNull()?.coerceIn(0, 100) ?: 0,
            precipMm = num(cur, "precipMM")?.coerceAtMost(200.0) ?: 0.0,
            gustsKmh = hours.mapNotNull { num(it, "WindGustKmph") }.maxOrNull()?.coerceAtMost(300.0) ?: 0.0,
            visibilityM = num(cur, "visibility")?.times(1000),
            isDay = if (rise != null && set != null) nowMinutes in rise until set else true,
            nowTime = "%02d:%02d".format(nowMinutes / 60, nowMinutes % 60),
            sunset = set?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "",
        )
    } catch (e: Exception) { null }

    fun fetch(lat: Double, lon: Double): GreetingText.WeatherNow? {
        val now = java.time.LocalTime.now()
        return parse(EuropeHttp.get(url(lat, lon), "DriveMusic"), now.hour * 60 + now.minute)
    }
}

private object EuropeHttp {
    fun get(url: String, userAgent: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally {
            conn.disconnect()
        }
    }
}
