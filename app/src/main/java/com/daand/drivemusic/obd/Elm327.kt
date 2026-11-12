package com.daand.drivemusic.obd

/**
 * The ELM327 language spoken by OBD adapters like the Vgate iCar Pro 2S: send "010C\r", get back
 * "410C1AF8" and a ">" prompt. Pure parsing + decoding (unit tested); no Bluetooth here.
 */
object Elm327 {
    /** Reset and set a compact, predictable output format. */
    val INIT = listOf(
        "ATZ",    // reset
        "ATE0",   // no echo of our commands
        "ATL0",   // no extra line feeds
        "ATS0",   // no spaces between bytes
        "ATH0",   // no CAN headers
        "ATAT1",  // adaptive timing (faster answers)
        "ATSP0",  // pick the car's protocol automatically (the ASX uses CAN)
    )

    private val NOISE = listOf("SEARCHING", "BUS INIT", "OK")
    private val ERRORS = listOf("NO DATA", "UNABLE TO CONNECT", "STOPPED", "CAN ERROR", "BUS ERROR", "BUS BUSY",
        "FB ERROR", "DATA ERROR", "BUFFER FULL", "ERROR", "?", "ACT ALERT", "LV RESET", "LP ALERT")

    /** Raw adapter output -> the meaningful lines (no prompt, no "SEARCHING...", no blank lines). */
    fun lines(raw: String): List<String> =
        raw.replace('\r', '\n').split('\n')
            .map { it.replace(">", "").trim() }
            .filter { it.isNotEmpty() && NOISE.none { n -> it.startsWith(n) } }

    /** The adapter said it couldn't answer (car off, PID unsupported, ...). */
    fun isError(lines: List<String>): Boolean = lines.isEmpty() || lines.all { l -> ERRORS.any { l.startsWith(it) } }

    /**
     * Hex bytes from one response line. Handles "41 0C 1A F8", "410C1AF8" and multi-frame CAN lines
     * like "0:4306013301" (the "0:" frame number is dropped). Non-hex lines -> empty.
     */
    fun bytes(line: String): List<Int> {
        var l = line.replace(" ", "")
        if (Regex("""^[0-9A-Fa-f]:""").containsMatchIn(l)) l = l.substring(2)
        if (l.isEmpty() || l.length % 2 != 0 || !l.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return emptyList()
        return l.chunked(2).map { it.toInt(16) }
    }

    /** Data bytes of a mode-01 answer for [pid] (after "41 <pid>"), from the first ECU that answered. */
    fun pidData(lines: List<String>, pid: Int): List<Int>? {
        for (line in lines) {
            val b = bytes(line)
            if (b.size >= 3 && b[0] == 0x41 && b[1] == pid) return b.drop(2)
        }
        return null
    }

    // ------------------------------------------------------------------ decoding (SAE J1979)

    const val PID_SUPPORTED_01_20 = 0x00
    const val PID_STATUS = 0x01          // check-engine light + number of codes
    const val PID_COOLANT = 0x05
    const val PID_RUNTIME = 0x1F          // seconds since the engine started
    const val PID_RPM = 0x0C
    const val PID_SPEED = 0x0D
    const val PID_FUEL = 0x2F
    const val PID_VOLTAGE = 0x42
    const val PID_AMBIENT = 0x46

    fun coolantC(d: List<Int>) = d.getOrNull(0)?.let { it - 40 }
    fun rpm(d: List<Int>) = if (d.size >= 2) (d[0] * 256 + d[1]) / 4 else null
    fun speedKmh(d: List<Int>) = d.getOrNull(0)
    fun runtimeS(d: List<Int>) = if (d.size >= 2) d[0] * 256 + d[1] else null
    fun fuelPct(d: List<Int>) = d.getOrNull(0)?.let { Math.round(it * 100 / 255f) }
    fun moduleVolts(d: List<Int>) = if (d.size >= 2) (d[0] * 256 + d[1]) / 1000.0 else null
    fun ambientC(d: List<Int>) = d.getOrNull(0)?.let { it - 40 }

    /** PID 01: is the check-engine light on, and how many codes are stored. */
    fun status(d: List<Int>): Pair<Boolean, Int>? = d.getOrNull(0)?.let { (it and 0x80 != 0) to (it and 0x7F) }

    /** "Supported PIDs" bitmask (4 bytes) for the block starting at [base] -> the PIDs it lists. */
    fun supported(d: List<Int>, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        d.take(4).forEachIndexed { byteIdx, byte ->
            for (bit in 0 until 8) if (byte and (0x80 shr bit) != 0) out += base + byteIdx * 8 + bit + 1
        }
        return out
    }

    /** "ATRV" answer like "12.4V" -> 12.4 (the adapter measures this itself; works on every car). */
    fun atrvVolts(lines: List<String>): Double? =
        lines.firstNotNullOfOrNull { Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.takeIf { it in 5.0..20.0 }

    /**
     * Stored trouble codes (mode 03), e.g. ["P0420", "P0133"]. CAN cars send a count byte first
     * ("43 02 0133 0420"), older protocols don't ("43 0133 0420 0000"); both are handled, also when
     * several control units answer or the answer spans several CAN frames.
     */
    fun dtcs(lines: List<String>): List<String> {
        // Keep each line together with its bytes, so "0:"/"1:" continuation frames stay recognisable.
        val frames = lines.map { it to bytes(it) }.filter { it.second.isNotEmpty() }
        val payload = mutableListOf<Int>()
        var i = 0
        while (i < frames.size) {
            val (_, f) = frames[i]
            if (f[0] == 0x43) {
                var data = f.drop(1)
                var multiFrame = false
                // Multi-frame CAN answer: the following "1:", "2:" frames continue this one.
                while (i + 1 < frames.size && frames[i + 1].first.contains(':') && frames[i + 1].second.firstOrNull() != 0x43) {
                    data = data + frames[i + 1].second; i++; multiFrame = true
                }
                when {
                    // CAN (always the case for multi-frame answers): first byte = number of codes, the
                    // rest may be zero padding - so use the count, not the length.
                    multiFrame || data.size % 2 == 1 -> {
                        val count = data.firstOrNull() ?: 0
                        data = data.drop(1).take(count * 2)
                    }
                }
                payload += data
            }
            i++
        }
        return payload.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { (a, b) -> code(a, b) }.distinct()
    }

    /** Two bytes -> "P0420" (first two bits pick P/C/B/U). */
    fun code(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 0x3]
        return letter + ((a shr 4) and 0x3).toString() + "%01X".format(a and 0xF) + "%02X".format(b)
    }
}
