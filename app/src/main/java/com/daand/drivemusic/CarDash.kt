package com.daand.drivemusic

import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.FuelMath
import com.daand.drivemusic.obd.TroubleCodes
import com.daand.drivemusic.obd.Trip
import java.util.Locale

/** What the Drive Mode car tab shows: big, glanceable numbers. Pure, unit tested. */
object CarDash {
    const val COLD = 0xFF4FC3F7.toInt()
    const val GOOD = 0xFF32D74B.toInt()
    const val HOT = 0xFFFF453A.toInt()

    private fun f1(x: Double) = "%.1f".format(Locale.ROOT, x)

    /** key -> (text, colour or null for the normal colour). */
    /** Label of the bottom-left tile: range when known, else throttle · load. */
    fun loadLabel(rangeKm: Int?, dutch: Boolean) = if (rangeKm != null) (if (dutch) "Bereik" else "Range") else (if (dutch) "Gas · belasting" else "Throttle · load")

    fun values(s: CarSnapshot?, trip: Trip?, dutch: Boolean, rangeKm: Int? = null): Map<String, Pair<String, Int?>> {
        if (s == null) return emptyMap()
        val out = HashMap<String, Pair<String, Int?>>()
        s.speedKmh?.let { out["speed"] = "$it" to null }
        s.rpm?.let { out["rpm"] = "$it" to null }
        s.coolantC?.let { c -> out["coolant"] = "$c °C" to when { c < 60 -> COLD; c >= 105 -> HOT; else -> GOOD } }
        s.litresPerHour?.let { lph ->
            out["fuel"] = (FuelMath.per100km(lph, s.speedKmh?.toDouble())?.let { "${f1(it)} L/100" } ?: "${f1(lph)} L/h") to null
        }
        trip?.let { t ->
            val avg = t.avgPer100()?.let { " · ${f1(it)}" } ?: ""
            if (t.km >= 0.1) out["trip"] = "${f1(t.km)} km$avg" to null
        }
        s.volts?.let { v -> out["battery"] = "${f1(v)} V" to when { s.engineRunning && v < 12.8 -> HOT; !s.engineRunning && v < 12.0 -> HOT; else -> null } }
        val throttle = s.extra[0x11]?.let { Math.round(it) }
        val load = s.extra[0x04]?.let { Math.round(it) }
        if (rangeKm != null) out["load"] = "≈ $rangeKm km" to (if (rangeKm < 60) HOT else null)
        else if (throttle != null || load != null) out["load"] = listOfNotNull(throttle?.let { "$it%" }, load?.let { "$it%" }).joinToString(" · ") to null
        val outside = s.ambientC
        val intake = s.extra[0x0F]?.let { Math.round(it).toInt() }
        if (outside != null || intake != null) out["temps"] = listOfNotNull(outside?.let { "$it°" }, intake?.let { "$it°" }).joinToString(" · ") to
            (if ((outside ?: 99) <= 3) COLD else null)
        return out
    }

    /** Red bar text when something needs attention, or null. */
    fun warning(s: CarSnapshot?, dutch: Boolean): String? {
        if (s == null) return null
        val hot = s.coolantC?.takeIf { it >= 110 && it <= 150 }
        return when {
            hot != null -> if (dutch) "⚠  Motor te heet: $hot °C - stop zodra het veilig kan" else "⚠  Engine hot: $hot °C - pull over when safe"
            s.codes.isNotEmpty() -> "⚠  ${s.codes.first()}: ${TroubleCodes.explain(s.codes.first(), dutch)}" +
                (if (s.codes.size > 1) " (+${s.codes.size - 1})" else "")
            s.checkEngine == true -> if (dutch) "⚠  Motorstoringslampje aan" else "⚠  Check-engine light on"
            else -> null
        }
    }
}
