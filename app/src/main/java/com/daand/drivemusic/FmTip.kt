package com.daand.drivemusic

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Best FM frequency here" for an FM transmitter (the Senzo screen sends Android Auto's sound to the
 * car radio over FM). A real station on or next to that frequency means crackle and hiss; the best
 * pick is a frequency no transmitter near your drives uses.
 *
 * The transmitters: every Dutch FM frequency with its station and place (radios.nl, Nov 2026), places
 * located once with OpenStreetMap - shipped in the app (assets/fm_transmitters.tsv), nothing is looked
 * up while you drive and your position never leaves the phone. Pure logic, unit tested.
 */
object FmTip {
    const val LOW = 87.5
    const val HIGH = 108.0

    data class Tx(val mhz: Double, val station: String, val place: String, val lat: Double, val lon: Double)

    /** A frequency's verdict: [score] 0 = nothing near; [clashes] = the stations in the way, nearest first. */
    data class Verdict(val mhz: Double, val score: Double, val clashes: List<Pair<Tx, Int>>)

    fun valid(mhz: Double) = mhz in LOW..HIGH

    /** "107.9" / "107,9" -> 107.9, on the 0.1 MHz raster; null when it isn't an FM frequency. */
    fun parseMhz(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.let { (it * 10).roundToInt() / 10.0 }?.takeIf { valid(it) }

    fun parse(tsv: String): List<Tx> = tsv.lineSequence().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 5) null else {
            val f = p[0].toDoubleOrNull(); val lat = p[3].toDoubleOrNull(); val lon = p[4].toDoubleOrNull()
            if (f == null || lat == null || lon == null || !valid(f)) null else Tx(f, p[1], p[2], lat, lon)
        }
    }.toList()

    @Volatile private var cached: List<Tx>? = null
    fun transmitters(ctx: Context): List<Tx> = cached ?: runCatching {
        parse(ctx.assets.open("fm_transmitters.tsv").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList()).also { cached = it }

    /**
     * How far a site carries. Places with many stations are the big masts (Hoogersmilde, IJsselstein,
     * Wieringerwerf...) - they reach far; a single local station only covers its town.
     */
    fun reachKm(stationsAtPlace: Int) = when { stationsAtPlace >= 8 -> 80.0; stationsAtPlace >= 4 -> 50.0; else -> 25.0 }

    /** Nearby on the dial interferes too, less the further away it is (a car radio's selectivity). */
    private fun channel(delta: Double) = when { delta < 0.05 -> 1.0; delta < 0.15 -> 0.5; delta < 0.25 -> 0.2; delta < 0.35 -> 0.05; else -> 0.0 }

    /** Signal strength guess: strong inside the reach, fading out to nothing at twice the reach. */
    private fun strength(km: Double, reach: Double) = when {
        km <= reach -> 1.0 - 0.5 * km / reach
        km <= 2 * reach -> 0.5 * (1 - (km - reach) / reach)
        else -> 0.0
    }

    /** The worst of [where] (your position, Home, Work...): a frequency must be quiet everywhere you drive. */
    fun verdict(mhz: Double, where: List<Pair<Double, Double>>, txs: List<Tx>): Verdict {
        val perPlace = txs.groupingBy { it.place }.eachCount()
        var worst = 0.0
        val clashes = HashMap<Tx, Int>()
        for ((lat, lon) in where) {
            var score = 0.0
            for (t in txs) {
                val c = channel(abs(t.mhz - mhz)); if (c == 0.0) continue
                val km = FuelPrices.distanceKm(lat, lon, t.lat, t.lon)
                val s = strength(km, reachKm(perPlace[t.place] ?: 1)); if (s == 0.0) continue
                score += c * s
                if (c * s >= 0.1) clashes[t] = minOf(clashes[t] ?: Int.MAX_VALUE, km.roundToInt())
            }
            worst = maxOf(worst, score)
        }
        return Verdict(mhz, worst, clashes.entries.sortedBy { it.value }.map { it.key to it.value })
    }

    /** The [n] quietest frequencies for [where], at least 0.4 MHz apart (so they're real alternatives). */
    fun best(where: List<Pair<Double, Double>>, txs: List<Tx>, n: Int = 3): List<Verdict> {
        val all = (876..1079).map { verdict(it / 10.0, where, txs) }.sortedWith(compareBy({ it.score }, { -edge(it.mhz) }))
        val out = mutableListOf<Verdict>()
        for (v in all) { if (out.none { abs(it.mhz - v.mhz) < 0.35 }) out += v; if (out.size == n) break }
        return out
    }

    /** Tie-break: the ends of the band are used least (fewer surprises on trips elsewhere). */
    private fun edge(mhz: Double) = maxOf(90.0 - mhz, mhz - 105.5, 0.0)

    fun word(score: Double, dutch: Boolean) = when {
        score < 0.05 -> if (dutch) "vrij" else "clear"
        score < 0.3 -> if (dutch) "goed" else "good"
        score < 0.7 -> if (dutch) "wat ruis mogelijk" else "some crackle likely"
        else -> if (dutch) "bezet" else "busy"
    }

    fun describe(v: Verdict, dutch: Boolean): String = "%.1f MHz: %s".format(java.util.Locale.ROOT, v.mhz, word(v.score, dutch)) +
        (v.clashes.firstOrNull()?.let { (t, km) -> " - " + "%s %.1f (%s, %d km)".format(java.util.Locale.ROOT, t.station.ifBlank { "?" }, t.mhz, t.place, km) } ?: "")
}
