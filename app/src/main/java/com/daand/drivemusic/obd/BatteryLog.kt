package com.daand.drivemusic.obd

import java.util.Locale

/**
 * Every battery reading before a start, with what happened while the car stood: how long it was
 * parked and whether the OBD adapter stayed plugged in (your answer to the "take it out" reminder).
 * That answers "is the adapter draining the battery?" with your own car's numbers. Pure, unit tested.
 */
object BatteryLog {
    /** Adapter state while parked: as answered on the reminder, or unknown. */
    const val ADAPTER_UNKNOWN = -1
    const val ADAPTER_OUT = 0
    const val ADAPTER_IN = 1

    data class Entry(val at: Long, val volts: Double, val parkedMin: Int?, val adapter: Int)

    /** A comparison only means something after a real stand: at least this long parked. */
    const val LONG_PARK_MIN = 12 * 60
    private const val MAX = 60

    fun encode(list: List<Entry>): String = list.takeLast(MAX).joinToString(";") {
        "${it.at}|${"%.2f".format(Locale.ROOT, it.volts)}|${it.parkedMin ?: ""}|${it.adapter}"
    }

    fun decode(s: String): List<Entry> = s.split(';').mapNotNull { e ->
        val f = e.split('|')
        if (f.size != 4) return@mapNotNull null
        val at = f[0].toLongOrNull() ?: return@mapNotNull null
        val v = f[1].toDoubleOrNull()?.takeIf { it in 5.0..20.0 } ?: return@mapNotNull null
        Entry(at, v, f[2].toIntOrNull()?.takeIf { it >= 0 }, f[3].toIntOrNull()?.takeIf { it in -1..1 } ?: ADAPTER_UNKNOWN)
    }

    fun add(list: List<Entry>, e: Entry) = (list + e).takeLast(MAX)

    /** "parked 14 h" / "parked 35 min" / "parked ?" */
    fun parkedText(min: Int?, dutch: Boolean): String = when {
        min == null -> if (dutch) "stilstand onbekend" else "parked ?"
        min >= 48 * 60 -> (if (dutch) "%d dagen stil" else "parked %d days").format(min / (24 * 60))
        min >= 60 -> (if (dutch) "%d u stil" else "parked %d h").format(min / 60)
        else -> (if (dutch) "%d min stil" else "parked %d min").format(min)
    }

    fun adapterText(a: Int, dutch: Boolean) = when (a) {
        ADAPTER_IN -> if (dutch) "adapter erin" else "adapter left in"
        ADAPTER_OUT -> if (dutch) "adapter eruit" else "adapter out"
        else -> if (dutch) "adapter ?" else "adapter ?"
    }

    /**
     * After long stands (12 h+): the average reading with the adapter left in vs taken out, once
     * there are at least 2 of each. A clearly lower "left in" (0.1 V+) points at the adapter.
     */
    data class Comparison(val leftIn: Double, val leftInCount: Int, val out: Double, val outCount: Int) {
        val drainSuspected get() = out - leftIn >= 0.1
    }

    fun compare(list: List<Entry>): Comparison? {
        val long = list.filter { (it.parkedMin ?: 0) >= LONG_PARK_MIN }
        val inn = long.filter { it.adapter == ADAPTER_IN }.map { it.volts }
        val out = long.filter { it.adapter == ADAPTER_OUT }.map { it.volts }
        if (inn.size < 2 || out.size < 2) return null
        return Comparison(inn.average(), inn.size, out.average(), out.size)
    }
}
