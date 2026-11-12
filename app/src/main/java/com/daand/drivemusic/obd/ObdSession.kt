package com.daand.drivemusic.obd

/**
 * One conversation with the adapter: set it up, learn what this car supports, then read values.
 * Works over any [ObdLink], so it's tested against a fake adapter.
 */
class ObdSession(private val link: ObdLink, private val log: (String) -> Unit = {}) {
    var supported: Set<Int> = emptySet(); private set
    var protocol: String = ""; private set

    /** Returns null when the car answers, or a readable reason why not. */
    fun start(): String? {
        for (cmd in Elm327.INIT) link.send(cmd, if (cmd == "ATZ") 3_000 else 1_500)
        // First real question: the adapter now searches for the car's protocol (can take seconds).
        val first = Elm327.lines(link.send("0100", 10_000))
        if (Elm327.isError(first)) return "the car isn't answering (is the ignition on?)"
        supported = Elm327.pidData(first, 0x00)?.let { Elm327.supported(it, 0x00) } ?: emptySet()
        // Further blocks of supported PIDs (21-40, 41-60), if the car says it has them.
        for (base in listOf(0x20, 0x40)) {
            if (base !in supported) break
            val ans = Elm327.lines(link.send("01%02X".format(base), 2_000))
            supported = supported + (Elm327.pidData(ans, base)?.let { Elm327.supported(it, base) } ?: emptySet())
        }
        protocol = Elm327.lines(link.send("ATDP", 1_500)).firstOrNull()?.removePrefix("AUTO, ")?.take(40) ?: ""
        log("OBD: connected ($protocol), car supports ${supported.size} values")
        return null
    }

    private fun pid(pid: Int): List<Int>? {
        if (supported.isNotEmpty() && pid !in supported) return null
        val lines = Elm327.lines(link.send("01%02X".format(pid), 1_500))
        return if (Elm327.isError(lines)) null else Elm327.pidData(lines, pid)
    }

    /** A reading. [full] = also the slower-changing values (fuel, voltage, warning light, codes). */
    fun read(previous: CarSnapshot?, full: Boolean, readCodes: Boolean): CarSnapshot {
        val now = System.currentTimeMillis()
        val coolant = pid(Elm327.PID_COOLANT)?.let { Elm327.coolantC(it) }
        val rpm = pid(Elm327.PID_RPM)?.let { Elm327.rpm(it) }
        val speed = pid(Elm327.PID_SPEED)?.let { Elm327.speedKmh(it) }
        // Seconds since start: only interesting in the first minutes (the "ready to drive" countdown).
        val runtime = if ((previous?.runtimeS ?: 0) < 300 && Elm327.PID_RUNTIME in supported) pid(Elm327.PID_RUNTIME)?.let { Elm327.runtimeS(it) } else previous?.runtimeS
        if (!full) return (previous ?: CarSnapshot(now)).copy(at = now, coolantC = coolant ?: previous?.coolantC, rpm = rpm, speedKmh = speed, runtimeS = runtime)
        val fuel = pid(Elm327.PID_FUEL)?.let { Elm327.fuelPct(it) }
        val volts = pid(Elm327.PID_VOLTAGE)?.let { Elm327.moduleVolts(it) }
            ?: Elm327.atrvVolts(Elm327.lines(link.send("ATRV", 1_500)))
        val ambient = pid(Elm327.PID_AMBIENT)?.let { Elm327.ambientC(it) }
        val status = pid(Elm327.PID_STATUS)?.let { Elm327.status(it) }
        var codes = previous?.codes ?: emptyList()
        if (readCodes || (status != null && status.second > 0 && codes.isEmpty())) {
            val ans = Elm327.lines(link.send("03", 4_000))
            codes = if (Elm327.isError(ans)) emptyList() else Elm327.dtcs(ans)
        }
        if (status?.first == false && status.second == 0) codes = emptyList()
        return CarSnapshot(now, coolant, rpm, speed, fuel, volts, ambient, status?.first, codes, runtime)
    }

    fun close() { runCatching { link.close() } }
}
