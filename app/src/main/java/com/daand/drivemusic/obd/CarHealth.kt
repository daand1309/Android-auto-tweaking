package com.daand.drivemusic.obd

/** One reading of the car. null = not known (yet) or not supported by this car. */
data class CarSnapshot(
    val at: Long,
    val coolantC: Int? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val fuelPct: Int? = null,
    val volts: Double? = null,
    val ambientC: Int? = null,
    val checkEngine: Boolean? = null,
    val codes: List<String> = emptyList(),
    /** Seconds since the engine started (from the car, if it reports it). */
    val runtimeS: Int? = null,
) {
    val engineRunning get() = (rpm ?: 0) > 400
}

/** Plain-language explanations for the most common generic fault codes. */
object TroubleCodes {
    private val EN = mapOf(
        "P0011" to "camshaft timing is off", "P0016" to "camshaft and crankshaft are out of sync",
        "P0100" to "air flow sensor fault", "P0101" to "air flow sensor reading is implausible",
        "P0102" to "air flow sensor signal low", "P0106" to "intake pressure sensor reading is implausible",
        "P0113" to "intake air temperature sensor signal high", "P0115" to "coolant temperature sensor fault",
        "P0117" to "coolant temperature sensor signal low", "P0118" to "coolant temperature sensor signal high",
        "P0125" to "engine takes too long to warm up", "P0128" to "the thermostat may be stuck open",
        "P0130" to "oxygen sensor fault", "P0131" to "oxygen sensor signal low", "P0133" to "oxygen sensor is slow to respond",
        "P0134" to "no signal from the oxygen sensor", "P0135" to "oxygen sensor heater fault",
        "P0141" to "rear oxygen sensor heater fault", "P0171" to "engine running too lean",
        "P0172" to "engine running too rich", "P0300" to "random misfires",
        "P0301" to "misfire in cylinder 1", "P0302" to "misfire in cylinder 2", "P0303" to "misfire in cylinder 3",
        "P0304" to "misfire in cylinder 4", "P0325" to "knock sensor fault", "P0335" to "crankshaft sensor fault",
        "P0340" to "camshaft sensor fault", "P0400" to "exhaust gas recirculation fault",
        "P0401" to "exhaust gas recirculation flow too low", "P0420" to "the catalytic converter is underperforming",
        "P0441" to "fuel vapour system flow problem", "P0442" to "small leak in the fuel vapour system",
        "P0455" to "large leak in the fuel vapour system, check the fuel cap", "P0456" to "very small fuel vapour leak, check the fuel cap",
        "P0500" to "vehicle speed sensor fault", "P0505" to "idle control problem", "P0507" to "idle speed higher than expected",
        "P0562" to "system voltage is low", "P0563" to "system voltage is high", "P0601" to "engine computer memory error",
        "P0700" to "the gearbox has a fault stored", "P0705" to "gear selector sensor fault",
    )
    private val NL = mapOf(
        "P0011" to "de nokkenas-timing klopt niet", "P0016" to "nokkenas en krukas lopen niet gelijk",
        "P0100" to "storing in de luchtmassameter", "P0101" to "de luchtmassameter geeft een onlogische waarde",
        "P0102" to "signaal luchtmassameter te laag", "P0106" to "inlaatdruksensor geeft een onlogische waarde",
        "P0113" to "signaal inlaatluchttemperatuur te hoog", "P0115" to "storing in de koelvloeistoftemperatuursensor",
        "P0117" to "signaal koelvloeistoftemperatuur te laag", "P0118" to "signaal koelvloeistoftemperatuur te hoog",
        "P0125" to "de motor wordt te langzaam warm", "P0128" to "de thermostaat blijft mogelijk openstaan",
        "P0130" to "storing in de lambdasonde", "P0131" to "signaal lambdasonde te laag", "P0133" to "de lambdasonde reageert traag",
        "P0134" to "geen signaal van de lambdasonde", "P0135" to "storing in de verwarming van de lambdasonde",
        "P0141" to "storing in de verwarming van de achterste lambdasonde", "P0171" to "het mengsel is te arm",
        "P0172" to "het mengsel is te rijk", "P0300" to "willekeurige overslagen",
        "P0301" to "overslag in cilinder 1", "P0302" to "overslag in cilinder 2", "P0303" to "overslag in cilinder 3",
        "P0304" to "overslag in cilinder 4", "P0325" to "storing in de klopsensor", "P0335" to "storing in de krukassensor",
        "P0340" to "storing in de nokkenassensor", "P0400" to "storing in de uitlaatgasrecirculatie",
        "P0401" to "te weinig uitlaatgasrecirculatie", "P0420" to "de katalysator werkt niet goed genoeg",
        "P0441" to "probleem in het dampsysteem van de brandstof", "P0442" to "klein lek in het brandstofdampsysteem",
        "P0455" to "groot lek in het brandstofdampsysteem, controleer de tankdop", "P0456" to "heel klein dampleek, controleer de tankdop",
        "P0500" to "storing in de snelheidssensor", "P0505" to "probleem met de stationairregeling", "P0507" to "stationair toerental hoger dan verwacht",
        "P0562" to "de boordspanning is te laag", "P0563" to "de boordspanning is te hoog", "P0601" to "geheugenfout in de motorcomputer",
        "P0700" to "de versnellingsbak heeft een storing opgeslagen", "P0705" to "storing in de sensor van de keuzehendel",
    )

