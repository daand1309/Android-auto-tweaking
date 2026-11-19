package com.daand.drivemusic.obd

/**
 * "4th would do here, sir": a hint when you cruise at steady speed with high revs in a gear that has a
 * higher one. The gears are learned from your own drives - at a steady speed, km/h per 1000 rpm is fixed
 * per gear, so steady readings pile up in peaks, one per gear (ASX 1.6, 5-speed: about 8, 16, 23, 30 and
 * 37). Until the peaks are clear (or on an automatic/CVT, where they never are) it says nothing.
 * Accelerating doesn't count - the MIVEC wants revs to pull; only steady cruising does. Pure, unit tested.
 */
class GearCoach(learned: String = "") {
    companion object {
        /** Histogram bins of 0.5 km/h per 1000 rpm, up to 50. */
        private const val BIN = 0.5
        private const val BINS = 100
        /** Cruising this high (steady speed) in a gear that has a higher one: worth a word. */
        const val CRUISE_RPM = 2_900
        /** ...but only if the next gear still pulls (no lugging the engine). */
        const val NEXT_GEAR_MIN_RPM = 1_700
        const val STEADY_FOR_MS = 8_000L
        const val GAP_MS = 4 * 60_000L
        const val MAX_PER_DRIVE = 3
        private const val PEAK_MIN = 25
    }

    private val hist = IntArray(BINS)
    init {
        learned.split(',').forEach { e -> e.split(':').takeIf { it.size == 2 }?.let { (b, c) ->
            val i = b.toIntOrNull(); val n = c.toIntOrNull()
            if (i != null && n != null && i in 0 until BINS && n > 0) hist[i] = n
        } }
    }

    fun save(): String = hist.withIndex().filter { it.value > 0 }.joinToString(",") { "${it.index}:${it.value}" }

    /** km/h per 1000 rpm of each gear (lowest first), from the histogram's clear peaks. */
    fun gears(): List<Double> {
        val total = hist.sum().takeIf { it > 0 } ?: return emptyList()
        val peaks = mutableListOf<Int>()
        for (i in 1 until BINS - 1) {
            val n = hist[i] + hist[i - 1] + hist[i + 1]       // smoothed
            val left = (if (i >= 2) hist[i - 2] else 0) + hist[i - 1] + hist[i]
            val right = hist[i] + hist[i + 1] + (if (i + 2 < BINS) hist[i + 2] else 0)
            if (n >= PEAK_MIN && n * 20 >= total && n >= left && n >= right && peaks.none { kotlin.math.abs(it - i) < 6 }) peaks += i
        }
        return peaks.sorted().map { (it + 0.5) * BIN }
    }

    private var lastAt = 0L
    private var lastSpeed = -1
    private var lastRatio = 0.0
    private var steadySince = 0L
    private var lastHintAt = -GAP_MS
    private var hints = 0

    /** What to say, if anything: (gear number or null when the count of gears isn't sure, rpm now). */
    data class Hint(val nextGear: Int?, val rpm: Int)

    fun add(s: CarSnapshot, warm: Boolean): Hint? {
        val speed = s.speedKmh ?: return null; val rpm = s.rpm ?: return null
        val at = s.at
        val ratio = if (rpm > 0) speed / (rpm / 1000.0) else 0.0
        // Steady: speed within 2 km/h and the same gear (ratio within 3%) since the last reading.
        val steady = lastSpeed >= 0 && at - lastAt in 1_000..10_000 && kotlin.math.abs(speed - lastSpeed) <= 2 &&
            lastRatio > 0 && kotlin.math.abs(ratio / lastRatio - 1) < 0.03
        lastAt = at; lastSpeed = speed; lastRatio = ratio
        if (!steady || speed < 15 || rpm !in 1_100..5_500) { steadySince = 0; return null }
        if (steadySince == 0L) steadySince = at
        (ratio / BIN).toInt().takeIf { it in 0 until BINS }?.let { hist[it]++ }
        if (!warm || rpm < CRUISE_RPM || at - steadySince < STEADY_FOR_MS) return null
        if (hints >= MAX_PER_DRIVE || at - lastHintAt < GAP_MS) return null
        val g = gears()
        if (g.size < 3) return null
        val i = g.indexOfFirst { kotlin.math.abs(ratio / it - 1) < 0.07 }
        if (i < 0 || i == g.lastIndex) return null                 // not in a known gear, or already in top gear
        val nextRpm = speed / g[i + 1] * 1000
        if (nextRpm < NEXT_GEAR_MIN_RPM) return null
        lastHintAt = at; hints++
        return Hint(if (g.size == 5) i + 2 else null, rpm)
    }
}
