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
    private var last: CarSnapshot? = null

    fun add(s: CarSnapshot) {
        val prev = last
        last = s
        s.speedKmh?.let { maxSpeed = maxOf(maxSpeed, it) }
        s.coolantC?.let { c -> if (c <= CarAlerts.MAX_PLAUSIBLE_C) maxCoolant = maxOf(maxCoolant ?: c, c) }
        if (prev == null) return
        // Gaps (adapter reconnecting) aren't counted as driving.
        val dt = (s.at - prev.at) / 1000.0
        if (dt <= 0 || dt > 15) return
        val speed = (((prev.speedKmh ?: 0) + (s.speedKmh ?: 0)) / 2.0)
        km += speed * dt / 3600
        prev.litresPerHour?.let { litres += it * dt / 3600 }
        if (s.engineRunning) { if (speed < 1) idleSeconds += dt else drivingSeconds += dt }
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
    ).joinToString(" · ")
}
