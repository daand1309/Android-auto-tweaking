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
    /** Every other standard value the car reported, by PID (see [Pids]). */
    val extra: Map<Int, Double> = emptyMap(),
    /** Faults the car has noticed but not confirmed yet (an early warning). */
    val pendingCodes: List<String> = emptyList(),
    /** Faults that stay until the car itself has verified the repair. */
    val permanentCodes: List<String> = emptyList(),
    /** Emissions self-tests: (complete, available), from PID 01. */
    val readiness: Pair<Int, Int>? = null,
    /** Fuel use right now, if it can be worked out. */
    val litresPerHour: Double? = null,
) {
    val engineRunning get() = (rpm ?: 0) > 400
}

/**
 * Fault codes in plain language: what it means, how serious it is, what to do. A table of common
 * codes, rules that decode the code's structure (cylinder, sensor position, coil...), and the code's
 * category for anything else. Pure, unit tested.
 */
object TroubleCodes {
    enum class Severity { INFO, CHECK_SOON, CAREFUL, STOP }

    class Explanation(val what: String, val severity: Severity, val advice: String)

    private fun t(en: String, nl: String, sev: Severity = Severity.CHECK_SOON) = Triple(en, nl, sev)

    private val TABLE: Map<String, Triple<String, String, Severity>> = mapOf(
        "P0010" to t("camshaft timing actuator circuit fault", "storing in de nokkenasverstelling"),
        "P0011" to t("camshaft timing is off (often low or old oil)", "de nokkenas-timing klopt niet (vaak te weinig of oude olie)"),
        "P0016" to t("camshaft and crankshaft are out of sync (timing chain?)", "nokkenas en krukas lopen niet gelijk (distributieketting?)", Severity.CAREFUL),
        "P0030" to t("front oxygen sensor heater fault", "storing in de verwarming van de voorste lambdasonde"),
        "P0100" to t("air flow sensor fault", "storing in de luchtmassameter"),
        "P0101" to t("air flow sensor reading is implausible (dirty sensor or air leak)", "de luchtmassameter geeft een onlogische waarde (vuil of luchtlek)"),
        "P0102" to t("air flow sensor signal low", "signaal luchtmassameter te laag"),
        "P0103" to t("air flow sensor signal high", "signaal luchtmassameter te hoog"),
        "P0106" to t("intake pressure sensor reading is implausible", "inlaatdruksensor geeft een onlogische waarde"),
        "P0107" to t("intake pressure sensor signal low", "signaal inlaatdruksensor te laag"),
        "P0108" to t("intake pressure sensor signal high", "signaal inlaatdruksensor te hoog"),
        "P0112" to t("intake air temperature sensor signal low", "signaal inlaatluchttemperatuur te laag"),
        "P0113" to t("intake air temperature sensor signal high", "signaal inlaatluchttemperatuur te hoog"),
        "P0115" to t("coolant temperature sensor fault", "storing in de koelvloeistoftemperatuursensor"),
        "P0116" to t("coolant temperature sensor reading is implausible", "koelvloeistoftemperatuursensor geeft een onlogische waarde"),
        "P0117" to t("coolant temperature sensor signal low", "signaal koelvloeistoftemperatuur te laag"),
        "P0118" to t("coolant temperature sensor signal high", "signaal koelvloeistoftemperatuur te hoog"),
        "P0120" to t("throttle position sensor fault", "storing in de gasklepsensor", Severity.CAREFUL),
        "P0121" to t("throttle position sensor reading is implausible", "gasklepsensor geeft een onlogische waarde", Severity.CAREFUL),
        "P0122" to t("throttle position sensor signal low", "signaal gasklepsensor te laag", Severity.CAREFUL),
        "P0123" to t("throttle position sensor signal high", "signaal gasklepsensor te hoog", Severity.CAREFUL),
        "P0125" to t("engine takes too long to warm up", "de motor wordt te langzaam warm"),
        "P0128" to t("the thermostat may be stuck open (engine stays too cold)", "de thermostaat blijft mogelijk open (motor blijft te koud)"),
        "P0171" to t("engine running too lean (air leak, dirty air flow sensor or weak fuel pump)", "het mengsel is te arm (luchtlek, vuile luchtmassameter of zwakke benzinepomp)"),
        "P0172" to t("engine running too rich (leaking injector or air flow sensor)", "het mengsel is te rijk (lekkende injector of luchtmassameter)"),
        "P0300" to t("random misfires", "willekeurige overslagen", Severity.CAREFUL),
        "P0325" to t("knock sensor fault", "storing in de klopsensor"),
        "P0335" to t("crankshaft position sensor fault (the engine may cut out)", "storing in de krukassensor (de motor kan afslaan)", Severity.CAREFUL),
        "P0340" to t("camshaft position sensor fault", "storing in de nokkenassensor", Severity.CAREFUL),
        "P0400" to t("exhaust gas recirculation fault", "storing in de uitlaatgasrecirculatie"),
        "P0401" to t("exhaust gas recirculation flow too low", "te weinig uitlaatgasrecirculatie"),
        "P0420" to t("the catalytic converter is underperforming", "de katalysator werkt niet goed genoeg"),
        "P0421" to t("the catalytic converter is slow to warm up", "de katalysator wordt te langzaam warm"),
        "P0440" to t("fuel vapour system fault", "storing in het brandstofdampsysteem"),
        "P0441" to t("fuel vapour system flow problem", "probleem met de doorstroming in het dampsysteem"),
        "P0442" to t("small leak in the fuel vapour system", "klein lek in het brandstofdampsysteem"),
        "P0443" to t("fuel vapour purge valve fault", "storing in de spoelklep van het dampsysteem"),
        "P0446" to t("fuel vapour vent valve fault", "storing in de ontluchtingsklep van het dampsysteem"),
        "P0455" to t("large leak in the fuel vapour system - check the fuel cap first", "groot lek in het brandstofdampsysteem - controleer eerst de tankdop", Severity.INFO),
        "P0456" to t("very small fuel vapour leak - check the fuel cap first", "heel klein dampleek - controleer eerst de tankdop", Severity.INFO),
        "P0457" to t("fuel cap loose or missing", "tankdop los of afwezig", Severity.INFO),
        "P0500" to t("vehicle speed sensor fault", "storing in de snelheidssensor"),
        "P0505" to t("idle control problem", "probleem met de stationairregeling"),
        "P0506" to t("idle speed lower than expected", "stationair toerental lager dan verwacht"),
        "P0507" to t("idle speed higher than expected (air leak or dirty throttle)", "stationair toerental hoger dan verwacht (luchtlek of vuile gasklep)"),
        "P0560" to t("system voltage problem", "probleem met de boordspanning"),
        "P0562" to t("system voltage is low (battery or alternator)", "de boordspanning is te laag (accu of dynamo)", Severity.CAREFUL),
        "P0563" to t("system voltage is high (alternator regulator)", "de boordspanning is te hoog (dynamoregelaar)", Severity.CAREFUL),
        "P0600" to t("engine computer communication fault", "communicatiestoring in de motorcomputer", Severity.CAREFUL),
        "P0601" to t("engine computer memory error", "geheugenfout in de motorcomputer", Severity.CAREFUL),
        "P0606" to t("engine computer processor fault", "processorfout in de motorcomputer", Severity.CAREFUL),
        "P0700" to t("the gearbox computer has a fault stored", "de versnellingsbakcomputer heeft een storing opgeslagen", Severity.CAREFUL),
        "P0705" to t("gear selector sensor fault", "storing in de sensor van de keuzehendel", Severity.CAREFUL),
        "P0715" to t("gearbox input speed sensor fault", "storing in de ingaande toerentalsensor van de bak", Severity.CAREFUL),
        "P0720" to t("gearbox output speed sensor fault", "storing in de uitgaande toerentalsensor van de bak", Severity.CAREFUL),
        "P0740" to t("torque converter clutch fault", "storing in de koppelomvormer", Severity.CAREFUL),
        "P0841" to t("gearbox fluid pressure sensor fault", "storing in de oliedruksensor van de bak", Severity.CAREFUL),
        "P2135" to t("the two throttle sensors disagree (limp mode possible)", "de twee gasklepsensoren zijn het oneens (noodloop mogelijk)", Severity.CAREFUL),
        "P2138" to t("the two accelerator pedal sensors disagree", "de twee gaspedaalsensoren zijn het oneens", Severity.CAREFUL),
        "U0001" to t("communication fault on the car's network", "communicatiestoring op het netwerk van de auto", Severity.CAREFUL),
        "U0100" to t("lost communication with the engine computer", "geen communicatie met de motorcomputer", Severity.CAREFUL),
        "U0101" to t("lost communication with the gearbox computer", "geen communicatie met de versnellingsbakcomputer", Severity.CAREFUL),
        "U0121" to t("lost communication with the ABS unit", "geen communicatie met de ABS-unit", Severity.CAREFUL),
    )

