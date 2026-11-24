package com.daand.drivemusic.obd

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-demand health checks with the OBD adapter: a quick check of one reading, and a guided full test
 * (parked, engine running) that collects readings per step. Judging only - no Android, no adapter -
 * so every threshold is unit tested. Read-only by design: nothing here asks the car to change anything.
 *
 * Thresholds are for a petrol engine like the ASX's 1.6 MIVEC (see docs/superpowers/specs/2026-11-24-diagnostics-design.md).
 */
object Diagnostics {
    enum class Verdict(val mark: String) { OK("✓"), WARN("⚠"), BAD("✗"), INFO("ℹ") }

    data class Check(val id: String, val title: String, val verdict: Verdict, val detail: String)

    /** One finished check run, for the history. */
    data class Result(val at: Long, val full: Boolean, val checks: List<Check>) {
        fun count(v: Verdict) = checks.count { it.verdict == v }
        fun summary() = "${count(Verdict.OK)} ✓ · ${count(Verdict.WARN)} ⚠ · ${count(Verdict.BAD)} ✗"
        /** Plain text for sharing (with a garage, or in a chat). */
        fun text(dateText: String) = buildString {
            append(if (full) "Full car test" else "Quick car check").append(" - ").append(dateText).append('\n')
            append(summary()).append("\n\n")
            for (c in checks) append(c.verdict.mark).append(' ').append(c.title).append(": ").append(c.detail).append('\n')
        }
    }

    // --- Quick check -------------------------------------------------------------------------------

    /**
     * Judges one reading (made with codes and every value). [catalyst]: the car's own catalyst test this
     * drive, if it has run. [misfires]: mode 06 misfire counts per cylinder (1-based), if the car shares them.
     */
    fun quick(s: CarSnapshot, catalyst: OnBoardTests.Catalyst?, misfires: Map<Int, Int>?): List<Check> {
        val out = ArrayList<Check>()
        out += codes(s)
        s.checkEngine?.let { mil ->
            out += if (mil) Check("mil", "Engine light", Verdict.BAD, "ON - the car has found a fault it wants fixed" +
                (s.extra[0x21]?.let { ", driven ${it.toInt()} km with it on" } ?: ""))
            else Check("mil", "Engine light", Verdict.OK, "off")
        }
        s.readiness?.let { (done, avail) ->
            out += if (avail == 0) Check("ready", "Emissions self-tests", Verdict.INFO, "the car doesn't report them")
            else if (done >= avail) Check("ready", "Emissions self-tests", Verdict.OK, "all $avail finished")
            else Check("ready", "Emissions self-tests", Verdict.WARN, "$done of $avail finished - normal after the battery " +
                "was disconnected or codes were cleared; they finish over a few mixed drives (needed for the APK)")
        }
        cleared(s)?.let { out += it }
        battery(s.volts, s.engineRunning)?.let { out += it }
        if (s.engineRunning) trim(s, "trim", "Fuel mixture correction")?.let { out += it }
        temps(s)?.let { out += it }
        coolant(s)?.let { out += it }
        out += catalystCheck(catalyst)
        misfires?.let { misfireCheck(it)?.let { c -> out += c } }
        return out
    }

    private fun codes(s: CarSnapshot): Check {
        val parts = ArrayList<String>()
        if (s.codes.isNotEmpty()) parts += "confirmed: " + s.codes.joinToString()
        if (s.permanentCodes.isNotEmpty()) parts += "permanent: " + s.permanentCodes.joinToString()
        if (s.pendingCodes.isNotEmpty()) parts += "early (pending): " + s.pendingCodes.joinToString()
        return when {
            s.codes.isNotEmpty() || s.permanentCodes.isNotEmpty() -> Check("codes", "Fault codes", Verdict.BAD, parts.joinToString("; "))
            s.pendingCodes.isNotEmpty() -> Check("codes", "Fault codes", Verdict.WARN, parts.joinToString("; ") +
                " - seen once, not confirmed yet")
            else -> Check("codes", "Fault codes", Verdict.OK, "none stored, none pending")
        }
    }

    private fun cleared(s: CarSnapshot): Check? {
        val km = s.extra[0x31]; val warmups = s.extra[0x30]
        if (km == null && warmups == null) return null
        val recent = (km != null && km < 200) || (warmups != null && warmups < 10)
        val what = listOfNotNull(km?.let { "${it.toInt()} km" }, warmups?.let { "${it.toInt()} warm-ups" }).joinToString(", ")
        return if (recent) Check("cleared", "Codes last cleared", Verdict.WARN,
            "only $what ago - a fault may have been hidden recently (or the battery was disconnected)")
        else Check("cleared", "Codes last cleared", Verdict.OK, "$what ago")
    }

