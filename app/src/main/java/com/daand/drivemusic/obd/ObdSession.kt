package com.daand.drivemusic.obd

/**
 * One conversation with the adapter: set it up, learn what this car supports, then read values.
 * Works over any [ObdLink], so it's tested against a fake adapter.
 */
class ObdSession(private val link: ObdLink, private val engineLitres: Double = 1.6, private val log: (String) -> Unit = {}) {
    var supported: Set<Int> = emptySet(); private set
    var protocol: String = ""; private set
    var vin: String? = null; private set
    /** 01-51: the fuel the car says it runs on (null: not offered / not pump fuel). */
    var fuel: com.daand.drivemusic.FuelPrices.Fuel? = null; private set
    /** Mode 06: the catalytic converter's own self-test (null: not offered). */
    var catalyst: OnBoardTests.Catalyst? = null; private set
    /** Mode 02: what the car saved when the stored fault code was set (read once, when there is one). */
    var freeze: FreezeFrame? = null; private set
    private var freezeTried = false
    /** A stored code was just read: the freeze frame is fetched after the alert, see [readFreezeIfDue]. */
    private var freezeDue = false
    /** Adapter's own identification ("ELM327 v2.3"). */
    var adapter: String = ""; private set
    /** Ask for up to 6 values per question (CAN cars like the ASX); switched off if the car can't. */
    var batching = false; private set
    private var batchFailures = 0
    /** Questions the car didn't answer / garbled answers this session (for the log). */
    var unanswered = 0; private set
    /** Unanswered since the car last answered anything: the tail after the ignition went off. */
    private var unansweredSinceAnswer = 0
    /** Unanswered while the car was otherwise answering - the number that says something about the adapter. */
    val unansweredWhileOn get() = unanswered - unansweredSinceAnswer
    var questions = 0; private set
    /**
     * When the car's computer last gave a real answer - updated per answer, not per reading: one
     * reading can take a while on a slow adapter, and the stall safeguard must not mistake that for
     * the ignition being off.
     */
    @Volatile var lastAnswerAt = 0L; private set
    private val droppedLogged = mutableSetOf<Int>()

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
        adapter = runCatching { Elm327.lines(link.send("ATI", 1_500)).firstOrNull()?.take(40) }.getOrNull() ?: ""
        vin = runCatching { Elm327.vin(Elm327.lines(link.send("0902", 3_000))) }.getOrNull()
        // Combined questions work on CAN (ISO 15765), which the ASX uses.
        batching = supported.isNotEmpty() && (protocol.contains("15765") || protocol.contains("CAN"))
        log("OBD: connected - adapter '$adapter', protocol '$protocol', car supports ${supported.size} values" +
            if (batching) ", combined questions on" else "")
        discover()
        if (0x51 in supported) fuel = runCatching { Elm327.fuelType(Elm327.lines(link.send("0151", 1_500))) }.getOrNull()
        val catAnswer = runCatching { Elm327.lines(link.send("0621", 3_000)) }.getOrNull().orEmpty()
        catalyst = runCatching { OnBoardTests.catalyst(OnBoardTests.parse(catAnswer)) }.getOrNull()
        if (catalyst == null) log("OBD: catalytic converter self-test - not completed yet, or not shared (raw: ${catAnswer.joinToString(" ").take(80)})")
        return null
    }

    /**
     * Ask for the catalyst's self-test again. The ASX resets it at every start (answers 0 / 0 until
     * the test has run), and the engine only runs it once warm, on a steady cruise - so a reading at
     * connect is nearly always "not yet". Returns the result once the test has completed this drive.
     */
    fun recheckCatalyst(): OnBoardTests.Catalyst? {
        if (catalyst != null) return catalyst
        val ans = runCatching { Elm327.lines(link.send("0621", 3_000)) }.getOrNull().orEmpty()
        catalyst = runCatching { OnBoardTests.catalyst(OnBoardTests.parse(ans)) }.getOrNull()
        return catalyst
    }

    /** The values worth knowing from the moment a fault code was stored. */
    private val FREEZE_PIDS = listOf(0x0D, 0x0C, 0x05, 0x04, 0x11, 0x0B, 0x0F)

    /**
     * Once per connection, after a stored code was found and announced: what the car saved at that
     * moment. Kept out of [read] so the code alert and the live values don't wait for ~8 extra questions.
     * True when a frame was read.
     */
    fun readFreezeIfDue(): Boolean {
        if (!freezeDue) return false
        freezeDue = false
        freeze = runCatching { readFreeze() }.getOrNull() ?: freeze
        return freeze != null
    }

    private fun readFreeze(): FreezeFrame? {
        val code = Elm327.freezeCode(Elm327.lines(link.send("020200", 1_200))) ?: return null
        val values = FREEZE_PIDS.mapNotNull { pid ->
            val info = Pids.ALL.firstOrNull { it.pid == pid } ?: return@mapNotNull null
            val d = runCatching { Elm327.freezeData(Elm327.lines(link.send("02%02X00".format(pid), 800)), pid) }.getOrNull()
            d?.let { info.read(it) }?.let { pid to it }
        }.toMap()
        return FreezeFrame(code, values)
    }

    /**
     * Values this car shares that the app doesn't show yet: log each with its raw answer (once per
     * connection), so they can be added later from a shared log.
     */
    private fun discover() {
        val unknown = (supported - Pids.KNOWN).sorted()
        if (unknown.isEmpty()) return
        val raw = unknown.take(20).joinToString(", ") { pid ->
            val ans = runCatching { Elm327.lines(link.send("01%02X".format(pid), 1_500)) }.getOrNull().orEmpty()
            "%02X=".format(pid) + (ans.firstOrNull()?.take(24) ?: "?")
        }
        log("OBD: car also shares ${unknown.size} values not shown yet (raw): $raw")
    }

    private fun raw(pid: Int): List<Int>? {
        if (supported.isNotEmpty() && pid !in supported) return null
        questions++
        val lines = Elm327.lines(link.send("01%02X".format(pid), 1_500))
        val d = if (Elm327.isError(lines)) null else Elm327.pidData(lines, pid)
        if (d == null) { unanswered++; unansweredSinceAnswer++ } else { lastAnswerAt = System.currentTimeMillis(); unansweredSinceAnswer = 0 }
        return d
    }

    /** Several values at once: combined questions (6 per question) when the car allows, else one by one. */
    private fun fetch(pids: List<Int>): Map<Int, List<Int>> {
        val ask = pids.filter { supported.isEmpty() || it in supported }
        if (!batching) return ask.mapNotNull { p -> raw(p)?.let { p to it } }.toMap()
        val out = HashMap<Int, List<Int>>()
        for (chunk in ask.chunked(6)) {
            questions++
            val lines = Elm327.lines(link.send("01" + chunk.joinToString("") { "%02X".format(it) }, 2_000))
            val got = if (Elm327.isError(lines)) emptyMap() else Elm327.multiPid(lines) { Pids.lengthOf(it) }.filterKeys { it in chunk }
            out.putAll(got)
            if (got.isNotEmpty()) { lastAnswerAt = System.currentTimeMillis(); unansweredSinceAnswer = 0 }
            // Anything the combined answer left out: ask on its own.
            var singlesAnswered = 0
            for (p in chunk) if (p !in out) raw(p)?.let { out[p] = it; singlesAnswered++ }
            // Combined questions only "don't work" if the car answers the same things asked one by one;
            // when nothing answers at all, the car simply went quiet (ignition off) - not a reason to switch.
            if (got.isEmpty() && chunk.size > 1 && singlesAnswered > 0) {
                if (++batchFailures >= 2 && batching) {
                    batching = false
                    log("OBD: this car/adapter doesn't answer combined questions - asking one at a time")
                }
            }
        }
        return out
    }

    /** null = the question failed (keep what we knew), empty = really no codes. */
    private fun codes(mode: String, answerByte: Int): List<String>? {
        val ans = Elm327.lines(link.send(mode, 4_000))
        if (Elm327.isError(ans)) return null
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
        // Seconds since start: interesting in the first minutes (the "ready to drive" countdown).
        val wantRuntime = !full && (previous?.runtimeS ?: 0) < 300
        val answers = fetch(wanted.map { it.pid } + if (wantRuntime) listOf(Elm327.PID_RUNTIME) else emptyList())
        // A value that doesn't answer (or answers garbage) this time is dropped, not kept from before:
        // a stale air-flow reading would otherwise keep "using fuel" after the engine stopped.
        for (info in wanted) {
            val d = answers[info.pid]
            val v = d?.let { info.read(it) }
            if (v != null) extra[info.pid] = v else extra.remove(info.pid)
            if (d != null && v == null && droppedLogged.add(info.pid))
                log("OBD: ignored an implausible ${info.en} (PID %02X): raw ${Elm327.hex(d)} -> ${info.decode(d)}".format(info.pid))
        }
        if (wantRuntime) {
            val rt = answers[Elm327.PID_RUNTIME]?.let { Elm327.runtimeS(it) }
            if (rt != null) extra[0x1F] = rt.toDouble() else extra.remove(0x1F)
        }

        var volts = extra[0x42] ?: previous?.volts
        var status = previous?.checkEngine
        var readiness = previous?.readiness
        var codes = previous?.codes ?: emptyList()
        var pending = previous?.pendingCodes ?: emptyList()
        var permanent = previous?.permanentCodes ?: emptyList()
        var codesReadAt = previous?.codesReadAt
        var storedReadAt = previous?.storedReadAt
        var codeCount = previous?.codeCount
        if (full) {
            if (0x42 !in extra || Elm327.PID_VOLTAGE !in supported) volts = Elm327.atrvVolts(Elm327.lines(link.send("ATRV", 1_500))) ?: volts
            raw(Elm327.PID_STATUS)?.let { d ->
                val st = Elm327.status(d)
                status = st?.first
                codeCount = st?.second
                readiness = Elm327.readiness(d)
                if (readCodes || (st != null && st.second > 0 && codes.isEmpty())) {
                    codes("03", 0x43)?.let { codes = it; storedReadAt = now }
                    // A stored code: once per connection, ask what the car saved at that moment.
                    if (codes.isNotEmpty() && !freezeTried) { freezeTried = true; freezeDue = true }
                }
                if (st?.first == false && st.second == 0) codes = emptyList()
            }
            if (readCodes) {
                codes("07", 0x47)?.let { pending = it; codesReadAt = now }
                codes("0A", 0x4A)?.let { permanent = it }
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
            codesReadAt = codesReadAt, storedReadAt = storedReadAt, codeCount = codeCount,
        )
    }

    fun close() { runCatching { link.close() } }
}
