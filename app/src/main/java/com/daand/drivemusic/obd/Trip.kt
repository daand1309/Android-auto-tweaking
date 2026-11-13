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
    /** For the driving score. */
    var highRpmSeconds = 0.0; private set
    var hardAccelerations = 0; private set
    var hardBrakes = 0; private set
    /** Early-warning numbers for this drive (see [HealthStats]). */
    val health = HealthStats()
    private var last: CarSnapshot? = null
    private var inHardAccel = false
    private var inHardBrake = false

    fun add(s: CarSnapshot) {
        val prev = last
        last = s
        s.speedKmh?.let { maxSpeed = maxOf(maxSpeed, it) }
        s.coolantC?.let { c -> if (c <= CarAlerts.MAX_PLAUSIBLE_C) maxCoolant = maxOf(maxCoolant ?: c, c) }
        if (prev == null) { health.add(s, 0.0); return }
        // Gaps (adapter reconnecting) aren't counted as driving.
        val dt = (s.at - prev.at) / 1000.0
        health.add(s, dt)
        if (dt <= 0 || dt > 15) return
        val speed = (((prev.speedKmh ?: 0) + (s.speedKmh ?: 0)) / 2.0)
        km += speed * dt / 3600
        prev.litresPerHour?.let { litres += it * dt / 3600 }
        if (s.engineRunning) { if (speed < 1) idleSeconds += dt else drivingSeconds += dt }
        if ((s.rpm ?: 0) > HIGH_RPM) highRpmSeconds += dt
        // Hard acceleration / braking: speed change per second between readings (m/s²). Readings
        // closer than 1 s apart are too coarse (1 km/h steps), and one long hard push counts once.
        val v0 = prev.speedKmh; val v1 = s.speedKmh
        if (v0 != null && v1 != null && dt >= 1.0) {
            val accel = (v1 - v0) / 3.6 / dt
            if (accel > HARD_ACCEL) { if (!inHardAccel) hardAccelerations++; inHardAccel = true } else inHardAccel = false
            if (accel < -HARD_BRAKE) { if (!inHardBrake) hardBrakes++; inHardBrake = true } else inHardBrake = false
        }
    }

    /**
     * Driving score 0-100 (fun, not judgement): smooth driving scores high; hard acceleration and
     * braking, lots of high revs and long idling cost points. null for very short trips.
     */
    fun score(): Int? {
        val total = drivingSeconds + idleSeconds
        if (total < 120 || km < 0.5) return null
        val idlePct = idleSeconds / total * 100
        val highRpmPct = highRpmSeconds / total * 100
        val events = (hardAccelerations + hardBrakes) / maxOf(km, 1.0) * 10   // per 10 km
        val penalty = (idlePct - 10).coerceAtLeast(0.0) * 0.6 + highRpmPct * 1.0 + events * 6
        return (100 - penalty).toInt().coerceIn(0, 100)
    }

    companion object {
        const val HIGH_RPM = 3500
        const val HARD_ACCEL = 2.5     // m/s² (0-100 in ~11 s)
        const val HARD_BRAKE = 3.5     // m/s²

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