    fun battery(v: Double?, running: Boolean): Check? {
        v ?: return null
        val t = "%.1f V".format(Locale.US, v)
        return if (running) when {
            v in 13.5..14.8 -> Check("battery", "Charging", Verdict.OK, "$t - the alternator charges well")
            v in 13.2..15.0 -> Check("battery", "Charging", Verdict.WARN, "$t - " + if (v > 14.8) "on the high side" else "on the low side")
            else -> Check("battery", "Charging", Verdict.BAD, "$t - " + if (v > 15.0) "too high (voltage regulator?)" else "too low: the alternator isn't keeping up")
        } else when {
            v >= 12.5 -> Check("battery", "Battery", Verdict.OK, "$t at rest - well charged")
            v >= 12.2 -> Check("battery", "Battery", Verdict.WARN, "$t at rest - half charged; a longer drive helps")
            else -> Check("battery", "Battery", Verdict.BAD, "$t at rest - low; may not start on a cold morning")
        }
    }

    /** Short + long term fuel trim, judged only when it means something (warm engine, closed loop). */
    private fun trim(s: CarSnapshot, id: String, title: String): Check? {
        val st = s.extra[0x06]; val lt = s.extra[0x07]
        if (st == null && lt == null) return null
        if ((s.coolantC ?: 0) < 70) return Check(id, title, Verdict.INFO, "engine not warm yet - judged once it's above 70 °C")
        return trimCheck(id, title, (st ?: 0.0) + (lt ?: 0.0), lt)
    }

    fun trimCheck(id: String, title: String, total: Double, lt: Double?): Check {
        val t = "%+.0f%%".format(Locale.US, total) + (lt?.let { " (long-term %+.0f%%)".format(Locale.US, it) } ?: "")
        val side = if (total > 0) "adding fuel: running lean (air leak, weak fuel pump, dirty air flow sensor)" else "taking fuel away: running rich"
        return when {
            abs(total) <= 10 -> Check(id, title, Verdict.OK, "$t - the mixture is right")
            abs(total) <= 20 -> Check(id, title, Verdict.WARN, "$t - $side")
            else -> Check(id, title, Verdict.BAD, "$t - strongly $side")
        }
    }

    /** Engine off and cold: coolant, intake and outside temperature should agree (sensors read true). */
    private fun temps(s: CarSnapshot): Check? {
        if (s.engineRunning) return null
        val c = s.coolantC ?: return null
        val iat = s.extra[0x0F]?.toInt(); val amb = s.ambientC
        val all = listOfNotNull(c, iat, amb)
        if (all.size < 2 || c > 40) return null   // a warm engine: they can't agree yet
        val spread = all.max() - all.min()
        val list = "coolant $c °C" + (iat?.let { ", intake $it °C" } ?: "") + (amb?.let { ", outside $it °C" } ?: "")
        return if (spread <= 8) Check("temps", "Temperature sensors", Verdict.OK, "$list - they agree")
        else Check("temps", "Temperature sensors", Verdict.WARN, "$list - $spread °C apart on a cold engine: one sensor may read wrong")
    }

    private fun coolant(s: CarSnapshot): Check? {
        val c = s.coolantC ?: return null
        if (!s.engineRunning) return null
        val runS = s.runtimeS ?: s.extra[0x1F]?.toInt()
        return when {
            c > 110 -> Check("coolant", "Engine temperature", Verdict.BAD, "$c °C - too hot: stop and let it cool, check the coolant level")
            c >= 80 -> Check("coolant", "Engine temperature", Verdict.OK, "$c °C - at working temperature")
            runS != null && runS > 20 * 60 -> Check("coolant", "Engine temperature", Verdict.WARN,
                "$c °C after ${runS / 60} min running - slow to warm up (thermostat stuck open?)")
            else -> Check("coolant", "Engine temperature", Verdict.INFO, "$c °C - still warming up")
        }
    }

    fun catalystCheck(c: OnBoardTests.Catalyst?): Check = when {
        c == null -> Check("cat", "Catalytic converter", Verdict.INFO,
            "the car hasn't run its own test yet this drive (it does on a steady warm cruise)")
        c.passes -> Check("cat", "Catalytic converter", Verdict.OK, "passed the car's own test (${(c.closeness * 100).toInt()}% of the limit)")
        else -> Check("cat", "Catalytic converter", Verdict.BAD, "failed the car's own test")
    }