    private val O2_FAULT_EN = mapOf('0' to "circuit fault", '1' to "signal low", '2' to "signal high", '3' to "slow to respond",
        '4' to "no activity", '5' to "heater fault")
    private val O2_FAULT_NL = mapOf('0' to "storing in het circuit", '1' to "signaal te laag", '2' to "signaal te hoog", '3' to "reageert traag",
        '4' to "geen activiteit", '5' to "storing in de verwarming")

    /** Rules that read the code's structure, for the families that follow a pattern. */
    private fun pattern(code: String, dutch: Boolean): Pair<String, Severity>? {
        if (code.length != 5 || code[0] != 'P' || code[1] != '0') return null
        val n = code.substring(2).toIntOrNull(16)?.let { code.substring(2) } ?: return null
        val last = code[4]
        return when {
            n.startsWith("30") && last in '1'..'8' -> (if (dutch) "overslag in cilinder $last" else "misfire in cylinder $last") to Severity.CAREFUL
            n.startsWith("20") && last in '1'..'8' -> (if (dutch) "storing in de injector van cilinder $last" else "fuel injector fault, cylinder $last") to Severity.CAREFUL
            n.startsWith("35") && last in '1'..'8' -> (if (dutch) "storing in bobine $last" else "ignition coil $last fault") to Severity.CAREFUL
            n.toIntOrNull() in 130..167 -> {
                // SAE groups of six: circuit, low, high, slow, no activity, heater.
                val num = n.toInt()
                val groups = listOf(130 to (1 to 1), 136 to (1 to 2), 142 to (1 to 3), 150 to (2 to 1), 156 to (2 to 2), 162 to (2 to 3))
                val (start, pos) = groups.lastOrNull { num >= it.first } ?: return null
                val offset = num - start
                if (offset > 5) return null
                val (bank, sensor) = pos
                val where = if (dutch) (when (sensor) { 1 -> "voorste"; 2 -> "achterste"; else -> "derde" }) + " lambdasonde" + (if (bank == 2) " (bank 2)" else "")
                            else (when (sensor) { 1 -> "front"; 2 -> "rear"; else -> "third" }) + " oxygen sensor" + (if (bank == 2) " (bank 2)" else "")
                val fault = (if (dutch) O2_FAULT_NL else O2_FAULT_EN)['0' + offset] ?: if (dutch) "storing" else "fault"
                "$where: $fault" to Severity.CHECK_SOON
            }
            else -> null
        }
    }

