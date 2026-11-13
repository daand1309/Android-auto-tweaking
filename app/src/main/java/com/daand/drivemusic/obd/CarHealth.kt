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
    /** When the fault codes were last actually asked (they're kept in between reads). */
    val codesReadAt: Long? = null,
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
        // ---- Common on the ASX 1.6 MIVEC (4A92): variable valve timing, sensors, throttle, CVT
        "P0012" to t("camshaft timing too far retarded (often low or old oil)", "nokkenas-timing te ver verlaat (vaak te weinig of oude olie)"),
        "P0014" to t("exhaust camshaft timing over-advanced", "uitlaatnokkenas-timing te ver vervroegd"),
        "P0031" to t("front oxygen sensor heater circuit low", "verwarming voorste lambdasonde: signaal te laag"),
        "P0032" to t("front oxygen sensor heater circuit high", "verwarming voorste lambdasonde: signaal te hoog"),
        "P0037" to t("rear oxygen sensor heater circuit low", "verwarming achterste lambdasonde: signaal te laag"),
        "P0038" to t("rear oxygen sensor heater circuit high", "verwarming achterste lambdasonde: signaal te hoog"),
        "P0068" to t("intake pressure and throttle readings don't agree (air leak?)", "inlaatdruk en gasklep kloppen niet met elkaar (luchtlek?)"),
        "P0105" to t("intake pressure sensor circuit fault", "storing in het circuit van de inlaatdruksensor"),
        "P0222" to t("throttle position sensor 2 signal low", "signaal gasklepsensor 2 te laag", Severity.CAREFUL),
        "P0223" to t("throttle position sensor 2 signal high", "signaal gasklepsensor 2 te hoog", Severity.CAREFUL),
        "P0327" to t("knock sensor signal low", "signaal klopsensor te laag"),
        "P0328" to t("knock sensor signal high", "signaal klopsensor te hoog"),
        "P0336" to t("crankshaft sensor signal out of range", "signaal krukassensor buiten bereik", Severity.CAREFUL),
        "P0341" to t("camshaft sensor signal out of range", "signaal nokkenassensor buiten bereik", Severity.CAREFUL),
        "P0342" to t("camshaft sensor signal low", "signaal nokkenassensor te laag", Severity.CAREFUL),
        "P0343" to t("camshaft sensor signal high", "signaal nokkenassensor te hoog", Severity.CAREFUL),
        "P0403" to t("exhaust gas recirculation valve circuit fault", "storing in het circuit van de EGR-klep"),
        "P0451" to t("fuel tank pressure sensor reading implausible", "druksensor brandstoftank geeft een onlogische waarde"),
        "P0461" to t("fuel level sensor reading implausible (gauge may be wrong)", "brandstofniveausensor onlogisch (meter kan verkeerd staan)", Severity.INFO),
        "P0462" to t("fuel level sensor signal low", "signaal brandstofniveausensor te laag", Severity.INFO),
        "P0463" to t("fuel level sensor signal high", "signaal brandstofniveausensor te hoog", Severity.INFO),
        "P0513" to t("immobiliser doesn't recognise the key", "startonderbreker herkent de sleutel niet", Severity.CAREFUL),
        "P0551" to t("power steering pressure switch fault", "storing in de stuurbekrachtigingsdrukschakelaar"),
        "P0603" to t("engine computer lost its memory (common after a battery change - often clears itself)", "motorcomputer is zijn geheugen kwijt (vaak na accuwissel - gaat meestal vanzelf weg)", Severity.INFO),
        "P0605" to t("engine computer memory fault", "geheugenfout in de motorcomputer", Severity.CAREFUL),
        "P0627" to t("fuel pump relay circuit fault", "storing in het relais van de benzinepomp", Severity.CAREFUL),
        "P0638" to t("electronic throttle actuator problem (limp mode possible)", "probleem met de elektronische gasklep (noodloop mogelijk)", Severity.CAREFUL),
        "P0657" to t("actuator power supply fault", "storing in de voeding van de actuatoren", Severity.CAREFUL),
        "P0776" to t("CVT pressure control solenoid performance problem", "drukregelklep van de CVT werkt niet goed", Severity.CAREFUL),
        "P0841" to t("gearbox fluid pressure sensor fault", "storing in de oliedruksensor van de bak", Severity.CAREFUL),
        "P0846" to t("gearbox fluid pressure sensor 2 fault", "storing in oliedruksensor 2 van de bak", Severity.CAREFUL),
        "P0868" to t("gearbox fluid pressure too low", "oliedruk in de bak te laag", Severity.CAREFUL),
        "P0882" to t("gearbox computer supply voltage low", "voedingsspanning versnellingsbakcomputer te laag", Severity.CAREFUL),
        "P0883" to t("gearbox computer supply voltage high", "voedingsspanning versnellingsbakcomputer te hoog", Severity.CAREFUL),
        "P2101" to t("electronic throttle motor circuit problem (limp mode possible)", "storing in de gasklepmotor (noodloop mogelijk)", Severity.CAREFUL),
        "P2122" to t("accelerator pedal sensor 1 signal low", "signaal gaspedaalsensor 1 te laag", Severity.CAREFUL),
        "P2123" to t("accelerator pedal sensor 1 signal high", "signaal gaspedaalsensor 1 te hoog", Severity.CAREFUL),
        "P2127" to t("accelerator pedal sensor 2 signal low", "signaal gaspedaalsensor 2 te laag", Severity.CAREFUL),
        "P2128" to t("accelerator pedal sensor 2 signal high", "signaal gaspedaalsensor 2 te hoog", Severity.CAREFUL),
        "P2195" to t("front oxygen sensor stuck lean", "voorste lambdasonde blijft op arm hangen"),
        "P2196" to t("front oxygen sensor stuck rich", "voorste lambdasonde blijft op rijk hangen"),
        "P2228" to t("barometric pressure sensor signal low", "signaal luchtdruksensor te laag"),
        "P2229" to t("barometric pressure sensor signal high", "signaal luchtdruksensor te hoog"),
        // ---- Mitsubishi's own codes (meanings vary a little by model - these are the agreed ones)
        "P1021" to t("MIVEC oil control valve circuit (variable valve timing) - check the oil level first", "MIVEC-oliestuurklep (variabele kleptiming) - controleer eerst het oliepeil"),
        "P1400" to t("manifold pressure sensor circuit (Mitsubishi)", "inlaatspruitstuk-druksensor (Mitsubishi)"),
        "P1500" to t("alternator FR terminal circuit (charging system)", "dynamo FR-aansluiting (laadsysteem)", Severity.CAREFUL),
        "P1530" to t("air conditioning switch / control circuit", "aircoschakelaar / regelcircuit", Severity.INFO),
        "P1603" to t("engine computer battery backup circuit (often after the battery was disconnected)", "back-upvoeding van de motorcomputer (vaak na loskoppelen van de accu)", Severity.INFO),
        "P1610" to t("immobiliser system fault", "storing in de startonderbreker", Severity.CAREFUL),
        "P1751" to t("automatic gearbox (CVT) control relay fault", "storing in het regelrelais van de automaat (CVT)", Severity.CAREFUL),
        "U0073" to t("the car's network (CAN bus) is down", "het netwerk van de auto (CAN-bus) ligt eruit", Severity.CAREFUL),
        "U0141" to t("lost communication with the body computer", "geen communicatie met de carrosseriecomputer", Severity.CHECK_SOON),
        "U0155" to t("lost communication with the instrument cluster", "geen communicatie met het dashboard", Severity.CHECK_SOON),
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

    /** In the app's own list or decoded by rule (false = only the general area is known). */
    fun isKnown(code: String) = TABLE.containsKey(code) || pattern(code, false) != null

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
    /** Pending codes already seen in a code read on an earlier drive (so this drive's read confirms them). */
    private val pendingSeenBefore: Set<String> = emptySet(),
) {
    enum class Kind { READY_TO_DRIVE, OVERHEAT, CHECK_ENGINE, EARLY_CODE, ICY_ROAD, LOW_BATTERY, WEAK_CHARGING, THERMOSTAT, ENGINE_WARM, LOW_FUEL }
    /** [timely] = only useful right now (say it as soon as possible, or not at all). */
    data class Alert(val kind: Kind, val urgent: Boolean, val value: Double? = null, val codes: List<String> = emptyList(),
                     val timely: Boolean = false)

    private val pendingReads = HashMap<String, MutableSet<Long>>()
    // Fuel level and outside temperature come with the slower full reads (every 9-30 s) and are kept in
    // between, so one garbled read would repeat for a while: they must hold for longer than that.
    private val fuelLow = Held(40_000L)
    private val icyCold = Held(40_000L)
    private val thermostatLow = Held(10_000L)
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
    private var thermostatDone = false
    private var warmDrivingSeconds = 0.0
    private var lastAt: Long? = null
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
        // Pending codes: the engine computer saw a problem once but hasn't lit the warning light (it
        // waits for a second drive to confirm). The earliest warning there is.
        // A pending code isn't corroborated by anything else (unlike confirmed codes, which the status
        // count must agree with), so one garbled answer could invent one: it must show up in two
        // separate code reads - this drive, or once on an earlier drive and again now.
        s.codesReadAt?.let { readAt -> s.pendingCodes.forEach { pendingReads.getOrPut(it) { HashSet() } += readAt } }
        val newPending = s.pendingCodes.filter {
            it !in s.codes && pendingKey(it) !in reportedCodes && ((pendingReads[it]?.size ?: 0) >= 2 || it in pendingSeenBefore)
        }
        if (newPending.isNotEmpty()) {
            reportedCodes += newPending.map(::pendingKey)
            out += Alert(Kind.EARLY_CODE, urgent = false, codes = newPending)
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
        // Thermostat stuck open: after 15 min of real driving the engine still isn't near normal
        // temperature (and it isn't freezing outside, which can keep it cool on its own).
        lastAt?.let { prev ->
            val dt = (s.at - prev) / 1000.0
            if (dt in 0.0..15.0 && s.engineRunning && (s.speedKmh ?: 0) > 30) warmDrivingSeconds += dt
        }
        lastAt = s.at
        if (!thermostatDone && warmDrivingSeconds >= 15 * 60) {
            val c = s.coolantC?.takeIf { it in -40..MAX_PLAUSIBLE_C }
            if (c != null && c < THERMOSTAT_C && (s.ambientC ?: 10) > -10) {
                if (thermostatLow.held(true, s.at)) { thermostatDone = true; out += Alert(Kind.THERMOSTAT, urgent = false, value = c.toDouble()) }
            } else if (c != null) thermostatDone = true
        }
        // Icy roads: the car's outside sensor if the engine computer shares it; otherwise the intake air
        // temperature right after a cold start (the engine hasn't heated it yet) is close to outside.
        if (firstCoolant == null) firstCoolant = s.coolantC
        if (!icyDone) outsideC(s)?.let { t ->
            if (icyCold.held(t <= ICY_C, s.at)) { icyDone = true; out += Alert(Kind.ICY_ROAD, urgent = false, value = t) }
        }
        s.fuelPct?.let { f ->
            if (!fuelDone && fuelLow.held(f in 1 until LOW_FUEL_PCT, s.at)) { fuelDone = true; out += Alert(Kind.LOW_FUEL, false, f.toDouble()) }
        }
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
        /** How a pending code is remembered (separately from the same code once confirmed). */
        fun pendingKey(code: String) = "pending:$code"

        /** A healthy engine is well above this after 15 minutes of driving. */
        const val THERMOSTAT_C = 70

        /**
         * Battery getting weaker over the days: the last 5 resting readings average below 12.2 V, or
         * they're 0.3 V lower than two weeks before. Pure, unit tested.
         */
        fun batteryTrendWeak(log: List<Pair<Long, Double>>): Boolean {
            if (log.size < 5) return false
            val recent = log.takeLast(5).map { it.second }.average()
            if (recent < 12.2) return true
            val older = log.dropLast(5).takeLast(5).map { it.second }
            return older.size >= 3 && older.average() - recent >= 0.3
        }

        /**
         * Service status from the car's own dashboard reminder: you type in what it shows (km and days
         * left) and the app counts down from that moment with the km of your drives and the calendar.
         * Returns (km left, days left); either can go negative when overdue.
         */
        fun serviceLeft(syncAt: Long, kmLeftAtSync: Int, daysLeftAtSync: Int, kmSinceSync: Double, now: Long): Pair<Int, Int> =
            (kmLeftAtSync - Math.round(kmSinceSync).toInt()) to (daysLeftAtSync - ((now - syncAt) / 86_400_000L).toInt())

        /** The dashboard sync wins when it's newer than the "last service" date; null = not due. */
        fun serviceStatus(serviceDate: Long, kmSinceService: Double, intervalKm: Int, months: Int,
                          syncAt: Long, kmLeftAtSync: Int, daysLeftAtSync: Int, kmSinceSync: Double, now: Long): String? {
            if (syncAt > 0 && syncAt >= serviceDate) {
                val (km, days) = serviceLeft(syncAt, kmLeftAtSync, daysLeftAtSync, kmSinceSync, now)
                return when { km <= 0 -> "km"; days <= 0 -> "time"; else -> null }
            }
            return serviceDue(serviceDate, kmSinceService, intervalKm, months, now)
        }

        /** "45 days", "2 months", "2 maanden", "45" (days) -> days; null if unreadable. Pure, unit tested. */
        fun parseDays(input: String): Int? {
            val m = Regex("""(-?\d+)\s*([a-zA-Z]*)""").find(input.trim()) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val days = when {
                unit.startsWith("m") -> n * 30          // months / maanden
                unit.startsWith("w") -> n * 7           // weeks / weken
                unit.startsWith("y") || unit.startsWith("j") -> n * 365
                else -> n
            }
            return days.takeIf { it in -3650..3650 }
        }

        /** Service due: km driven since the last service or time passed. null = not set / not due. */
        fun serviceDue(serviceDate: Long, kmSince: Double, intervalKm: Int, months: Int, now: Long): String? {
            if (serviceDate <= 0) return null
            val days = (now - serviceDate) / 86_400_000L
            return when {
                kmSince >= intervalKm -> "km"
                days >= months * 30L -> "time"
                else -> null
            }
        }

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
                Kind.EARLY_CODE -> {
                    val list = a.codes.take(3).joinToString(if (dutch) ". En " else ". And ") {
                        "${TroubleCodes.spoken(it)}: ${TroubleCodes.explain(it, dutch)}"
                    }
                    when {
                        dutch -> "Een vroege waarschuwing: de motorcomputer heeft code $list één keer gezien. Het lampje is nog niet aan; gebeurt het opnieuw, dan gaat het aan. Houd het in de gaten."
                        butler -> "An early word, sir: the engine computer has noticed code $list once. The warning light isn't on yet; should it happen again, it will be. Worth keeping an eye on."
                        else -> "Early heads-up: the engine computer noticed code $list once. The warning light isn't on yet - if it happens again, it will be. Worth keeping an eye on."
                    }
                }
                Kind.THERMOSTAT -> when {
                    dutch -> "De motor blijft na een kwartier rijden maar ${n(a.value)} graden. Mogelijk blijft de thermostaat open staan. Laat het binnenkort nakijken."
                    butler -> "Sir, after a quarter of an hour the engine is only at ${n(a.value)} degrees. The thermostat may be stuck open. Worth having it looked at."
                    else -> "After 15 minutes the engine's only at ${n(a.value)} degrees. The thermostat may be stuck open - worth getting it checked."
                }
                Kind.ENGINE_WARM -> when {
                    dutch -> "De motor is op temperatuur."
                    butler -> "The engine is warm now, sir. Do enjoy yourself."
                    else -> "Engine's warmed up. Go ahead and enjoy it."
                }
                Kind.LOW_FUEL -> {
                    val range = a.codes.firstOrNull()?.toIntOrNull()   // range in km, if known
                    val r = if (range == null) "" else if (dutch) " Daarmee kom je nog zo'n $range kilometer." else if (butler) " Enough for roughly $range kilometres." else " That's about $range kilometres."
                    when {
                        dutch -> "De tank raakt leeg, nog ongeveer ${n(a.value)} procent.$r"
                        butler -> "We are running low on fuel, sir. About ${n(a.value)} percent remains.$r"
                        else -> "Fuel's getting low, about ${n(a.value)} percent left.$r"
                    }
                }
            }
        }
    }
}

/**
 * True once a condition has held for [ms] (from its first sighting): one bad reading, even one that
 * is repeated until the next read, can't trigger anything. Resets as soon as it doesn't hold. Pure.
 */
class Held(private val ms: Long) {
    private var since: Long? = null
    fun held(condition: Boolean, at: Long): Boolean {
        if (!condition) { since = null; return false }
        val start = since ?: at.also { since = it }
        return at - start >= ms
    }
}
