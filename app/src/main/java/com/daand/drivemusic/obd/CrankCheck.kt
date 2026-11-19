package com.daand.drivemusic.obd

/**
 * The battery's real test: how far the voltage dips while the starter turns the engine. Sampled several
 * times a second while the ignition is on and the engine off; the start shows itself as a dip followed
 * by charging voltage, so no rpm reading is needed. Healthy: stays at 10.0 V or above; 9.6-10.0 V: tired;
 * below 9.6 V: replace it soon (most of all before frost). Pure, unit tested.
 */
class CrankCheck {
    companion object {
        /** The alternator is charging: the engine runs. */
        const val RUNNING_V = 13.2
        /** A real crank pulls at least this far below the resting voltage. */
        const val MIN_DIP_V = 0.8
        const val GOOD_V = 10.0
        const val WEAK_V = 9.6
        fun verdict(minV: Double) = when { minV >= GOOD_V -> "good"; minV >= WEAK_V -> "tired"; else -> "replace soon" }

        /** "day:volts;..." newest last, at most 30. */
        fun add(log: String, day: Long, volts: Double): String =
            (decode(log).filter { it.first != day } + (day to volts)).takeLast(30).joinToString(";") { "${it.first}:%.2f".format(java.util.Locale.ROOT, it.second) }
        fun decode(log: String): List<Pair<Long, Double>> = log.split(';').mapNotNull { e ->
            e.split(':').takeIf { it.size == 2 }?.let { (d, v) -> d.toLongOrNull()?.let { day -> v.toDoubleOrNull()?.let { day to it } } }
        }
        /** Clearly weaker than it used to be: the last two average 0.4 V+ below the earlier ones. */
        fun gettingWeaker(log: List<Pair<Long, Double>>): Boolean {
            if (log.size < 5) return false
            val recent = log.takeLast(2).map { it.second }.average()
            val before = log.dropLast(2).takeLast(6).map { it.second }.average()
            return before - recent >= 0.4
        }
    }

    data class Result(val restingV: Double, val minV: Double)

    private val samples = java.util.ArrayDeque<Pair<Long, Double>>()
    var done = false; private set

    /** One voltage sample (ms, volts). Returns the result once, when a start is seen. */
    fun add(at: Long, v: Double): Result? {
        if (done || v !in 5.0..16.0) return null
        samples.addLast(at to v)
        while (samples.isNotEmpty() && at - samples.first.first > 15_000) samples.removeFirst()
        if (v < RUNNING_V || samples.size < 4) return null
        // Charging now: was there a dip in the seconds before, below the resting level?
        val before = samples.filter { at - it.first in 1..12_000 && it.second < RUNNING_V }
        if (before.size < 3) return null
        val minV = before.minOf { it.second }
        val minAt = before.first { it.second == minV }.first
        val rest = before.filter { it.first < minAt - 300 }.map { it.second }
        val resting = (if (rest.isNotEmpty()) rest.maxOrNull() else null) ?: return null
        if (resting - minV < MIN_DIP_V) return null
        done = true
        return Result(resting, minV)
    }
}