    /** What part of the car the code belongs to, from its structure. */
    private fun category(code: String, dutch: Boolean): Pair<String, Severity> {
        val manufacturer = code.length == 5 && (code[1] == '1' || code[1] == '3')
        val brand = if (manufacturer) (if (dutch) "een Mitsubishi-specifieke code: " else "a Mitsubishi-specific code: ") else ""
        val part = when (code.firstOrNull()) {
            'C' -> (if (dutch) "het onderstel (ABS, remmen, stuur)" else "the chassis (ABS, brakes, steering)") to Severity.CAREFUL
            'B' -> (if (dutch) "de carrosserie (airbags, sloten, klimaat)" else "the body (airbags, locks, climate)") to Severity.CHECK_SOON
            'U' -> (if (dutch) "de communicatie tussen de computers" else "communication between the car's computers") to Severity.CAREFUL
            else -> when (code.getOrNull(2)) {
                '1', '2' -> (if (dutch) "het lucht- en brandstofsysteem" else "air and fuel metering") to Severity.CHECK_SOON
                '3' -> (if (dutch) "de ontsteking of overslagen" else "ignition or misfires") to Severity.CAREFUL
                '4' -> (if (dutch) "de uitlaatgasreiniging" else "the emissions controls") to Severity.CHECK_SOON
                '5' -> (if (dutch) "snelheid, stationair toerental of hulpsystemen" else "speed, idle or auxiliary inputs") to Severity.CHECK_SOON
                '6' -> (if (dutch) "de motorcomputer of de aansturing" else "the engine computer or its outputs") to Severity.CAREFUL
                '7', '8', '9' -> (if (dutch) "de versnellingsbak" else "the gearbox") to Severity.CAREFUL
                else -> (if (dutch) "de aandrijflijn" else "the powertrain") to Severity.CHECK_SOON
            }
        }
        return (brand + (if (dutch) "een storing in " else "a fault in ") + part.first) to part.second
    }