    fun misfireCheck(counts: Map<Int, Int>): Check? {
        if (counts.isEmpty()) return null
        val list = counts.toSortedMap().entries.joinToString(", ") { "cyl ${it.key}: ${it.value}" }
        val worst = counts.values.max()
        return when {
            worst >= 50 -> Check("misfire", "Misfires", Verdict.BAD, "$list - a cylinder misfires a lot (spark plug, coil?)")
            worst >= 10 -> Check("misfire", "Misfires", Verdict.WARN, "$list - some misfires counted")
            else -> Check("misfire", "Misfires", Verdict.OK, "$list")
        }
    }

    /** Mode 06 misfire monitors: MID A2..A9 = cylinder 1..8; test 0x0B = average over recent drives. */
    fun misfires(results: List<OnBoardTests.Result>): Map<Int, Int> =
        results.filter { it.mid in 0xA2..0xA9 && it.tid == 0x0B }.associate { (it.mid - 0xA1) to it.value }

    // --- Full test ---------------------------------------------------------------------------------

    enum class Step(val seconds: Int, val title: String, val todo: String) {
        IDLE(60, "Idle", "Leave the engine idling. Don't touch the pedals. Lights, fan and air-con off."),
        LOAD(30, "Electrical load", "Switch on the headlights, the rear window heater and the fan on full. Keep idling."),
        REV(30, "Hold 2500 rpm", "Switch the extras off again. Now hold the engine at about 2500 rpm with the pedal."),
        SNAP(15, "Quick tap", "Let it return to idle, then press the pedal quickly once (to about 3500 rpm) and let go."),
    }

    /** Readings collected during one step. */
    class Samples {
        val all = ArrayList<CarSnapshot>()
        fun values(pid: Int) = all.mapNotNull { it.extra[pid] }
        val rpms get() = all.mapNotNull { it.rpm?.toDouble() }
        val volts get() = all.mapNotNull { it.volts }
    }

    class FullTest {
        val steps = Step.values().associateWith { Samples() }
        var aborted: String? = null; private set

        /** One reading during [step]. False (and the test stops) when the car moves. */
        fun add(step: Step, s: CarSnapshot): Boolean {
            if ((s.speedKmh ?: 0) > 3) { aborted = "the car moved - the full test only runs parked"; return false }
            steps[step]!!.all += s
            return true
        }

        /** The verdicts, from what each step collected (steps without readings are left out). */
        fun evaluate(): List<Check> {
            val out = ArrayList<Check>()
            val idle = steps[Step.IDLE]!!; val load = steps[Step.LOAD]!!; val rev = steps[Step.REV]!!; val snap = steps[Step.SNAP]!!
            val warm = (idle.all.lastOrNull()?.coolantC ?: 0) >= 75
            if (idle.all.isNotEmpty() && !warm) out += Check("warm", "Engine temperature", Verdict.INFO,
                "${idle.all.last().coolantC ?: "?"} °C - best done warm; mixture numbers are only judged above 75 °C")
            idleSteadiness(idle.rpms)?.let { out += it }
            if (warm) {
                val idleTrim = trimOf(idle); val revTrim = trimOf(rev)
                idleTrim?.let { out += trimCheck("trim_idle", "Mixture at idle", it, idle.values(0x07).medianOrNull()) }
                revTrim?.let { out += trimCheck("trim_2500", "Mixture at 2500 rpm", it, rev.values(0x07).medianOrNull()) }
                if (idleTrim != null && revTrim != null) leakCheck(idleTrim, revTrim)?.let { out += it }
                o2(idle.values(0x14), idle.values(0x15))?.let { out += it }
            }
            idle.values(0x10).medianOrNull()?.let { maf ->
                out += if (maf in 1.5..4.5) Check("maf", "Air flow at idle", Verdict.OK, "%.1f g/s".format(Locale.US, maf))
                else Check("maf", "Air flow at idle", Verdict.WARN, "%.1f g/s - expected 1.5-4.5 for a 1.6 (dirty or faulty air flow sensor, or an air leak)".format(Locale.US, maf))
            }
            loadCheck(load.volts, idle.volts)?.let { out += it }
            snapCheck(snap.rpms, idle.rpms.medianOrNull())?.let { out += it }
            return out
        }

        private fun trimOf(s: Samples): Double? {
            val st = s.values(0x06).medianOrNull(); val lt = s.values(0x07).medianOrNull()
            return if (st == null && lt == null) null else (st ?: 0.0) + (lt ?: 0.0)
        }
    }

