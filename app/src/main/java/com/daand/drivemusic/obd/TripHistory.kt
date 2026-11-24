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
    /** What a litre cost when this drive happened (0 = an older record, priced with today's price). */
    val price: Double = 0.0,
    /** Which scoring rules [score] was made with (1 = before 17 Nov 2026: hard moments only). Scores of
     *  different versions aren't comparable - see [TripHistory.averageScore]. */
    val scoreVersion: Int = Trip.SCORE_VERSION,
) {
    val per100: Double? get() = if (km >= 1 && litres > 0) litres / km * 100 else null

    /** The cost of this drive: yesterday's drive shouldn't get dearer because fuel went up today. */
    fun cost(todaysPrice: Double): Double = litres * (if (price > 0) price else todaysPrice)

    /** The price this drive was priced with, for showing per trip. */
    fun priceUsed(todaysPrice: Double): Double = if (price > 0) price else todaysPrice
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
                .put("p", t.price).put("sv", t.scoreVersion)
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
                o.optString("codes").split(',').filter { it.isNotBlank() }, HealthSample.fromJson(o.optJSONObject("h")),
                o.optDouble("p", 0.0).takeIf { it.isFinite() && it in 0.0..20.0 } ?: 0.0,
                o.optInt("sv", 1))
        }
    } catch (e: Exception) { emptyList() }

    /** Average driving score over drives scored with today's rules only (older scores used another ruler). */
    fun averageScore(list: List<TripRecord>): Int? =
        list.filter { it.scoreVersion == Trip.SCORE_VERSION }.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** The last load, kept while the file is unchanged: pages read it on every redraw (500 drives of JSON). */
    private var cached: Triple<Long, Long, List<TripRecord>>? = null

    @Synchronized fun load(ctx: Context): List<TripRecord> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val (mod, len) = f.lastModified() to f.length()
        cached?.let { (m, l, list) -> if (m == mod && l == len) return list }
        return fromJson(f.readText()).also { cached = Triple(mod, len, it) }
    }

    @Synchronized fun add(ctx: Context, t: TripRecord) {
        val all = load(ctx) + t
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(toJson(all))
        tmp.renameTo(file(ctx))
        cached = null   // written by us: never trust the file's timestamp alone
    }

    @Synchronized fun clear(ctx: Context) { file(ctx).delete(); cached = null; clearDraft(ctx) }

    // ---------------------------------------------------------------- the drive being driven

    private const val DRAFT = "trip-draft.json"
    private fun draftFile(ctx: Context) = File(ctx.filesDir, DRAFT)

    /**
     * While you drive, the trip only exists in memory ([ObdManager]'s poll thread). If the process is
     * killed - low memory, a reboot, the adapter yanked at the wrong moment - the drive was simply gone.
     * This keeps a small copy on disk every half minute, with [TripRecord.at] set to when the drive
     * started so a saved version of it can be recognised later.
     */
    @Synchronized fun saveDraft(ctx: Context, t: TripRecord, now: Long = System.currentTimeMillis()) {
        runCatching {
            val tmp = File(ctx.filesDir, "$DRAFT.tmp")
            tmp.writeText(JSONObject().put("at", t.at).put("min", t.minutes).put("km", t.km).put("l", t.litres)
                .put("score", t.score ?: -1).put("maxC", t.maxCoolant ?: -999)
                .put("codes", t.codes.joinToString(",")).put("p", t.price).put("sv", t.scoreVersion).put("upd", now)
                // The health sample belongs to the drive: without it a drive that survives a killed
                // process comes back without its charging/idle/warm-up numbers, for good.
                .apply { t.health?.takeIf { !it.isEmpty }?.let { put("h", it.toJson()) } }.toString())
            tmp.renameTo(draftFile(ctx))
        }
    }

    @Synchronized fun clearDraft(ctx: Context) { draftFile(ctx).delete() }

    /**
     * Turns a draft left behind by a killed process into a real trip. Returns it if that happened.
     * A draft younger than [staleAfterMs] is left alone (the drive is probably still going), and one
     * whose drive already made it into the history is dropped rather than counted twice.
     */
    @Synchronized fun recoverDraft(ctx: Context, now: Long = System.currentTimeMillis(),
                                  staleAfterMs: Long = 90_000L): TripRecord? {
        val f = draftFile(ctx)
        if (!f.exists()) return null
        val rec = try {
            val o = JSONObject(f.readText())
            val updatedAt = o.optLong("upd", 0L)
            if (updatedAt > 0 && now - updatedAt < staleAfterMs) return null   // still driving
            TripRecord(o.optLong("at"), o.optInt("min"),
                o.optDouble("km", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                o.optDouble("l", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                o.optInt("score", -1).takeIf { it in 0..100 }, o.optInt("maxC", -999).takeIf { it > -100 },
                // A draft from before this existed has no "h": fromJson(null) is null, so it still
                // recovers - just without a health sample, as before.
                o.optString("codes").split(',').filter { it.isNotBlank() }, HealthSample.fromJson(o.optJSONObject("h")),
                o.optDouble("p", 0.0).takeIf { it.isFinite() && it in 0.0..20.0 } ?: 0.0, o.optInt("sv", 1))
        } catch (e: Exception) {
            clearDraft(ctx); return null
        }
        clearDraft(ctx)
        if (rec.km < 0.3) return null                       // barely moved: not worth keeping
        if (load(ctx).any { it.at >= rec.at }) return null  // its drive did get saved after all
        add(ctx, rec)
        return rec
    }

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

    /**
     * The odometer now: what the dashboard said when you typed it in, plus the drives since (with the
     * adapter). Null when never typed in. Drives without the adapter aren't counted - sync now and then.
     */
    fun odometer(list: List<TripRecord>, odoKm: Int, odoAt: Long): Int? =
        if (odoKm <= 0 || odoAt <= 0) null else odoKm + Math.round(kmSince(list, odoAt)).toInt()
}

/** Range left on the current tank. Pure, unit tested. */
object Range {
    /** km left, or null if fuel level / consumption aren't known. */
    fun km(fuelPct: Int?, tankLitres: Double, per100: Double?): Int? {
        if (fuelPct == null || per100 == null || per100 < 2 || per100 > 30) return null
        return (fuelPct / 100.0 * tankLitres / per100 * 100).toInt()
    }
}
