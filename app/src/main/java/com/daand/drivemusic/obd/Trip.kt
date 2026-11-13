package com.daand.drivemusic.obd

/**
 * Trip numbers built from the readings: distance (speed over time), fuel used, idle time, averages.
 * Pure, unit tested.
 */
class Trip {
    var km = 0.0; private set
    var litres = 0.0; private set
    var idleSeconds = 0.0; private set
    var drivingSeconds = 0.0; private set
    var maxSpeed = 0; private set
    var maxCoolant: Int? = null; private set
    /** For the driving score (see [score]). */
    var highRevSeconds = 0.0; private set
    var hardAccelerations = 0; private set
    var hardBrakes = 0; private set
    /** Idling beyond the first [IDLE_FREE_S] of each stop: waiting with the engine running. */
    var longIdleSeconds = 0.0; private set
    /** Early-warning numbers for this drive (see [HealthStats]). */
    val health = HealthStats()
    private var last: CarSnapshot? = null
    private var inHardAccel = false
    private var inHardBrake = false
    // The last three speed readings: each is judged with its neighbours (see smoothSpeed).
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private val history = ArrayDeque<Pair<Long, Double>>()   // smoothed speeds of the last 15 s
    private var accelerating = false
    private var idleStint = 0.0

    fun add(s: CarSnapshot) {
        val prev = last
        last = s
        s.speedKmh?.let { maxSpeed = maxOf(maxSpeed, it) }
        s.coolantC?.let { c -> if (c <= CarAlerts.MAX_PLAUSIBLE_C) maxCoolant = maxOf(maxCoolant ?: c, c) }
        if (prev == null) { health.add(s, 0.0); s.speedKmh?.let { smoothSpeed(s.at, it) }; return }
        // Gaps (adapter reconnecting) aren't counted as driving.
        val dt = (s.at - prev.at) / 1000.0
        health.add(s, dt)
        if (dt <= 0 || dt > 15) return
        val speed = (((prev.speedKmh ?: 0) + (s.speedKmh ?: 0)) / 2.0)
        km += speed * dt / 3600
        prev.litresPerHour?.let { litres += it * dt / 3600 }
        if (s.engineRunning) {
            if (speed < 1) {
                idleSeconds += dt
                // Stopping at lights, in a queue or for the warm-up the app asks for is fine; only
                // the part of a stop beyond IDLE_FREE_S counts (waiting with the engine running).
                idleStint += dt
                if (idleStint > IDLE_FREE_S) longIdleSeconds += minOf(dt, idleStint - IDLE_FREE_S)
            } else { drivingSeconds += dt; idleStint = 0.0 }
        }
        // High revs only count while speeding up (revving hard through the gears) - not cruising on
        // the motorway (the ASX 1.6 turns ~3,600 rpm at 130 km/h) or engine braking downhill.
        if ((s.rpm ?: 0) >= HIGH_REV_RPM && accelerating) highRevSeconds += dt
        s.speedKmh?.let { smoothSpeed(s.at, it) }
    }

    /**
     * Hard acceleration / braking from the smoothed speed over windows of at least 3 s - the same
     * whether readings come every 1.5 s (car tab open) or every 5 s. One long push counts once;
     * physically impossible changes (over 10 m/s²) are a garbled reading, not driving.
     */
    private fun smoothSpeed(at: Long, kmh: Int) {
        recent.addLast(at to kmh)
        if (recent.size > 3) recent.removeFirst()
        if (recent.size < 3) return
        val (mAt, raw) = recent[1]
        val before = recent[0].second; val after = recent[2].second
        // A lone spike - one reading far off while the ones either side agree - is a garbled answer.
        val v = if (Math.abs(before - after) <= 10 && Math.abs(raw - (before + after) / 2.0) >= 25) (before + after) / 2.0 else raw.toDouble()
        // Sliding window: compare with the latest smoothed speed at least WINDOW_S earlier, so a hard
        // stop is measured whole whatever the reading rhythm (not split across two windows).
        history.addLast(mAt to v)
        while (history.isNotEmpty() && mAt - history.first().first > 15_000) history.removeFirst()
        val ref = history.lastOrNull { mAt - it.first >= WINDOW_S * 1000 } ?: return
        val accel = (v - ref.second) / 3.6 / ((mAt - ref.first) / 1000.0)
        if (Math.abs(accel) > IMPOSSIBLE) return
        accelerating = accel > 0.3
        if (accel >= HARD_ACCEL) { if (!inHardAccel) hardAccelerations++; inHardAccel = true } else inHardAccel = false
        if (accel <= -HARD_BRAKE) { if (!inHardBrake) hardBrakes++; inHardBrake = true } else inHardBrake = false
    }

    /**
     * Driving score 0-100 (fun, not judgement). Only what the driver chooses costs points: hard
     * acceleration and braking, revving hard while speeding up, and waiting with the engine running.
     * Traffic, red lights, the motorway and the warm-up don't. null for very short trips.
     */
    fun score(): Int? {
        val total = drivingSeconds + idleSeconds
        if (total < 120 || km < 0.5) return null
        val events = (hardAccelerations + hardBrakes) / maxOf(km, 5.0) * 10   // per 10 km (short trips not over-punished)
        val penalty = events * 5 + highRevSeconds / total * 100 * 1.5 + longIdleSeconds / total * 100 * 0.6
        return (100 - penalty).toInt().coerceIn(0, 100)
    }

    companion object {
        const val HIGH_REV_RPM = 4000
        const val HARD_ACCEL = 3.0     // m/s²: the ASX 1.6 flat out does 0-100 in ~11.5 s (~2.4 m/s² average)
        const val HARD_BRAKE = 4.0     // m/s²: a normal stop is 2-3, an emergency stop 8+
        const val IMPOSSIBLE = 10.0    // m/s²: no car changes speed faster - a garbled reading
        const val WINDOW_S = 3.0
        const val IDLE_FREE_S = 120.0

        fun scoreWord(score: Int, dutch: Boolean) = when {
            score >= 85 -> if (dutch) "rustig" else "smooth"
            score >= 65 -> if (dutch) "prima" else "good"
            else -> if (dutch) "sportief" else "sporty"
        }
    }

    /** Average L/100 km for the trip (after the first kilometre, before that it's meaningless). */
    fun avgPer100(): Double? = if (km >= 1 && litres > 0) litres / km * 100 else null
    fun avgSpeed(): Double? = if (drivingSeconds > 60) km / (drivingSeconds / 3600) else null

    fun summary(dutch: Boolean): String = listOfNotNull(
        if (km >= 0.1) "%.1f km".format(java.util.Locale.ROOT, km) else null,
        avgPer100()?.let { "%.1f L/100km".format(java.util.Locale.ROOT, it) },
        if (litres >= 0.05) "%.2f L".format(java.util.Locale.ROOT, litres) + (if (dutch) " verbruikt" else " used") else null,
        avgSpeed()?.let { (if (dutch) "gem. " else "avg ") + "${Math.round(it)} km/h" },
        if (idleSeconds >= 60) (if (dutch) "stationair " else "idle ") + "${Math.round(idleSeconds / 60)} min" else null,
        if (maxSpeed > 0) "max $maxSpeed km/h" else null,
        maxCoolant?.let { (if (dutch) "max motor " else "max engine ") + "$it °C" },
        score()?.let { (if (dutch) "rijstijl " else "driving ") + "${scoreWord(it, dutch)} $it/100" },
    ).joinToString(" · ")
}
