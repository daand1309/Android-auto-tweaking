package com.daand.drivemusic.obd

/**
 * One conversation with the adapter: set it up, learn what this car supports, then read values.
 * Works over any [ObdLink], so it's tested against a fake adapter.
 */
class ObdSession(private val link: ObdLink, private val engineLitres: Double = 1.6, private val log: (String) -> Unit = {}) {
    var supported: Set<Int> = emptySet(); private set
    var protocol: String = ""; private set
    var vin: String? = null; private set

    /** Returns null when the car answers, or a readable reason why not. */
    fun start(): String? {
        for (cmd in Elm327.INIT) link.send(cmd, if (cmd == "ATZ") 3_000 else 1_500)
        // First real question: the adapter now searches for the car's protocol (can take seconds).
        val first = Elm327.lines(link.send("0100", 10_000))
        if (Elm327.isError(first)) return "the car isn't answering (is the ignition on?)"
        supported = Elm327.pidData(first, 0x00)?.let { Elm327.supported(it, 0x00) } ?: emptySet()
        // Further blocks of supported values (21-40, 41-60, ...), as far as the car says it has them.
        for (base in Pids.SUPPORT_BLOCKS.drop(1)) {
            if (base !in supported) break
            val ans = Elm327.lines(link.send("01%02X".format(base), 2_000))
            supported = supported + (Elm327.pidData(ans, base)?.let { Elm327.supported(it, base) } ?: emptySet())
        }
        protocol = Elm327.lines(link.send("ATDP", 1_500)).firstOrNull()?.removePrefix("AUTO, ")?.take(40) ?: ""
        vin = runCatching { Elm327.vin(Elm327.lines(link.send("0902", 3_000))) }.getOrNull()
        log("OBD: connected ($protocol), car supports ${supported.size} values")
        return null
    }

    private fun raw(pid: Int): List<Int>? {
        if (supported.isNotEmpty() && pid !in supported) return null
        val lines = Elm327.lines(link.send("01%02X".format(pid), 1_500))
        return if (Elm327.isError(lines)) null else Elm327.pidData(lines, pid)
    }

    private fun codes(mode: String, answerByte: Int): List<String> {
        val ans = Elm327.lines(link.send(mode, 4_000))
        if (Elm327.isError(ans)) return emptyList()
        // Pending (07) and permanent (0A) answers look like stored ones (03), just with 47 / 4A first.
        val asStored = ans.map { line ->
            val b = Elm327.bytes(line)
            if (b.firstOrNull() != answerByte) line
            else {
                // Keep a multi-frame "0:" number so continuation frames are still recognised.
                val frame = Regex("""^\s*([0-9A-Fa-f]:)""").find(line)?.groupValues?.get(1) ?: ""
                frame + "43" + b.drop(1).joinToString("") { "%02X".format(it) }
            }
        }
        return Elm327.dtcs(asStored)
    }

    /**
     * A reading. Fast values every time; [full] = everything else the car supports (every ~30 s);
     * [readCodes] = also the fault codes (every ~10 min).
     */
    fun read(previous: CarSnapshot?, full: Boolean, readCodes: Boolean): CarSnapshot {
        val now = System.currentTimeMillis()
        val extra = (previous?.extra ?: emptyMap()).toMutableMap()
        val wanted = Pids.ALL.filter { it.fast || full }
        // A value that doesn't answer (or answers garbage) this time is dropped, not kept from before:
        // a stale air-flow reading would otherwise keep "using fuel" after the engine stopped.
        for (info in wanted) {
            val v = raw(info.pid)?.let { info.read(it) }
            if (v != null) extra[info.pid] = v else extra.remove(info.pid)
        }
        // Seconds since start: interesting in the first minutes (the "ready to drive" countdown).
        if (!full && (previous?.runtimeS ?: 0) < 300) {
            val rt = raw(Elm327.PID_RUNTIME)?.let { Elm327.runtimeS(it) }
            if (rt != null) extra[0x1F] = rt.toDouble() else extra.remove(0x1F)
        }

        var volts = extra[0x42] ?: previous?.volts
        var status = previous?.checkEngine
        var readiness = previous?.readiness
        var codes = previous?.codes ?: emptyList()
        var pending = previous?.pendingCodes ?: emptyList()
        var permanent = previous?.permanentCodes ?: emptyList()
        if (full) {
            if (0x42 !in extra || Elm327.PID_VOLTAGE !in supported) volts = Elm327.atrvVolts(Elm327.lines(link.send("ATRV", 1_500))) ?: volts
            raw(Elm327.PID_STATUS)?.let { d ->
                val st = Elm327.status(d)
                status = st?.first
                readiness = Elm327.readiness(d)
                if (readCodes || (st != null && st.second > 0 && codes.isEmpty())) codes = codes("03", 0x43)
                if (st?.first == false && st.second == 0) codes = emptyList()
            }
            if (readCodes) {
                pending = codes("07", 0x47)
                permanent = codes("0A", 0x4A)
            }
        }
        val running = (extra[0x0C] ?: 0.0) > 400
        val lph = if (running) FuelMath.litresPerHour(extra, engineLitres) else if (extra.containsKey(0x0C)) 0.0 else null
        return CarSnapshot(
            at = now,
            coolantC = extra[0x05]?.toInt(), rpm = extra[0x0C]?.toInt(), speedKmh = extra[0x0D]?.toInt(),
            fuelPct = extra[0x2F]?.let { Math.round(it).toInt() }, volts = volts, ambientC = extra[0x46]?.toInt(),
            checkEngine = status, codes = codes, runtimeS = extra[0x1F]?.toInt(), extra = extra,
            pendingCodes = pending, permanentCodes = permanent, readiness = readiness, litresPerHour = lph,
        )
    }

    fun close() { runCatching { link.close() } }
}