    /** "P0420" -> "the catalytic converter is underperforming"; unknown codes get a generic phrase. */
    fun explain(code: String, dutch: Boolean): String = (if (dutch) NL else EN)[code] ?: when {
        code.length == 5 && code[1] == '1' -> if (dutch) "een Mitsubishi-specifieke code" else "a Mitsubishi-specific code"
        else -> if (dutch) "een storing die ik niet ken" else "a fault I don't have a description for"
    }

    /** Codes read out letter by letter, so "P0420" doesn't become "P four hundred and twenty". */
    fun spoken(code: String) = code.toCharArray().joinToString(" ")
}

/**
 * When to say something about the car - once per drive per topic, so it never nags.
 * Pure state machine (unit tested): feed it snapshots, it returns what's worth saying.
 */
class CarAlerts(
    /** How long the engine should idle after a cold start before driving off (your setting). */
    private val readySeconds: Int = 30,
    /**
     * Codes (and "MIL" for a light without codes) already told recently - from earlier drives. A
     * light that's been on for weeks shouldn't be announced like news at every start.
     */
    toldRecently: Set<String> = emptySet(),
) {
    enum class Kind { READY_TO_DRIVE, OVERHEAT, CHECK_ENGINE, LOW_BATTERY, WEAK_CHARGING, ENGINE_WARM, LOW_FUEL }
    /** [timely] = only useful right now (say it as soon as possible, or not at all). */
    data class Alert(val kind: Kind, val urgent: Boolean, val value: Double? = null, val codes: List<String> = emptyList(),
                     val timely: Boolean = false)

    private var coldAtStart: Boolean? = null
    private var warmDone = false
    private var fuelDone = false
    private var batteryDone = false
    private var chargingDone = false
    private var lastOverheatAt = 0L
    private val reportedCodes = toldRecently.toMutableSet()
    private var engineEverRan = false
    private var lowBatterySince: Long? = null
    private var hotReadings = 0
    private var lowChargeSince: Long? = null
    private var lastCheckEngine = false
    private var readyDone = false
    private var engineSeen = false
    private var engineSeenAt = 0L

    fun check(s: CarSnapshot): List<Alert> {
        val out = mutableListOf<Alert>()
        readyToDrive(s)?.let { out += it }
        if (s.engineRunning) engineEverRan = true
        // A corrupt byte can read as 215 °C: ignore impossible values, and want two hot readings in a row.
        s.coolantC?.takeIf { it in -40..MAX_PLAUSIBLE_C }?.let { c ->
            if (coldAtStart == null) coldAtStart = c < 50
            hotReadings = if (c >= OVERHEAT_C) hotReadings + 1 else 0
            if (hotReadings >= 2 && s.at - lastOverheatAt > 5 * 60_000L) {
                lastOverheatAt = s.at; out += Alert(Kind.OVERHEAT, urgent = true, value = c.toDouble())
            }
            if (!warmDone && coldAtStart == true && c >= WARM_C) { warmDone = true; out += Alert(Kind.ENGINE_WARM, false, c.toDouble()) }
        }
        val newCodes = s.codes.filter { it !in reportedCodes }
        val lightJustCameOn = s.checkEngine == true && !lastCheckEngine
        if (newCodes.isNotEmpty()) {
            reportedCodes += newCodes
            out += Alert(Kind.CHECK_ENGINE, urgent = true, codes = newCodes)
        } else if (lightJustCameOn && s.codes.isEmpty() && MIL !in reportedCodes) {
            reportedCodes += MIL
            out += Alert(Kind.CHECK_ENGINE, urgent = true, codes = emptyList())
        }
        if (s.checkEngine != null) lastCheckEngine = s.checkEngine
        s.volts?.let { v ->
            // Resting voltage only means something before the engine has run: restarting after a stall
            // dips to ~10 V for a moment. Two low readings 5+ s apart, so one sample mid-crank can't trigger it.
            if (!s.engineRunning && !engineEverRan && !batteryDone && v < LOW_BATTERY_V) {
                val since = lowBatterySince
                if (since == null) lowBatterySince = s.at
                else if (s.at - since >= 5_000L) { batteryDone = true; out += Alert(Kind.LOW_BATTERY, false, v) }
            } else if (v >= LOW_BATTERY_V) lowBatterySince = null
            if (!s.engineRunning) lowChargeSince = null
            if (s.engineRunning) {
                if (v < WEAK_CHARGE_V) {
                    val since = lowChargeSince ?: s.at.also { lowChargeSince = it }
                    if (!chargingDone && s.at - since >= 60_000L) { chargingDone = true; out += Alert(Kind.WEAK_CHARGING, false, v) }
                } else lowChargeSince = null
            }
        }
        s.fuelPct?.let { f -> if (!fuelDone && f in 1 until LOW_FUEL_PCT) { fuelDone = true; out += Alert(Kind.LOW_FUEL, false, f.toDouble()) } }
        return out
    }

    /**
     * "Oil has had its moment": the engine has idled long enough after a cold start. Uses the car's
     * own "seconds since start" when it reports it (Android Auto and the adapter connect a few seconds
     * after you start the engine), otherwise our own clock from the first running reading.
     */
    private fun readyToDrive(s: CarSnapshot): Alert? {
        if (readyDone || !s.engineRunning) return null
        if (!engineSeen) {
            engineSeen = true
            engineSeenAt = s.at
            // Warm engine (short stop) or already well past the wait when we connected: nothing to say.
            if ((s.coolantC ?: 0) >= WARM_RESTART_C || (s.runtimeS ?: 0) > 3 * 60) { readyDone = true; return null }
        }
        if ((s.speedKmh ?: 0) > 5) { readyDone = true; return null }  // already driving: too late to be useful
        val need = waitFor(s.coolantC, s.ambientC, readySeconds)
        val ran = s.runtimeS ?: ((s.at - engineSeenAt) / 1000).toInt()
        if (ran < need) return null
        readyDone = true
        return Alert(Kind.READY_TO_DRIVE, urgent = false, value = need.toDouble(), timely = true)
    }

    /** Codes told this drive (to remember across drives). */
    fun told(): Set<String> = reportedCodes.toSet()

    /** True while we're still counting down (the manager polls faster then). */
    fun countingDown() = engineSeen && !readyDone

    companion object {
        /** Key for "light on, but no code read" in the told-codes memory. */
        const val MIL = "MIL"
        /** Readings above this are a garbled answer, not a temperature. */
        const val MAX_PLAUSIBLE_C = 150
        /** Known codes are mentioned again after this long (until fixed). */
        const val REMIND_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** Coolant at or above this when the engine starts = it was only a short stop. */
        const val WARM_RESTART_C = 60

        /** Your setting, but longer when it's cold: at least 45 s near freezing, 60 s below -5 °C. */
        fun waitFor(coolantC: Int?, ambientC: Int?, setting: Int): Int {
            val coldest = listOfNotNull(coolantC, ambientC).minOrNull() ?: return setting
            return when {
                coldest < -5 -> maxOf(setting, 60)
                coldest < 3 -> maxOf(setting, 45)
                else -> setting
            }
        }

        const val OVERHEAT_C = 110
        const val WARM_C = 75
        const val LOW_BATTERY_V = 12.0     // engine off: a healthy battery rests around 12.4-12.7 V
        const val WEAK_CHARGE_V = 12.8     // engine running: the alternator should give ~13.5-14.7 V
        const val LOW_FUEL_PCT = 12

        /** What the voice says for an alert. */
        fun message(a: Alert, dutch: Boolean, butler: Boolean): String {
            fun n(v: Double?) = v?.let { Math.round(it).toString() } ?: ""
            fun volts(v: Double?) = v?.let { "%.1f".format(java.util.Locale.ROOT, it).replace(".", if (dutch) " komma " else " point ") } ?: ""
            return when (a.kind) {
                Kind.OVERHEAT -> when {
                    dutch -> "Let op: de motor wordt te heet, ${n(a.value)} graden. Zet de auto zo snel het veilig kan aan de kant en laat hem afkoelen."
                    butler -> "Sir, the engine is running hot: ${n(a.value)} degrees. Please pull over as soon as it is safe and let it cool."
                    else -> "Heads up: the engine is running hot, ${n(a.value)} degrees. Pull over when it's safe and let it cool down."
                }
                Kind.CHECK_ENGINE -> {
                    val list = a.codes.take(3).joinToString(if (dutch) ". En " else ". And ") {
                        "${TroubleCodes.spoken(it)}: ${TroubleCodes.explain(it, dutch)}"
                    }
                    when {
                        dutch && list.isEmpty() -> "Het motorstoringslampje is aangegaan. Laat het binnenkort even nakijken."
                        dutch -> "Het motorstoringslampje is aan. Code $list. Laat het binnenkort even nakijken."
                        butler && list.isEmpty() -> "Pardon me, sir. The engine warning light has come on. Worth having it looked at soon."
                        butler -> "Pardon me, sir. The engine warning light is on. Code $list. Worth having it looked at soon."
                        list.isEmpty() -> "The check-engine light just came on. Worth getting it looked at soon."
                        else -> "The check-engine light is on. Code $list. Worth getting it looked at soon."
                    }
                }
                Kind.LOW_BATTERY -> when {
                    dutch -> "De accu staat wat laag, ${volts(a.value)} volt. Misschien is hij aan vervanging toe."
                    butler -> "The battery is looking rather tired, sir: ${volts(a.value)} volts. It may be due for retirement."
                    else -> "The battery is a bit low: ${volts(a.value)} volts. It might be getting old."
                }
                Kind.WEAK_CHARGING -> when {
                    dutch -> "De accu wordt niet goed geladen, maar ${volts(a.value)} volt terwijl de motor draait. Laat de dynamo eens controleren."
                    butler -> "The battery is not charging properly: only ${volts(a.value)} volts with the engine running. The alternator may want a look."
                    else -> "The battery isn't charging properly: only ${volts(a.value)} volts with the engine running. Have the alternator checked."
                }
                Kind.READY_TO_DRIVE -> when {
                    dutch && butler -> "De olie is rondgepompt, meneer. U kunt rustig wegrijden."
                    dutch -> "De olie is rond. Je kunt rustig wegrijden."
                    butler -> "The oil has had its moment, sir. You may proceed. Gently, for the first few minutes."
                    else -> "Oil's had its moment. You're good to go, take it easy for the first few minutes."
                }
                Kind.ENGINE_WARM -> when {
                    dutch -> "De motor is op temperatuur."
                    butler -> "The engine is warm now, sir. Do enjoy yourself."
                    else -> "Engine's warmed up. Go ahead and enjoy it."
                }
                Kind.LOW_FUEL -> when {
                    dutch -> "De tank raakt leeg, nog ongeveer ${n(a.value)} procent."
                    butler -> "We are running low on fuel, sir. About ${n(a.value)} percent remains."
                    else -> "Fuel's getting low, about ${n(a.value)} percent left."
                }
            }
        }
    }
}
