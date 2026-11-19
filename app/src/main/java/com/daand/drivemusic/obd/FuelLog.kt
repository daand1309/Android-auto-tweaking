package com.daand.drivemusic.obd

/**
 * Fill-ups, seen by the adapter: the fuel level at the start of a drive against where the last drive
 * ended. Litres from the jump and the tank size; the price is the local one the app looked up.
 * Real consumption between fill-ups = fuel gone (level after the last fill-up minus level before this
 * one) / km driven in between - right also when you don't fill to the brim. Pure, unit tested.
 */
object FuelLog {
    /** Fuel level sensors wobble a few points (slopes, sloshing): a fill-up is at least this jump. */
    const val MIN_JUMP = 10
    const val MAX = 50

    data class Fill(val at: Long, val beforePct: Int, val afterPct: Int, val litres: Double, val price: Double) {
        val cost get() = litres * price
        fun encode() = "%d|%d|%d|%.1f|%.3f".format(java.util.Locale.ROOT, at, beforePct, afterPct, litres, price)
    }

    fun decode(s: String): List<Fill> = s.split(';').mapNotNull { e ->
        val p = e.split('|'); if (p.size != 5) return@mapNotNull null
        val at = p[0].toLongOrNull(); val b = p[1].toIntOrNull(); val a = p[2].toIntOrNull(); val l = p[3].toDoubleOrNull(); val pr = p[4].toDoubleOrNull()
        if (at == null || b == null || a == null || l == null || pr == null) null else Fill(at, b, a, l, pr)
    }

    fun encode(list: List<Fill>) = list.takeLast(MAX).joinToString(";") { it.encode() }

    /** A fill-up between two drives, or null (no jump / levels unknown / nonsense). */
    fun detect(lastEndPct: Int?, startPct: Int?, tankLitres: Double, price: Double, at: Long): Fill? {
        if (lastEndPct == null || startPct == null || lastEndPct !in 0..100 || startPct !in 0..100) return null
        val jump = startPct - lastEndPct
        if (jump < MIN_JUMP) return null
        return Fill(at, lastEndPct, startPct, Math.round(jump / 100.0 * tankLitres * 10) / 10.0, price)
    }

    data class Consumption(val per100: Double, val km: Double, val litres: Double)

    /**
     * Real L/100 over the last [tanks] fill-up intervals: fuel gone between consecutive fill-ups (by the
     * level) over the km driven in between ([kmBetween], from the trips). Null with too little data.
     */
    fun consumption(fills: List<Fill>, tankLitres: Double, kmBetween: (Long, Long) -> Double, tanks: Int = 3): Consumption? {
        val pairs = fills.sortedBy { it.at }.zipWithNext().takeLast(tanks)
        var litres = 0.0; var km = 0.0
        for ((a, b) in pairs) {
            val used = (a.afterPct - b.beforePct) / 100.0 * tankLitres
            val d = kmBetween(a.at, b.at)
            if (used > 0 && d >= 50) { litres += used; km += d }    // a short gap says nothing (sensor wobble)
        }
        return if (km < 100) null else Consumption(litres / km * 100, km, litres)
    }

    /** Fuel money per km over those same intervals. */
    fun costPerKm(c: Consumption, price: Double) = if (c.km > 0) c.litres * price / c.km else null
}