    fun idleSteadiness(rpms: List<Double>): Check? {
        if (rpms.size < 5) return null
        val med = rpms.medianOrNull()!!
        val sd = sqrt(rpms.map { (it - med) * (it - med) }.average())
        val t = "%.0f rpm, varies ±%.0f".format(Locale.US, med, sd)
        return when {
            med !in 600.0..900.0 && med > 0 -> Check("idle", "Idle", Verdict.WARN, "$t - " + if (med > 900) "high (still cold, or an air leak)" else "low")
            sd <= 25 -> Check("idle", "Idle", Verdict.OK, "$t - smooth")
            sd <= 50 -> Check("idle", "Idle", Verdict.WARN, "$t - a little uneven")
            else -> Check("idle", "Idle", Verdict.BAD, "$t - rough (misfire, air leak, dirty throttle body?)")
        }
    }

    /** Lean at idle but fine at 2500 rpm is the classic sign of an air leak (it matters less as air flow rises). */
    fun leakCheck(idleTrim: Double, revTrim: Double): Check? =
        if (idleTrim > 10 && revTrim < 5) Check("leak", "Air leak", Verdict.WARN,
            "lean at idle (%+.0f%%) but fine at 2500 rpm (%+.0f%%) - a vacuum/air leak is likely".format(Locale.US, idleTrim, revTrim))
        else null

    fun o2(front: List<Double>, rear: List<Double>): Check? {
        if (front.size < 5) return null
        val fr = front.max() - front.min()
        val f = "front sensor %.2f-%.2f V".format(Locale.US, front.min(), front.max())
        if (fr < 0.3) return Check("o2", "Oxygen sensors", Verdict.WARN, "$f - hardly moving: lazy front sensor (or the car uses a different kind)")
        if (rear.size < 5) return Check("o2", "Oxygen sensors", Verdict.OK, "$f - switching as it should")
        val rr = rear.max() - rear.min()
        val r = "rear %.2f-%.2f V".format(Locale.US, rear.min(), rear.max())
        return if (rr <= 0.3 || fr < 0.5) Check("o2", "Oxygen sensors", Verdict.OK, "$f, $r - the catalytic converter is doing its job")
        else Check("o2", "Oxygen sensors", Verdict.WARN, "$f, $r - the rear follows the front: the catalytic converter may be tired")
    }

    fun loadCheck(load: List<Double>, idle: List<Double>): Check? {
        if (load.size < 3) return null
        val min = load.min()
        val before = idle.medianOrNull()
        val t = "%.1f V with everything on".format(Locale.US, min) + (before?.let { " (%.1f V before)".format(Locale.US, it) } ?: "")
        return when {
            min >= 13.2 -> Check("load", "Charging under load", Verdict.OK, "$t - the alternator keeps up")
            min >= 12.8 -> Check("load", "Charging under load", Verdict.WARN, "$t - only just keeping up")
            else -> Check("load", "Charging under load", Verdict.BAD, "$t - the alternator can't keep up (belt, alternator)")
        }
    }

    fun snapCheck(rpms: List<Double>, idleRpm: Double?): Check? {
        if (rpms.size < 4) return null
        val peak = rpms.max()
        if (peak < 2_500) return Check("snap", "Throttle response", Verdict.INFO, "the tap wasn't seen (peak ${peak.toInt()} rpm) - try a firmer, longer press")
        val after = rpms.drop(rpms.indexOf(peak) + 1)
        val settled = after.lastOrNull()
        return if (idleRpm != null && settled != null && settled > idleRpm + 250)
            Check("snap", "Throttle response", Verdict.WARN, "revved to ${peak.toInt()} rpm but stayed at ${settled.toInt()} - slow to return to idle")
        else Check("snap", "Throttle response", Verdict.OK, "revved to ${peak.toInt()} rpm and came back to idle")
    }

    // --- History -----------------------------------------------------------------------------------

    const val KEEP = 10

    fun encode(results: List<Result>): String = JSONArray().apply {
        results.takeLast(KEEP).forEach { r ->
            put(JSONObject().put("at", r.at).put("full", r.full).put("c", JSONArray().apply {
                r.checks.forEach { c -> put(JSONObject().put("id", c.id).put("t", c.title).put("v", c.verdict.name).put("d", c.detail)) }
            }))
        }
    }.toString()

    fun decode(s: String?): List<Result> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val cs = o.optJSONArray("c") ?: JSONArray()
                Result(o.optLong("at"), o.optBoolean("full"), (0 until cs.length()).mapNotNull { j ->
                    val c = cs.optJSONObject(j) ?: return@mapNotNull null
                    val v = runCatching { Verdict.valueOf(c.optString("v")) }.getOrNull() ?: return@mapNotNull null
                    Check(c.optString("id"), c.optString("t"), v, c.optString("d"))
                })
            }
        }.getOrDefault(emptyList())
    }

    private fun List<Double>.medianOrNull(): Double? = if (isEmpty()) null else sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
