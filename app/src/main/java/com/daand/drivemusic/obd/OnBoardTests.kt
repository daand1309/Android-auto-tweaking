package com.daand.drivemusic.obd

/**
 * Mode 06: the car's own self-test results, each with its pass/fail limits. For the catalytic
 * converter (OBDMID 21/22, bank 1/2) that says how close it is to failing - long before a P0420 is
 * stored, and how it is doing after one. Read-only, like everything else. Pure, unit tested.
 */
object OnBoardTests {
    /** One test record: OBDMID, test id, unit/scaling id, the value and its limits (raw). */
    data class Result(val mid: Int, val tid: Int, val unit: Int, val value: Int, val min: Int, val max: Int) {
        val passes get() = value in min..max
        /**
         * How close to the limit, 0..1 (above 1 = failing): value / max when there is an upper limit,
         * min / value when only a lower one; null when neither says anything.
         */
        val closeness: Double? get() = when {
            max in 1 until 0xFFFF -> value.toDouble() / max
            min > 0 -> if (value <= 0) 2.0 else min.toDouble() / value
            else -> null
        }
    }

    const val CATALYST_BANK1 = 0x21
    const val CATALYST_BANK2 = 0x22

    /** All records from a mode 06 answer (single line or CAN multi-frame "0:", "1:" lines). */
    fun parse(lines: List<String>): List<Result> {
        val all = lines.flatMap { Elm327.bytes(it) }
        val start = all.indexOf(0x46)
        if (start < 0) return emptyList()
        val data = all.drop(start + 1)
        return data.chunked(9).filter { it.size == 9 && it[0] != 0 }.map { r ->
            Result(r[0], r[1], r[2], (r[3] shl 8) or r[4], (r[5] shl 8) or r[6], (r[7] shl 8) or r[8])
        }
    }

    data class Catalyst(val closeness: Double, val passes: Boolean)

    /** The catalytic converter's worst test (bank 1 or 2), or null when the answer has none. */
    fun catalyst(results: List<Result>): Catalyst? {
        val cat = results.filter { it.mid == CATALYST_BANK1 || it.mid == CATALYST_BANK2 }
        val scored = cat.mapNotNull { r -> r.closeness?.let { it to r.passes } }
        if (scored.isEmpty()) return null
        val worst = scored.maxBy { it.first }
        return Catalyst(worst.first, cat.all { it.passes })
    }
}

/** What the car saved when [code] was stored: values by PID (see Pids). */
data class FreezeFrame(val code: String, val values: Map<Int, Double>) {
    fun encode() = code + "|" + values.entries.joinToString(",") { "%02X=%s".format(it.key, it.value) }

    companion object {
        fun decode(s: String): FreezeFrame? {
            val parts = s.split('|')
            if (parts.size != 2 || !Regex("""^[PCBU][0-9A-F]{4}$""").matches(parts[0])) return null
            val values = parts[1].split(',').mapNotNull { kv ->
                val (k, v) = kv.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val pid = k.toIntOrNull(16) ?: return@mapNotNull null
                v.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { pid to it }
            }.toMap()
            return FreezeFrame(parts[0], values)
        }
    }
}

/** The catalyst test over the drives: "time:closeness;..." (newest last, at most 30). */
object CatalystLog {
    fun add(log: String, at: Long, closeness: Double): String =
        (decode(log) + (at to closeness)).takeLast(30).joinToString(";") { "${it.first}:%.3f".format(java.util.Locale.ROOT, it.second) }

    fun decode(s: String): List<Pair<Long, Double>> = s.split(';').mapNotNull { e ->
        val (t, c) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val at = t.toLongOrNull() ?: return@mapNotNull null
        c.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..5.0 }?.let { at to it }
    }
}
