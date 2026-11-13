package com.daand.drivemusic.obd

import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

/**
 * Early-warning health numbers for one drive, measured only in comparable conditions so drives can be
 * compared with each other:
 * - long-term fuel trim (warm engine): how much the engine computer corrects the fuel mixture;
 * - charging voltage (engine 1200-3500 rpm): how well the alternator charges;
 * - idle steadiness (warm, standing still): the spread of the idle revs;
 * - warm-up time (cold start, engine running): minutes from ≤35 °C to 80 °C;
 * - coolant on the road (warm, ≥60 km/h): how hot it runs while cruising.
 */
data class HealthSample(
    val ltft: Double? = null,
    val chargeV: Double? = null,
    val idleRpm: Double? = null,
    val idleSpread: Double? = null,
    val warmupMin: Double? = null,
    val cruiseCoolant: Double? = null,
    val ambient: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        ltft?.let { put("ltft", it) }; chargeV?.let { put("v", it) }; idleRpm?.let { put("idle", it) }
        idleSpread?.let { put("idleSd", it) }; warmupMin?.let { put("warm", it) }; cruiseCoolant?.let { put("cool", it) }
        ambient?.let { put("amb", it) }
    }

    val isEmpty get() = listOf(ltft, chargeV, idleSpread, warmupMin, cruiseCoolant).all { it == null }

    companion object {
        fun fromJson(o: JSONObject?): HealthSample? {
            if (o == null) return null
            fun d(k: String, range: ClosedFloatingPointRange<Double>) =
                if (o.has(k)) o.optDouble(k).takeIf { it.isFinite() && it in range } else null
            return HealthSample(d("ltft", -50.0..50.0), d("v", 8.0..16.0), d("idle", 300.0..2000.0), d("idleSd", 0.0..500.0),
                d("warm", 0.0..120.0), d("cool", 40.0..130.0), if (o.has("amb")) o.optInt("amb").takeIf { it in -40..60 } else null)
        }
    }
}

/** Collects the [HealthSample] numbers during a drive. Pure, unit tested. */
class HealthStats {
    private val ltft = ArrayList<Double>()
    private val charge = ArrayList<Double>()
    private val idle = ArrayList<Double>()
    private val cruise = ArrayList<Double>()
    private var ambient: Int? = null
    private var firstCoolant: Int? = null
    private var warmRunSeconds = 0.0
    private var warmupMin: Double? = null
    private var warmupDone = false

    fun add(s: CarSnapshot, dt: Double) {
        val c = s.coolantC?.takeIf { it in -40..CarAlerts.MAX_PLAUSIBLE_C }
        if (firstCoolant == null && c != null) firstCoolant = c
        if (ambient == null) ambient = s.ambientC
        if (!s.engineRunning) return
        val warm = c != null && c >= WARM_C
        val rpm = s.rpm ?: 0
        val speed = s.speedKmh
        if (warm) s.extra[0x07]?.takeIf { it in -50.0..50.0 }?.let { ltft += it }
        if (rpm in 1200..3500) s.volts?.takeIf { it in 8.0..16.0 }?.let { charge += it }
        if (warm && speed == 0 && rpm in 500..1300) idle += rpm.toDouble()
        if (warm && speed != null && speed >= 60) cruise += c!!.toDouble()
        // Warm-up: only from a real cold start, counting engine-running time.
        if (!warmupDone && (firstCoolant ?: 99) <= COLD_START_C) {
            if (dt in 0.0..15.0) warmRunSeconds += dt
            if (c != null && c >= WARM_C) { warmupDone = true; warmupMin = warmRunSeconds / 60 }
        }
    }

    fun sample(): HealthSample {
        fun med(l: List<Double>, min: Int) = if (l.size >= min) median(l) else null
        val idleMean = if (idle.size >= MIN_IDLE) idle.average() else null
        val idleSd = idleMean?.let { m -> sqrt(idle.sumOf { (it - m) * (it - m) } / idle.size) }
        return HealthSample(med(ltft, 3)?.round1(), med(charge, 5)?.round2(), idleMean?.round1(), idleSd?.round1(),
            warmupMin?.round1(), med(cruise, 5)?.round1(), ambient)
    }

    companion object {
        const val WARM_C = 80
        const val COLD_START_C = 35
        const val MIN_IDLE = 12
    }
}