    fun explanation(code: String, dutch: Boolean): Explanation {
        TABLE[code]?.let { (en, nl, sev) -> return Explanation(if (dutch) nl else en, sev, advice(sev, dutch)) }
        pattern(code, dutch)?.let { (what, sev) -> return Explanation(what, sev, advice(sev, dutch)) }
        val (what, sev) = category(code, dutch)
        return Explanation(what, sev, advice(sev, dutch))
    }

    /** Short "what it means" (kept for the voice). */
    fun explain(code: String, dutch: Boolean): String = explanation(code, dutch).what

    fun advice(sev: Severity, dutch: Boolean): String = when (sev) {
        Severity.INFO -> if (dutch) "Niet urgent. Vaak een kleinigheid (bijvoorbeeld de tankdop)." else "Not urgent. Often something small (like the fuel cap)."
        Severity.CHECK_SOON -> if (dutch) "Je kunt doorrijden; laat het binnenkort nakijken." else "Safe to keep driving; have it checked soon."
        Severity.CAREFUL -> if (dutch) "Rij rustig, vermijd vol gas, en laat het snel nakijken." else "Drive gently, avoid hard acceleration, and get it checked soon."
        Severity.STOP -> if (dutch) "Stop zodra het veilig kan." else "Stop as soon as it's safe."
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
    enum class Kind { READY_TO_DRIVE, OVERHEAT, CHECK_ENGINE, ICY_ROAD, LOW_BATTERY, WEAK_CHARGING, ENGINE_WARM, LOW_FUEL }
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
    private var icyDone = false
    private var firstCoolant: Int? = null
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
        // Icy roads: the car's outside sensor if the engine computer shares it; otherwise the intake air
        // temperature right after a cold start (the engine hasn't heated it yet) is close to outside.
        if (firstCoolant == null) firstCoolant = s.coolantC
        if (!icyDone) outsideC(s)?.let { t ->
            if (t <= ICY_C) { icyDone = true; out += Alert(Kind.ICY_ROAD, urgent = false, value = t) }
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

    /** Outside temperature: the real sensor, or the intake air in the first 90 s of a cold start. */
    private fun outsideC(s: CarSnapshot): Double? {
        s.ambientC?.let { return it.toDouble() }
        val intake = s.extra[0x0F] ?: return null
        val coldStart = (firstCoolant ?: return null) < 40
        val early = (s.runtimeS ?: return null) < 90
        return if (coldStart && early) intake else null
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
        /** Like the car's own snowflake warning. */
        const val ICY_C = 3.0

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
                    // The advice for the most serious one ("drive gently, avoid hard acceleration...").
                    val worst = a.codes.map { TroubleCodes.explanation(it, dutch).severity }.maxOrNull() ?: TroubleCodes.Severity.CHECK_SOON
                    val advice = TroubleCodes.advice(worst, dutch)
                    when {
                        dutch && list.isEmpty() -> "Het motorstoringslampje is aangegaan. $advice"
                        dutch -> "Het motorstoringslampje is aan. Code $list. $advice"
                        butler && list.isEmpty() -> "Pardon me, sir. The engine warning light has come on. $advice"
                        butler -> "Pardon me, sir. The engine warning light is on. Code $list. $advice"
                        list.isEmpty() -> "The check-engine light just came on. $advice"
                        else -> "The check-engine light is on. Code $list. $advice"
                    }
                }
                Kind.ICY_ROAD -> {
                    val t = a.value?.let { com.daand.drivemusic.SpeechText.number(Math.round(it).toInt(), dutch) } ?: ""
                    when {
                        dutch -> "Het is $t graden buiten. Pas op voor gladheid."
                        butler -> "It is $t degrees outside, sir. Mind the black ice."
                        else -> "It's $t degrees outside. Watch out for ice."
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
