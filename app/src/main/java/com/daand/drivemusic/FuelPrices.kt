package com.daand.drivemusic

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fuel prices for the area you're in, from the ANWB's public station database: every fuel station in
 * the Netherlands with its own price per fuel type, no key and no account. Used for the cost per trip
 * (the manual price stays as the fallback).
 *
 * One request covers a box around you (~15 km) - the location is rounded first, so the app never sends
 * a precise position for this. Parsing and the choice of price are pure and unit tested; only
 * [fetch] touches the network, and it is always called off the main thread.
 */
object FuelPrices {
    private const val ENDPOINT = "https://api.anwb.nl/routing/points-of-interest/v3/all"

    /** Prices move a few times a day; one request per open (at most every few hours) is plenty. */
    const val FRESH_MS = 6 * 60 * 60 * 1000L

    /** A quote older than this is too stale to base costs on. */
    const val USABLE_MS = 7 * 24 * 60 * 60 * 1000L

    /** The fuels the app knows how to ask for (ANWB's own codes) and their names. */
    enum class Fuel(val code: String, val en: String, val nl: String) {
        EURO95("EURO95", "Euro 95 (E10)", "Euro 95 (E10)"),
        DIESEL("DIESEL", "Diesel (B7)", "Diesel (B7)"),
        LPG("AUTOGAS", "LPG", "LPG");

        companion object {
            fun of(code: String?): Fuel = values().firstOrNull { it.code == code } ?: EURO95
        }
    }

    /** One station with whatever prices it publishes. */
    data class Station(
        val name: String,
        val place: String,
        val lat: Double,
        val lon: Double,
        /** € per litre, null = this station doesn't sell it (or didn't report a believable price). */
        val prices: Map<Fuel, Double> = emptyMap(),
    )

    /** The price to work with, and where it came from. */
    data class Quote(
        /** € per litre. */
        val euro: Double,
        /** How many stations the price is based on. */
        val stations: Int,
        /** Nearest of those, for the "where" line. */
        val nearestName: String,
        val nearestPlace: String,
        val nearestKm: Double,
        val fuel: Fuel,
    )

    // ------------------------------------------------------------------ parsing (unit tested)

    /** Believable pump price per litre; anything else is a garbled or missing value. */
    private fun sane(v: Double) = v.takeIf { it.isFinite() && it in 0.3..5.0 }

    fun parseAll(json: String): List<Station> = try {
        val arr = JSONObject(json).optJSONArray("value") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optJSONObject("coordinates") ?: return@mapNotNull null
            val lat = c.optDouble("latitude", Double.NaN)
            val lon = c.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val priceArr = o.optJSONArray("prices") ?: JSONArray()
            val byCode = HashMap<String, Double>()
            for (j in 0 until priceArr.length()) {
                val p = priceArr.optJSONObject(j) ?: continue
                sane(p.optDouble("value", Double.NaN))?.let { byCode[p.optString("fuelType")] = it }
            }
            Station(
                name = o.optString("title").trim().take(48),
                place = o.optJSONObject("address")?.optString("city")?.trim()?.take(32).orEmpty(),
                lat = lat, lon = lon,
                prices = Fuel.values().mapNotNull { f -> byCode[f.code]?.let { f to it } }.toMap(),
            ).takeIf { it.prices.isNotEmpty() }
        }
    } catch (e: Exception) {
        emptyList()   // a format change or a truncated answer: no prices, costs keep working on the manual value
    }

    // ------------------------------------------------------------------ choosing a price (unit tested)

    /** Great-circle distance in km - close enough for "the station around the corner". */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /**
     * The price to use for costs: the median of the stations around you that sell [fuel]. A median
     * ignores one cheap motorway-adjacent outlier (and one expensive one), so the number reflects what
     * you'll likely pay. Returns null when there is nothing usable in range.
     */
    fun quote(prices: List<Station>, lat: Double, lon: Double, fuel: Fuel, radiusKm: Double = 10.0): Quote? {
        val inRange = prices.mapNotNull { s ->
            val p = s.prices[fuel] ?: return@mapNotNull null
            val km = distanceKm(lat, lon, s.lat, s.lon)
            if (km > radiusKm) null else Triple(s, p, km)
        }
        if (inRange.isEmpty()) return null
        val sorted = inRange.sortedBy { it.second }
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid].second
            else (sorted[mid - 1].second + sorted[mid].second) / 2
        val nearest = inRange.minByOrNull { it.third }!!
        return Quote(
            euro = Math.round(median * 1000) / 1000.0,   // to the tenth of a cent, like the pumps show
            stations = inRange.size,
            nearestName = nearest.first.name,
            nearestPlace = nearest.first.place,
            nearestKm = Math.round(nearest.third * 10) / 10.0,
            fuel = fuel,
        )
    }

    /** Bounding box (south,west,north,east) around a point, rounded to ~1 km so no precise fix is sent. */
    fun boundingBox(lat: Double, lon: Double, degrees: Double = 0.15): String {
        fun r(v: Double) = Math.round(v * 100) / 100.0
        return "${r(lat - degrees)},${r(lon - degrees)},${r(lat + degrees)},${r(lon + degrees)}"
    }

    fun urlFor(lat: Double, lon: Double): String =
        "$ENDPOINT?type-filter=FUEL_STATION&bounding-box-filter=${boundingBox(lat, lon)}"

    // ------------------------------------------------------------------ network (call off the main thread)

    /** The quote for where you are now, or null (no signal, no stations, or the source changed). */
    fun fetch(lat: Double, lon: Double, fuel: Fuel): Quote? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val json = httpGet(urlFor(lat, lon))
        val stations = parseAll(json)
        if (stations.isEmpty()) {
            DriveLog.log("FUEL: no usable station prices in the answer (${json.length} chars)")
            return null
        }
        val q = quote(stations, lat, lon, fuel) ?: run {
            DriveLog.log("FUEL: ${stations.size} stations nearby, none selling ${fuel.en}")
            return null
        }
        DriveLog.log("FUEL: ${"%.3f".format(java.util.Locale.ROOT, q.euro)} €/L ${fuel.en} " +
            "(median of ${q.stations} within 10 km, nearest ${q.nearestName}, ${q.nearestKm} km)")
        return q
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "DriveMusic")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("fuel service returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(3_000_000) }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ what the app charges for costs

    /**
     * The price per litre to use for costs: the fetched one while it is fresh and automatic lookup is
     * on, else the price you typed in. 0 = nothing known (costs are hidden). Pure, unit tested.
     */
    fun effectivePrice(auto: Boolean, quoted: Double, quotedAt: Long, manual: Double, now: Long): Double = when {
        auto && quoted > 0 && now - quotedAt in 0..USABLE_MS -> quoted
        else -> manual
    }
}