internal fun median(l: List<Double>): Double {
    val s = l.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
private fun Double.round1() = Math.round(this * 10) / 10.0
private fun Double.round2() = Math.round(this * 100) / 100.0

/**
 * Spots slow changes before they become fault codes, by comparing your last few drives with what's
 * normal for YOUR car (the drives before), plus a few absolute limits. Pure, unit tested.
 */
object CarTrends {
    enum class Kind { LEAN, RICH, CHARGING, ROUGH_IDLE, SLOW_WARMUP, RUNNING_HOTTER }

    /** [now] = recent median, [usual] = your car's normal before (null if not known yet). */
    data class Finding(val kind: Kind, val now: Double, val usual: Double?)

    /** The same finding is told again at most every 2 weeks. */
    const val REMIND_AFTER_MS = 14 * 86_400_000L

    /** The last finding of each kind, for the greeting (it only has the kind names). */
    fun latest(history: List<HealthSample>, kinds: Collection<String>): List<Finding> =
        findings(history).filter { it.kind.name in kinds }

    private const val RECENT = 5
    private const val MIN_RECENT = 3
    private const val BASELINE = 15
    private const val MIN_BASELINE = 5

    private class Split(val recent: List<Double>, val baseline: List<Double>) {
        val ok get() = recent.size >= MIN_RECENT
        val hasBaseline get() = baseline.size >= MIN_BASELINE
        val now get() = median(recent)
        val usual get() = if (hasBaseline) median(baseline) else null
    }

    private fun split(samples: List<HealthSample>, pick: (HealthSample) -> Double?): Split {
        val values = samples.mapNotNull(pick)
        val recent = values.takeLast(RECENT)
        val baseline = values.dropLast(recent.size).takeLast(BASELINE)
        return Split(recent, baseline)
    }

    /** [history]: oldest first. */
    fun findings(history: List<HealthSample>): List<Finding> {
        val out = ArrayList<Finding>()
        // Fuel trim: the car's own fault codes (P0171/P0172) only trigger around ±25%. A steady +10%
        // or a big shift from your normal is an air leak / sensor going off, long before that.
        val trim = split(history) { it.ltft }
        if (trim.ok) {
            val n = trim.now; val u = trim.usual
            val drift = if (u != null) n - u else 0.0
            if (n >= 10 || (n >= 6 && drift >= 7)) out += Finding(Kind.LEAN, n, u)
            else if (n <= -10 || (n <= -6 && drift <= -7)) out += Finding(Kind.RICH, n, u)
        }
        // Charging: a healthy alternator gives ~13.8-14.6 V. Sagging is a tired alternator, regulator or belt.
        val v = split(history) { it.chargeV }
        if (v.ok) {
            val n = v.now; val u = v.usual
            if (n < 13.4 || (u != null && u - n >= 0.4 && n < 13.9)) out += Finding(Kind.CHARGING, n, u)
        }
        // Idle getting shaky: often spark plugs or an ignition coil, before a misfire code appears.
        val idle = split(history) { it.idleSpread }
        if (idle.ok) {
            val n = idle.now; val u = idle.usual
            if (n >= 60 || (u != null && n >= 35 && n >= u * 1.8)) out += Finding(Kind.ROUGH_IDLE, n, u)
        }
        // Warm-up slower than it used to be (in similar weather): thermostat starting to stick open.
        val warmSamples = history.filter { it.warmupMin != null }
        val w = split(warmSamples) { it.warmupMin }
        if (w.ok && w.hasBaseline) {
            val ambRecent = warmSamples.takeLast(w.recent.size).mapNotNull { it.ambient?.toDouble() }
            val ambBase = warmSamples.dropLast(w.recent.size).takeLast(BASELINE).mapNotNull { it.ambient?.toDouble() }
            val colderNow = ambRecent.size >= 2 && ambBase.size >= 2 && median(ambRecent) < median(ambBase) - 5
            val n = w.now; val u = w.usual!!
            if (!colderNow && n >= 12 && n >= u * 1.6) out += Finding(Kind.SLOW_WARMUP, n, u)
        }
        // Running hotter on the road than it used to: coolant level, radiator, fan.
        val hot = split(history) { it.cruiseCoolant }
        if (hot.ok && hot.hasBaseline) {
            val n = hot.now; val u = hot.usual!!
            if (n >= 98 && n - u >= 6) out += Finding(Kind.RUNNING_HOTTER, n, u)
        }
        return out
    }

    private fun f0(x: Double) = Math.round(x).toString()
    private fun f1(x: Double) = "%.1f".format(Locale.ROOT, x)
    private fun pct(x: Double) = (if (x > 0) "+" else "") + f0(x) + "%"

    /** Spoken / shown text: what it is, what it usually means, what to do. */
    fun message(f: Finding, dutch: Boolean, butler: Boolean): String {
        val sir = if (butler && !dutch) ", sir" else ""
        val was = { unit: (Double) -> String -> f.usual?.let { if (dutch) " (normaal ${unit(it)})" else " (usually ${unit(it)})" } ?: "" }
        return when (f.kind) {
            Kind.LEAN -> if (dutch)
                "Vroege waarschuwing: de motor spuit de laatste ritten extra brandstof bij, ${pct(f.now)}${was(::pct)}. Vaak een luchtlek of een vuile luchtmassameter. Nog geen storingslampje, maar laat het bij de volgende beurt nakijken."
            else "An early heads-up$sir: lately the engine has been adding extra fuel, ${pct(f.now)}${was(::pct)}. That often means an air leak or a dirty air-flow sensor. No warning light yet, but worth a look at the next service."
            Kind.RICH -> if (dutch)
                "Vroege waarschuwing: de motor haalt de laatste ritten brandstof terug, ${pct(f.now)}${was(::pct)}. Vaak een lekkende injector of een lambdasonde die slijt. Laat het bij de volgende beurt nakijken."
            else "An early heads-up$sir: lately the engine has been taking fuel away, ${pct(f.now)}${was(::pct)}. Often a leaking injector or an ageing oxygen sensor. Worth a look at the next service."
            Kind.CHARGING -> if (dutch)
                "Vroege waarschuwing: de laadspanning is gezakt naar ${f1(f.now)} volt${was { f1(it) + " volt" }}. De dynamo of de V-snaar raakt mogelijk vermoeid."
            else "An early heads-up$sir: the charging voltage has dropped to ${f1(f.now)} volts${was { f1(it) + " volts" }}. The alternator or its belt may be getting tired."
            Kind.ROUGH_IDLE -> if (dutch)
                "Vroege waarschuwing: het stationair toerental is onrustiger dan normaal. Vaak bougies of een bobine die het begint op te geven, nog voordat er een storingscode komt."
            else "An early heads-up$sir: the idle has become less steady than usual. Often spark plugs or an ignition coil starting to fail, before any fault code shows up."
            Kind.SLOW_WARMUP -> if (dutch)
                "Vroege waarschuwing: de motor doet er langer over om warm te worden, ${f0(f.now)} minuten${was { f0(it) + " minuten" }}. De thermostaat blijft mogelijk deels open staan."
            else "An early heads-up$sir: the engine takes longer to warm up, ${f0(f.now)} minutes${was { f0(it) + " minutes" }}. The thermostat may be starting to stick open."
            Kind.RUNNING_HOTTER -> if (dutch)
                "Vroege waarschuwing: de motor wordt onderweg warmer dan normaal, ${f0(f.now)} graden${was { f0(it) + " graden" }}. Controleer het koelvloeistofniveau; radiator of ventilator verdienen een blik."
            else "An early heads-up$sir: the engine runs hotter on the road than it used to, ${f0(f.now)} degrees${was { f0(it) + " degrees" }}. Check the coolant level; the radiator or fan may need a look."
        }
    }

    /** For the Car data page: each tracked number now vs your normal. */
    fun overview(history: List<HealthSample>, dutch: Boolean): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        fun row(en: String, nl: String, pick: (HealthSample) -> Double?, fmt: (Double) -> String) {
            val s = split(history, pick)
            if (s.recent.isEmpty()) return
            val usual = s.usual?.let { (if (dutch) "normaal " else "usually ") + fmt(it) }
            rows += (if (dutch) nl else en) to (fmt(s.now) + (usual?.let { " · $it" } ?: ""))
        }
        row("Fuel trim (long-term)", "Brandstofcorrectie (lang)", { it.ltft }, ::pct)
        row("Charging voltage", "Laadspanning", { it.chargeV }) { f1(it) + " V" }
        row("Idle steadiness", "Rust stationair", { it.idleSpread }) { "±" + f0(it) + " rpm" }
        row("Warm-up time", "Opwarmtijd", { it.warmupMin }) { f0(it) + " min" }
        row("Engine temp on the road", "Motortemp. onderweg", { it.cruiseCoolant }) { f0(it) + " °C" }
        return rows
    }
}
