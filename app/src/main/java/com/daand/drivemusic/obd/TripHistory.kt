package com.daand.drivemusic.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One finished drive. */
data class TripRecord(
    val at: Long, val minutes: Int, val km: Double, val litres: Double,
    val score: Int?, val maxCoolant: Int?, val codes: List<String> = emptyList(),
    val health: HealthSample? = null,
) {
    val per100: Double? get() = if (km >= 1 && litres > 0) litres / km * 100 else null
}

/**
 * Every drive with car data, kept on the phone (last 500) for the Trips page, costs, range and the
 * service reminder. Pure list logic + JSON (unit tested); file I/O is a thin layer.
 */
object TripHistory {
    private const val FILE = "trips.json"
    private const val MAX = 500

    fun toJson(list: List<TripRecord>): String = JSONArray().apply {
        list.takeLast(MAX).forEach { t ->
            put(JSONObject().put("at", t.at).put("min", t.minutes).put("km", t.km).put("l", t.litres)
                .put("score", t.score ?: -1).put("maxC", t.maxCoolant ?: -999).put("codes", t.codes.joinToString(","))
                .apply { t.health?.takeIf { !it.isEmpty }?.let { put("h", it.toJson()) } })
        }
    }.toString()

    fun fromJson(json: String): List<TripRecord> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            TripRecord(o.optLong("at"), o.optInt("min"), o.optDouble("km", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                o.optDouble("l", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                o.optInt("score", -1).takeIf { it in 0..100 }, o.optInt("maxC", -999).takeIf { it > -100 },
                o.optString("codes").split(',').filter { it.isNotBlank() }, HealthSample.fromJson(o.optJSONObject("h")))
        }
    } catch (e: Exception) { emptyList() }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized fun load(ctx: Context): List<TripRecord> = file(ctx).takeIf { it.exists() }?.let { fromJson(it.readText()) } ?: emptyList()

    @Synchronized fun add(ctx: Context, t: TripRecord) {
        val all = load(ctx) + t
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(toJson(all))
        tmp.renameTo(file(ctx))
    }

    @Synchronized fun clear(ctx: Context) { file(ctx).delete() }

    class Totals(val trips: Int, val km: Double, val litres: Double, val minutes: Int) {
        val per100: Double? get() = if (km >= 1 && litres > 0) litres / km * 100 else null
    }

    fun totals(list: List<TripRecord>, since: Long = 0): Totals {
        val l = list.filter { it.at >= since }
        return Totals(l.size, l.sumOf { it.km }, l.sumOf { it.litres }, l.sumOf { it.minutes })
    }

    /** Your typical consumption: the last ~300 km of trips with fuel data (more stable than one trip). */
    fun typicalPer100(list: List<TripRecord>): Double? {
        var km = 0.0; var l = 0.0
        for (t in list.reversed()) { if (t.litres <= 0) continue; km += t.km; l += t.litres; if (km >= 300) break }
        return if (km >= 5) l / km * 100 else null
    }

    fun kmSince(list: List<TripRecord>, since: Long) = list.filter { it.at >= since }.sumOf { it.km }
}

/** Range left on the current tank. Pure, unit tested. */
object Range {
    /** km left, or null if fuel level / consumption aren't known. */
    fun km(fuelPct: Int?, tankLitres: Double, per100: Double?): Int? {
        if (fuelPct == null || per100 == null || per100 < 2 || per100 > 30) return null
        return (fuelPct / 100.0 * tankLitres / per100 * 100).toInt()
    }
}
