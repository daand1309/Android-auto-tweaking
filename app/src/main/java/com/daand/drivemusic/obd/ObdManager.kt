package com.daand.drivemusic.obd

import com.daand.drivemusic.RadioPlayer

import android.Manifest
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.daand.drivemusic.DriveLog
import com.daand.drivemusic.Prefs

/**
 * Reads the car through the OBD adapter while you drive: connects when Android Auto connects,
 * retries while the adapter wakes up, polls every few seconds, and hands alerts to the voice.
 * Stops (and lets the adapter sleep) when you leave the car.
 */
object ObdManager {
    /** Speed, rpm, temperature: every 3.5 s - a little headroom for the ELM327 clone (5 s while it falls behind, see [PollPace]). */
    const val POLL_MS = 3_500L
    /** Fuel / voltage / warning light: every 30 s by the clock, whatever the pace of the fast values. */
    const val FULL_EVERY_MS = 30_000L
    const val CODES_EVERY_MS = 10 * 60_000L
    const val CONNECT_TRIES = 6
    /** Re-ask for the catalyst test every 4 minutes once warm, at most 6 times a drive. */
    const val CATALYST_EVERY_MS = 4 * 60_000L
    const val CATALYST_CHECKS = 6
    const val RETRY_MS = 20_000L

    /** Latest reading (null = none this drive). */
    @Volatile var latest: CarSnapshot? = null; private set
    /** Short status for the settings page / log. */
    @Volatile var status: String = "Off"; private set
    @Volatile private var running = false
    /** Bumped on every start(): an old connection thread still unwinding can't keep going. */
    @Volatile private var gen = 0
    /** Alert memory for the whole drive (survives the adapter reconnecting, e.g. after a stall). */
    @Volatile private var alerts: CarAlerts? = null
    /** The adapter worked at some point this drive (so a missing adapter during a stall is temporary). */
    @Volatile var connectedThisDrive = false; private set
    /** [onLinked] has fired for this drive (so a reconnect after a stall doesn't repeat it). */
    @Volatile private var linkedTold = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null

    /** When the car's computer last answered (it only does with the ignition on). */
    @Volatile private var lastEcuAnswerAt = 0L

    /** The adapter is connected and polling. */
    fun isConnected() = running && status == "Connected"

    /** Where the current poll's alerts go: replaced by [start] when a restarted monitor takes over. */
    @Volatile private var alertSink: ((CarAlerts.Alert) -> Unit)? = null

    /**
     * Called (on the adapter's own thread) each time the link comes up for a drive. The monitor uses it to
     * bring Drive Mode up when the ignition comes on, without waiting for Android Auto.
     */
    @Volatile var onLinked: (() -> Unit)? = null

    /** How long the car's computer has been silent (ignition off), or Long.MAX_VALUE if never heard. */
    fun ecuSilentMs(): Long {
        val last = maxOf(lastEcuAnswerAt, lastSession?.lastAnswerAt ?: 0L)
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }

    /** How long the car has been switched off: its computer is quiet while we still have the adapter.
     *  0 = the car is on (or we can't tell). */
    fun carOffForMs(): Long {
        val silent = ecuSilentMs()
        return if (Ignition.off(isConnected(), silent)) silent else 0L
    }

    /** This drive's trip numbers (distance, fuel, idle...). */
    @Volatile var trip: Trip = Trip(); private set
    /** Chassis number, once read. */
    @Volatile var vin: String? = null; private set
    @Volatile var supportedCount = 0; private set
    @Volatile private var engineLitres = 1.6
    @Volatile private var appCtx: Context? = null
    /** Last time the half-minute draft was written. */
    private var lastDraftAt = 0L
    /** Typical L/100 from trip history (for the range), loaded at the start of the drive. */
    @Volatile var typicalPer100: Double? = null; private set
    @Volatile private var tankLitres = 60.0
    /** This connection's first fuel level was compared with where the last drive ended (fill-up check). */
    @Volatile private var fuelChecked = false
    @Volatile private var gearCoach = GearCoach()
    /** The last few fuel levels - the median is saved at the end (one odd reading on a slope doesn't count). */
    private val recentFuel = java.util.ArrayDeque<Int>()
    @Volatile private var batteryLogged = false
    @Volatile private var restingMax: Double? = null

    /** Once per connection: the fuel type (01-51) and the catalyst's self-test (mode 06). */
    private fun afterConnect(ctx: Context, s: ObdSession) {
        val p = Prefs(ctx)
        s.fuel?.let { f ->
            if (!p.fuelTypeSetByUser && p.fuelType != f.code) {
                p.fuelType = f.code
                DriveLog.log("OBD: the car runs on ${f.en} - fuel type set to match (pick another in Car data to overrule)")
            }
        }
        s.catalyst?.let { c -> saveCatalyst(ctx, c, "") }
    }

    private fun saveCatalyst(ctx: Context, c: OnBoardTests.Catalyst, `when`: String) {
        val p = Prefs(ctx)
        p.catalystLog = CatalystLog.add(p.catalystLog, System.currentTimeMillis(), c.closeness)
        DriveLog.log("OBD: catalytic converter self-test$`when` - %d%% of its limit (%s)".format(java.util.Locale.ROOT,
            Math.round(c.closeness * 100), if (c.passes) "passes" else "FAILS"))
    }

    /**
     * The first fuel level of a connection against where the last drive ended: a jump is a fill-up.
     * After that, the level is kept so the end of this drive can be saved.
     */
    private fun trackFuel(ctx: Context, pct: Int) {
        synchronized(recentFuel) { recentFuel.addLast(pct); while (recentFuel.size > 3) recentFuel.removeFirst() }
        if (fuelChecked) return
        fuelChecked = true
        val p = Prefs(ctx)
        // Stamped with this drive's start (not now): the km between two fill-ups are the drives from the one
        // after the first fill-up up to the one before the next - by their start times.
        val fill = FuelLog.detect(p.lastFuelPct.takeIf { it >= 0 }, pct, tankLitres, p.fuelPriceQuoted, trip.startedAt) ?: return
        p.fuelLog = FuelLog.encode(FuelLog.decode(p.fuelLog) + fill)
        DriveLog.log("FUEL: fill-up seen - %d%% -> %d%%, about %.0f L".format(java.util.Locale.ROOT, fill.beforePct, fill.afterPct, fill.litres) +
            if (fill.price > 0) " (€%.3f/L ≈ €%.0f)".format(java.util.Locale.ROOT, fill.price, fill.cost) else "")
    }

    /** Winter (Nov-Mar), once a week, adapter connected before the start: say the check is ready. */
    private fun promptCrankCheck(ctx: Context, onAlert: (CarAlerts.Alert) -> Unit) {
        val p = Prefs(ctx)
        val month = java.time.LocalDate.now().monthValue
        if (month in 4..10 || System.currentTimeMillis() - p.lastCrankPrompt < 7 * 86_400_000L) return
        p.lastCrankPrompt = System.currentTimeMillis()
        DriveLog.log("BATTERY: adapter connected before the start - battery check ready")
        onAlert(CarAlerts.Alert(CarAlerts.Kind.BATTERY_CHECK_READY, urgent = false, timely = true))
    }

    private fun saveCrank(ctx: Context, r: CrankCheck.Result, onAlert: (CarAlerts.Alert) -> Unit) {
        val p = Prefs(ctx)
        p.crankLog = CrankCheck.add(p.crankLog, System.currentTimeMillis() / 86_400_000L, Math.round(r.minV * 100) / 100.0)
        val verdict = CrankCheck.verdict(r.minV)
        DriveLog.log("BATTERY: starting dipped from %.2f V to %.2f V (%s)".format(java.util.Locale.ROOT, r.restingV, r.minV, verdict))
        if (verdict != "good" || CrankCheck.gettingWeaker(CrankCheck.decode(p.crankLog)))
            onAlert(CarAlerts.Alert(CarAlerts.Kind.WEAK_CRANK, urgent = false, value = r.minV))
    }

    /** Where this drive ended, for the next drive's fill-up check. */
    private fun saveFuelLevel() {
        val ctx = appCtx ?: return
        val m = synchronized(recentFuel) { recentFuel.sorted() }.let { if (it.isEmpty()) null else it[it.size / 2] } ?: return
        Prefs(ctx).lastFuelPct = m
    }

    /** The freeze frame, kept so the pages can show it while you're parked (logged once per change). */
    private fun saveFreeze(ctx: Context, f: FreezeFrame) {
        val p = Prefs(ctx)
        val enc = f.encode()
        if (p.freezeFrame == enc) return
        p.freezeFrame = enc
        DriveLog.log("OBD: freeze frame for ${f.code} - " + f.values.entries.joinToString(", ") { (pid, v) ->
            (Pids.ALL.firstOrNull { it.pid == pid }?.en ?: "%02X".format(pid)) + " " + Pids.format(pid, v)
        })
    }

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Test hook: pretend the car sent this (and this trip). */
    @androidx.annotation.VisibleForTesting
    fun setLiveForTest(snapshot: CarSnapshot?, testTrip: Trip = Trip()) { latest = snapshot; trip = testTrip }

    /** Fresh reading (less than 20 s old) or null. */
    fun live(): CarSnapshot? = latest?.takeIf { System.currentTimeMillis() - it.at < 20_000 }

    @Synchronized
    fun start(ctx: Context, onAlert: (CarAlerts.Alert) -> Unit) {
        val p = Prefs(ctx)
        if (!p.obdEnabled || p.obdDevice.isBlank()) return
        if (!hasPermission(ctx)) { status = "Needs the 'Nearby devices' permission"; DriveLog.log("OBD: no Bluetooth permission"); return }
        val app = ctx.applicationContext
        val address = p.obdDevice
        val remember: (CarAlerts.Alert) -> Unit = { a ->
            if (a.kind == CarAlerts.Kind.CHECK_ENGINE || a.kind == CarAlerts.Kind.EARLY_CODE) {
                val told = Prefs(app).obdToldCodes.toMutableMap()
                val keys = if (a.kind == CarAlerts.Kind.EARLY_CODE) a.codes.map(CarAlerts::pendingKey) else a.codes.ifEmpty { listOf(CarAlerts.MIL) }
                keys.forEach { told[it] = System.currentTimeMillis() }
                Prefs(app).obdToldCodes = told
            }
            onAlert(a)
        }
        // Where alerts go. Re-armed below if a monitor that is already polling asks for them again.
        alertSink = remember
        if (running) {
            // Android rebound the notification listener while we were polling: the new instance must take
            // over the alerts (and Drive Mode's adapter trigger), or every warning keeps going to the
            // instance that was cleaned up and the car can go quiet for the rest of the drive.
            DriveLog.log("OBD: monitor restarted - the running adapter now reports to the new instance")
            return
        }
        running = true
        val myGen = ++gen
        linkedTold = false
        latest = null; trip = Trip(); lastEcuAnswerAt = 0L; connectedThisDrive = false
        // A new drive starts with a clean slate: the same fault happening again next drive is news,
        // and the log is what you read afterwards to see how often something goes wrong.
        lastProblem = null
        lastDraftAt = 0L
        // A draft left from a drive that never finished (the process was killed) becomes a real trip.
        Thread({
            runCatching { TripHistory.recoverDraft(app) }.getOrNull()?.let { r ->
                DriveLog.log("OBD: the drive that was cut short is kept (%.1f km, from the copy written while driving)"
                    .format(java.util.Locale.ROOT, r.km))
            }
        }, "trip-recover").start()
        engineLitres = p.obdEngineLitres
        tankLitres = p.tankLitres
        appCtx = app
        batteryLogged = false; restingMax = null
        fuelChecked = false; recentFuel.clear()
        gearCoach = GearCoach(p.gearLearned)
        typicalPer100 = runCatching { TripHistory.typicalPer100(TripHistory.load(ctx)) }.getOrNull()
        readySeconds = p.obdReadySeconds
        val now = System.currentTimeMillis()
        val recent = p.obdToldCodes.filter { now - it.value < CarAlerts.REMIND_AFTER_MS }.keys
        alerts = CarAlerts(readySeconds, recent, CodeTracker(p.storedCodeLog), CodeTracker(p.pendingCodeLog))
        // The loop talks to whatever sink is current, so a later start() takes over without a restart.
        thread = Thread({ loop(app, address, myGen) { a -> alertSink?.invoke(a) } }, "obd").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { saveFuelLevel() }
        appCtx?.let { c -> runCatching { Prefs(c).gearLearned = gearCoach.save() } }
        alertSink = null
        // Closing the socket also aborts a connect() that's still waiting.
        runCatching { link?.close() }
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
        status = "Off"
        lastSession?.let { ss ->
            // Questions after the ignition went off go unanswered by design - they don't count against the adapter.
            val missed = ss.unansweredWhileOn
            if (ss.questions > 0) DriveLog.log("OBD: ${ss.questions} questions, $missed unanswered while the car was on" +
                (if (ss.unanswered > missed) " (+${ss.unanswered - missed} after it switched off)" else "") +
                if (ss.questions >= 20 && missed * 4 > ss.questions) " - a lot: check the adapter is firmly in the port" else "")
        }
        lastSession = null
        unknownLogged.clear()
        latest?.volts?.let { v ->
            DriveLog.log("BATTERY: last reading this drive %.2f V (%s)".format(java.util.Locale.ROOT, v,
                if (latest?.engineRunning == true) "engine running - charging" else "engine off"))
        }
        saveTrip()
        val summary = trip.summary(dutch = false)
        val codes = latest?.codes?.takeIf { it.isNotEmpty() }?.let { " · fault codes ${it.joinToString()}" } ?: ""
        if (summary.isNotEmpty() || codes.isNotEmpty()) DriveLog.log("OBD: trip - $summary$codes")
    }

    private fun alive(myGen: Int) = running && gen == myGen

    private fun loop(ctx: Context, address: String, myGen: Int, onAlert: (CarAlerts.Alert) -> Unit) {
        var tries = 0
        while (alive(myGen) && tries < CONNECT_TRIES) {
            tries++
            status = "Connecting (try $tries)..."
            try {
                val l = BluetoothObd.connect(ctx, address, cancelled = { !alive(myGen) }) { socket = it }
                link = l
                if (!alive(myGen)) { runCatching { l.close() }; break }  // stopped while connecting
                val session = ObdSession(l, engineLitres) { DriveLog.log(it) }
                val problem = session.start()
                if (problem != null) {
                    status = "Adapter found, but $problem"
                    // Each retry gives the same sentence: say it once per episode, not three times a drive.
                    if (problem != lastProblem) { DriveLog.log("OBD: $problem"); lastProblem = problem }
                    session.close()
                    sleep(RETRY_MS); continue
                }
                lastProblem = null
                tries = 0  // connected: a later drop gets a fresh set of retries
                status = "Connected"
                connectedThisDrive = true
                // Only tell the monitor on the first link of this drive, not on every reconnect after a stall.
                if (!linkedTold) { linkedTold = true; runCatching { onLinked?.invoke() } }
                vin = session.vin ?: vin
                runCatching { afterConnect(ctx, session) }
                supportedCount = session.supported.size
                lastEcuAnswerAt = System.currentTimeMillis()  // it just answered "0100"
                poll(session, myGen, onAlert)
                session.close()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!alive(myGen)) break
                status = "Can't reach the adapter (${e.message})"
                DriveLog.log("OBD: connection failed (${e.javaClass.simpleName}: ${e.message})")
                sleep(RETRY_MS)
            }
        }
        if (alive(myGen) && tries >= CONNECT_TRIES) { status = "Gave up for this drive - adapter not reachable"; DriveLog.log("OBD: gave up for this drive") }
    }

    /** Poll faster while counting down to "ready to drive", so it's said on time. */
    const val FAST_POLL_MS = 2_000L

    @Volatile var readySeconds = 30

    /** The Drive Mode car tab is on screen: read the fast values more often so the numbers feel live. */
    @Volatile var liveView = false
    const val LIVE_POLL_MS = 1_500L

    /** Engine off / ignition off: the ECU is silent, so stop burning questions on it. */
    const val QUIET_POLL_MS = 15_000L

    /** Codes already logged as "not in the app's list" this drive. */
    private val unknownLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var lastSession: ObdSession? = null
    /** The last "can't talk to the car" reason, so retries don't repeat it in the log (per drive). */
    @Volatile private var lastProblem: String? = null

    private fun poll(session: ObdSession, myGen: Int, onAlert: (CarAlerts.Alert) -> Unit) {
        val alerts = this.alerts ?: CarAlerts(readySeconds).also { this.alerts = it }
        lastSession = session
        var n = 0
        var lastCodes = 0L
        var silent = 0          // rounds in a row with no answer at all
        var toldQuiet = false
        // Answer quality per 5 minutes: one line when many questions go unanswered, so a log shows *when*
        // the adapter struggles (17 Nov: 1366 of 3723 unanswered, cause unknown).
        var windowAt = System.currentTimeMillis(); var windowQ = 0; var windowMissed = 0
        var lastFullAt = 0L
        val crank = CrankCheck(); val pollStartedAt = System.currentTimeMillis(); var crankPrompted = false
        var minuteAt = System.currentTimeMillis(); var minuteQ = 0; var minuteMissed = 0; var slowUntil = 0L
        var catalystChecks = 0
        var movingAt = 0L          // when the car last reported speed or a running engine
        var lastReset = 0L
        var lastCatalystCheck = System.currentTimeMillis()
        while (alive(myGen)) {
            val roundStart = System.currentTimeMillis()
            val full = n == 0 || roundStart - lastFullAt >= FULL_EVERY_MS
            if (full) lastFullAt = roundStart
            // Once a minute: is the adapter keeping up? If not, the slower pace for 5 minutes.
            if (roundStart - minuteAt >= 60_000L) {
                val q = session.questions - minuteQ; val missed = session.unansweredWhileOn - minuteMissed
                if (PollPace.fallingBehind(q, missed) && roundStart >= slowUntil) {
                    slowUntil = roundStart + PollPace.SLOW_FOR_MS
                    DriveLog.log("OBD: the adapter is falling behind ($missed of $q unanswered in the last minute) - asking every 5 s for 5 minutes")
                }
                minuteAt = roundStart; minuteQ = session.questions; minuteMissed = session.unansweredWhileOn
            }
            val codesDue = full && System.currentTimeMillis() - lastCodes > CODES_EVERY_MS
            val snap = session.read(latest, full, readCodes = codesDue)
            if (codesDue) lastCodes = System.currentTimeMillis()
            if (System.currentTimeMillis() - windowAt >= 5 * 60_000L) {
                val q = session.questions - windowQ; val missed = session.unansweredWhileOn - windowMissed
                if (q >= 20 && missed * 10 > q) DriveLog.log("OBD: last 5 min $missed of $q questions unanswered (${missed * 100 / q}%)" +
                    (if (RadioPlayer.on) " - radio was on" else ""))
                windowAt = System.currentTimeMillis(); windowQ = session.questions; windowMissed = session.unansweredWhileOn
            }
            // The catalyst test only completes mid-drive (warm engine, steady cruise): ask again now and then.
            if (session.catalyst == null && catalystChecks < CATALYST_CHECKS && (snap.coolantC ?: 0) >= 70 &&
                System.currentTimeMillis() - lastCatalystCheck > CATALYST_EVERY_MS) {
                lastCatalystCheck = System.currentTimeMillis()
                catalystChecks++
                session.recheckCatalyst()?.let { c -> appCtx?.let { ctx -> runCatching { saveCatalyst(ctx, c, " (completed during the drive)") } } }
            }
            latest = snap
            snap.fuelPct?.takeIf { it in 0..100 }?.let { pct -> appCtx?.let { c -> runCatching { trackFuel(c, pct) } } }
            val answered = snap.coolantC != null || snap.rpm != null || snap.speedKmh != null
            if (answered) {
                lastEcuAnswerAt = snap.at
                silent = 0
                toldQuiet = false
                if ((snap.speedKmh ?: 0) > 3 || (snap.rpm ?: 0) > 400) movingAt = System.currentTimeMillis()
            } else {
                silent++
                // Silent right after driving or a running engine: the ignition isn't off that fast - the
                // adapter is stuck (18 Nov 18:17: silent for a minute mid-drive). Reset it, at most every 2 min.
                val now = System.currentTimeMillis()
                if (silent == 2 && now - movingAt < 30_000 && now - lastReset > 120_000) {
                    lastReset = now
                    val back = session.resetAdapter()
                    DriveLog.log("OBD: the adapter stopped answering while driving - reset it: " + if (back) "answering again" else "still quiet")
                    if (back) { silent = 0; continue }
                }
                // One line per quiet episode instead of twenty a minute: the car is off, not broken.
                if (silent == 3 && !toldQuiet) {
                    toldQuiet = true
                    DriveLog.log("OBD: the car has gone quiet - asking every 15 s until it answers again")
                }
            }
            trip.add(snap)
            // Gears are learned from every drive; a hint only when it's on and the engine is warm.
            gearCoach.add(snap, warm = (snap.coolantC ?: 0) >= 70)?.let { h ->
                if (appCtx?.let { Prefs(it).gearCoach } == true) {
                    DriveLog.log("OBD: gear hint - cruising at ${h.rpm} rpm" + (h.nextGear?.let { ", gear $it would do" } ?: ""))
                    onAlert(CarAlerts.Alert(CarAlerts.Kind.GEAR_HINT, urgent = false, value = h.rpm.toDouble(),
                        codes = listOfNotNull(h.nextGear?.toString()), timely = true))
                }
            }
            // Keep a copy on disk while driving, so a killed process doesn't take the drive with it.
            val draftNow = System.currentTimeMillis()
            if (draftNow - lastDraftAt > 30_000L && trip.km >= 0.3) {
                lastDraftAt = draftNow
                appCtx?.let { c -> runCatching { TripHistory.saveDraft(c, record(trip, draftNow), draftNow) } }
            }
            if (!alive(myGen)) break
            recordRestingBattery(snap, onAlert)
            alerts.check(snap).map { a ->
                // Low fuel: say how far it still goes.
                if (a.kind == CarAlerts.Kind.LOW_FUEL) a.copy(codes = listOfNotNull(rangeKm(snap)?.toString())) else a
            }.forEach { a ->
                DriveLog.log("OBD: alert ${a.kind}" + (a.value?.let { " ($it)" } ?: "") + (if (a.codes.isNotEmpty()) " ${a.codes}" else ""))
                // A code we can't explain properly yet goes to the log (only codes that passed the checks:
                // a garbled answer full of nonsense codes mustn't flood the log).
                a.codes.filter { it.length == 5 && !TroubleCodes.isKnown(it) && unknownLogged.add(it) }.forEach {
                    DriveLog.log("OBD: code $it isn't in the app's list yet - explained by its area: \"${TroubleCodes.explain(it, false)}\"")
                }
                onAlert(a)
            }
            // After the code was announced: what the car saved when it set it.
            if (session.readFreezeIfDue()) session.freeze?.let { f -> appCtx?.let { c -> runCatching { saveFreeze(c, f) } } }
            if (codesDue) {
                // How often each code showed up is kept across drives (short drives add up).
                appCtx?.let { c ->
                    val p = Prefs(c)
                    p.storedCodeLog = alerts.storedTracker.save(); p.pendingCodeLog = alerts.pendingTracker.save()
                }
            }
            n++
            // A steady rhythm: the wait includes the time the reading took.
            val interval = PollPace.interval(liveView, alerts.countingDown(), silent, slowed = System.currentTimeMillis() < slowUntil)
            // Ignition on, engine not running yet (first 5 minutes): instead of waiting, watch the battery
            // voltage several times a second - the start shows as a dip then charging (the battery test).
            val waitingToStart = !crank.done && silent == 0 && !snap.engineRunning && (snap.volts ?: 0.0) in 9.0..CrankCheck.RUNNING_V &&
                System.currentTimeMillis() - pollStartedAt < 5 * 60_000L
            if (waitingToStart) {
                if (!crankPrompted) { crankPrompted = true; appCtx?.let { c -> runCatching { promptCrankCheck(c, onAlert) } } }
                val end = roundStart + maxOf(interval, 8_000L)
                var misses = 0
                while (alive(myGen) && System.currentTimeMillis() < end && !crank.done) {
                    val v = session.adapterVolts()
                    if (v == null) misses++ else misses = 0
                    v?.let { crank.add(System.currentTimeMillis(), it)?.let { r -> appCtx?.let { c -> runCatching { saveCrank(c, r, onAlert) } } } }
                    // The starter pulls the OBD port's power down too: a cheap adapter can restart mid-crank
                    // and come back with its factory settings (echo on...). Set it up again, once.
                    if (crank.done || misses >= 3) {
                        val ok = session.resetAdapter()
                        DriveLog.log("OBD: adapter set up again after the start" + if (ok) "" else " - not answering yet")
                        if (!crank.done) crank.giveUp()
                        break
                    }
                    sleep(150L)
                }
            } else sleep((interval - (System.currentTimeMillis() - roundStart)).coerceAtLeast(200L))
        }
    }

    /**
     * The trip so far as a record. [at] is when the drive started, so the copy on disk can be matched
     * against a finished trip and the end-of-drive save can be recognised as the same drive.
     * The price is stamped here, so changing it later doesn't rewrite old drives.
     */
    private fun record(t: Trip, now: Long): TripRecord {
        val price = appCtx?.let { c -> runCatching { Prefs(c).fuelPriceForCosts }.getOrDefault(0.0) } ?: 0.0
        return TripRecord(t.startedAt,
            ((t.drivingSeconds + t.idleSeconds) / 60).toInt(), t.km, t.litres,
            t.score(), t.maxCoolant, latest?.codes ?: emptyList(), t.health.sample(), price = price)
    }

    /** The drive is over: keep it for the Trips page and tell the phone (a quiet notification). */
    private fun saveTrip() {
        val ctx = appCtx ?: return
        val t = trip
        if (t.km < 0.3) return
        // Snapshot now (a new drive may start right away), write the file off the main thread.
        val rec = record(t, System.currentTimeMillis())
        Thread({
            runCatching { TripHistory.add(ctx, rec); TripHistory.clearDraft(ctx) }
                .onFailure { DriveLog.log("OBD: couldn't save the trip (${it.javaClass.simpleName})") }
            runCatching { TripNotice.post(ctx, rec, Prefs(ctx)) }
            runCatching { checkTrends(ctx, rec.health) }.onFailure { DriveLog.log("OBD: trend check failed (${it.javaClass.simpleName})") }
        }, "trip-save").start()
    }

    /**
     * After each drive: compare the latest drives with this car's normal. A new finding (not told in
     * the last 2 weeks) gets a quiet notification now and is said once in the next greeting.
     */
    private fun checkTrends(ctx: Context, thisDrive: HealthSample?) {
        val trips = TripHistory.load(ctx)
        val history = trips.mapNotNull { it.health }
        // This drive's own sample - not the last drive that had one (a short cold hop has none), and
        // not whatever is last in the file if saving this drive failed.
        val h = thisDrive?.takeIf { !it.isEmpty }
        if (h == null) DriveLog.log("OBD: health this drive - not measured (too short, or the engine didn't get warm)")
        else DriveLog.log("OBD: health this drive - " + listOfNotNull(
            h.ltft?.let { "fuel trim $it%" }, h.chargeV?.let { "charging $it V" },
            h.idleSpread?.let { "idle ±$it rpm" }, h.warmupMin?.let { "warm-up $it min" }, h.cruiseCoolant?.let { "road temp $it °C" },
        ).joinToString(", ").ifEmpty { "not enough data" })
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        val told = p.trendTold.toMutableMap()
        val fresh = CarTrends.findings(history).filter { now - (told[it.kind.name] ?: 0L) > CarTrends.REMIND_AFTER_MS }
        if (fresh.isEmpty()) return
        fresh.forEach { f ->
            told[f.kind.name] = now
            DriveLog.log("OBD: early warning ${f.kind} (now ${f.now}, usually ${f.usual})")
        }
        p.trendTold = told
        p.trendToSay = (p.trendToSay + fresh.map { it.kind.name }).distinct()
        TripNotice.postEarlyWarning(ctx, fresh.joinToString("\n") { CarTrends.message(it, p.greetingDutch, false) }, p.greetingDutch)
    }

    /** km left on this tank (fuel level × tank ÷ typical consumption, else this trip's). */
    fun rangeKm(s: CarSnapshot? = latest): Int? = Range.km(s?.fuelPct, tankLitres, typicalPer100 ?: trip.avgPer100())

    /**
     * Resting battery voltage, logged once per day: the highest reading before the engine starts,
     * saved the moment it starts. (Not just the first reading - that can land in the starter's dip
     * and look like a dying battery.) If the adapter only connects once the engine runs: nothing.
     */
    private fun recordRestingBattery(s: CarSnapshot, onAlert: (CarAlerts.Alert) -> Unit) {
        if (batteryLogged) return
        val ctx = appCtx ?: return
        if (!s.engineRunning) { restingMax = RestingBattery.track(restingMax, s); return }
        batteryLogged = true
        val v = RestingBattery.value(restingMax) ?: run {
            DriveLog.log("BATTERY: no reading before start this time - the engine was already running when the adapter connected")
            return
        }
        val day = System.currentTimeMillis() / 86_400_000L
        val p = Prefs(ctx)
        val log = p.batteryLog.filter { it.first != day } + (day to Math.round(v * 100) / 100.0)
        p.batteryLog = log
        // Every start: the reading with how long the car stood and whether the adapter stayed in.
        val nowMs = System.currentTimeMillis()
        val parkedMin = p.lastDriveEndAt.takeIf { it > 0 }?.let { ((nowMs - it) / 60_000L).toInt() }?.takeIf { it in 0..(60 * 24 * 60) }
        val entry = BatteryLog.Entry(nowMs, Math.round(v * 100) / 100.0, parkedMin, p.adapterWhileParked)
        p.batteryEvents = BatteryLog.add(p.batteryEvents, entry)
        DriveLog.log("BATTERY: before start %.2f V (ignition on, engine off) · %s · %s".format(java.util.Locale.ROOT, v,
            BatteryLog.parkedText(parkedMin, false), BatteryLog.adapterText(p.adapterWhileParked, false)))
        BatteryLog.compare(p.batteryEvents)?.let { c ->
            DriveLog.log("BATTERY: after 12 h+ parked - adapter left in %.2f V (%d×) vs taken out %.2f V (%d×)%s".format(java.util.Locale.ROOT,
                c.leftIn, c.leftInCount, c.out, c.outCount, if (c.drainSuspected) " - lower with it in: the adapter may be draining" else " - no sign of drain"))
        }
        // Weaker over the days: a heads-up (at most weekly) before it leaves you stranded on a cold morning.
        val now = System.currentTimeMillis()
        if (CarAlerts.batteryTrendWeak(log) && now - p.lastBatteryWarning > 7 * 86_400_000L) {
            p.lastBatteryWarning = now
            DriveLog.log("OBD: battery getting weaker over the last days")
            onAlert(CarAlerts.Alert(CarAlerts.Kind.LOW_BATTERY, urgent = false, value = log.takeLast(5).map { it.second }.average()))
        }
    }

    /** Sleep that treats interrupt as a clean stop signal (stop() interrupts this thread). */
    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            // Do not rethrow: the while (alive) checks exit the poll/connect loops cleanly.
        }
    }

    /**
     * One-off test from the settings page (blocking - call from a background thread): connect, read
     * everything once, describe what was found.
     */
    fun testOnce(ctx: Context, address: String, dutch: Boolean): String {
        if (running) return "Already connected for this drive - see the live values."
        if (!hasPermission(ctx)) return "Allow 'Nearby devices' first."
        var s: BluetoothSocket? = null
        return try {
            val l = BluetoothObd.connect(ctx, address) { s = it }
            val session = ObdSession(l, Prefs(ctx).obdEngineLitres) { DriveLog.log(it) }
            try {
                session.start()?.let { return "Adapter found ✓, but $it" }
                val snap = session.read(null, full = true, readCodes = true)
                "Adapter ✓  Car ✓  (${session.protocol})\n" +
                    (session.vin?.let { "VIN: $it\n" } ?: "") +
                    "Your car shares ${session.supported.size} values.\n\n" + CarStatusText.report(snap, dutch)
            } finally { session.close() }
        } catch (e: Exception) {
            runCatching { s?.close() }
            "Couldn't connect: ${e.message}.\nIs the adapter plugged in, the ignition on, and 'Android-Vlink' paired (PIN 1234)?"
        }
    }
}

/** Car values in words - for the voice, the car menu and the test result. Pure, unit tested. */
object CarStatusText {
    fun describe(s: CarSnapshot, dutch: Boolean): String = listOfNotNull(
        s.coolantC?.let { if (dutch) "Motor: $it °C" else "Engine: $it °C" },
        s.volts?.let { (if (dutch) "Accu: " else "Battery: ") + "%.1f V".format(java.util.Locale.ROOT, it) },
        s.fuelPct?.let { (if (dutch) "Brandstof: " else "Fuel: ") + "$it%" },
        s.rpm?.let { (if (dutch) "Toerental: " else "RPM: ") + it },
        s.speedKmh?.let { (if (dutch) "Snelheid: " else "Speed: ") + "$it km/h" },
        when {
            s.codes.isNotEmpty() -> (if (dutch) "Storingscodes: " else "Fault codes: ") + s.codes.joinToString()
            s.checkEngine == true -> if (dutch) "Motorstoringslampje AAN" else "Check-engine light ON"
            s.checkEngine == false -> if (dutch) "Geen storingen ✓" else "No faults ✓"
            else -> null
        },
    ).joinToString("\n")

    /** Everything the car shared, grouped, plus faults explained - for the settings page and the test. */
    fun report(s: CarSnapshot, dutch: Boolean, trip: Trip? = null): String {
        val sb = StringBuilder()
        for (g in Pids.Group.values()) {
            val rows = Pids.ALL.filter { it.group == g && s.extra.containsKey(it.pid) }
                .map { "  ${if (dutch) it.nl else it.en}: ${Pids.format(it.pid, s.extra.getValue(it.pid))}" }
                .toMutableList()
            if (g == Pids.Group.ELECTRICAL && s.volts != null && 0x42 !in s.extra)
                rows += "  ${if (dutch) "Accuspanning" else "Battery voltage"}: ${"%.1f".format(java.util.Locale.ROOT, s.volts)} V"
            if (g == Pids.Group.AIR_FUEL) s.litresPerHour?.let { lph ->
                val per100 = FuelMath.per100km(lph, s.speedKmh?.toDouble())
                rows += "  ${if (dutch) "Verbruik nu" else "Fuel use now"}: " +
                    (per100?.let { "%.1f L/100km".format(java.util.Locale.ROOT, it) } ?: "%.1f L/h".format(java.util.Locale.ROOT, lph))
            }
            if (rows.isNotEmpty()) sb.append(if (dutch) g.nl else g.en).append('\n').append(rows.joinToString("\n")).append("\n\n")
        }
        trip?.summary(dutch)?.takeIf { it.isNotEmpty() }?.let { sb.append(if (dutch) "Deze rit\n  " else "This trip\n  ").append(it).append("\n\n") }
        sb.append(diagnostics(s, dutch))
        return sb.toString().trim()
    }

    /** Fault codes with what they mean and what to do, plus the emissions self-tests. */
    fun diagnostics(s: CarSnapshot, dutch: Boolean): String {
        val sb = StringBuilder(if (dutch) "Diagnose\n" else "Diagnostics\n")
        sb.append("  ").append(when (s.checkEngine) {
            true -> if (dutch) "Motorstoringslampje: AAN" else "Check-engine light: ON"
            false -> if (dutch) "Motorstoringslampje: uit ✓" else "Check-engine light: off ✓"
            null -> if (dutch) "Motorstoringslampje: onbekend" else "Check-engine light: unknown"
        }).append('\n')
        fun list(title: String, codes: List<String>, note: String) {
            if (codes.isEmpty()) return
            sb.append("\n").append(title).append(" - ").append(note).append('\n')
            codes.forEach { c ->
                val e = TroubleCodes.explanation(c, dutch)
                sb.append("  $c: ${e.what}\n    → ${e.advice}\n")
            }
        }
        list(if (dutch) "Storingscodes" else "Fault codes", s.codes, if (dutch) "bevestigde storingen" else "confirmed faults")
        list(if (dutch) "Mogelijke storingen" else "Pending codes", s.pendingCodes,
            if (dutch) "gezien, nog niet bevestigd (vroege waarschuwing)" else "noticed, not confirmed yet (an early warning)")
        list(if (dutch) "Permanente codes" else "Permanent codes", s.permanentCodes,
            if (dutch) "blijven tot de auto de reparatie zelf heeft gecontroleerd" else "stay until the car has verified the repair itself")
        if (s.codes.isEmpty() && s.pendingCodes.isEmpty() && s.permanentCodes.isEmpty())
            sb.append(if (dutch) "  Geen storingscodes ✓\n" else "  No fault codes ✓\n")
        s.readiness?.let { (done, all) ->
            sb.append("  ").append(if (dutch) "Uitstoot-zelftests: $done van $all klaar" else "Emissions self-tests: $done of $all complete")
            if (done < all) sb.append(if (dutch) " (rij nog wat; nodig voor de APK)" else " (drive a bit more - the APK/MOT needs these)")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Spoken summary ("Engine 90 degrees, battery 14.1 volts, ... no warning lights."). */
    fun spoken(s: CarSnapshot?, dutch: Boolean, butler: Boolean, trip: Trip? = null): String {
        if (s == null) return when {
            dutch -> "Ik heb nog geen gegevens van de auto. Is de OBD-adapter aangesloten?"
            butler -> "I have no word from the car yet, sir. Is the adapter plugged in?"
            else -> "No car data yet. Is the OBD adapter plugged in?"
        }
        fun v(x: Double) = "%.1f".format(java.util.Locale.ROOT, x).replace(".", if (dutch) " komma " else " point ")
        val parts = listOfNotNull(
            s.coolantC?.let { if (dutch) "motor $it graden" else "engine $it degrees" },
            s.volts?.let { if (dutch) "accu ${v(it)} volt" else "battery ${v(it)} volts" },
            s.fuelPct?.let { if (dutch) "brandstof $it procent" else "fuel $it percent" },
            trip?.avgPer100()?.let { if (dutch) "verbruik deze rit ${v(it)} liter per honderd kilometer" else "this trip ${v(it)} litres per hundred kilometres" },
        )
        val faults = when {
            s.codes.isNotEmpty() -> if (dutch) "Er staan storingscodes: ${s.codes.joinToString(", ") { TroubleCodes.spoken(it) }}." else "There are fault codes: ${s.codes.joinToString(", ") { TroubleCodes.spoken(it) }}."
            s.checkEngine == true -> if (dutch) "Het motorstoringslampje is aan." else "The check-engine light is on."
            s.checkEngine == false -> if (dutch) "Geen waarschuwingslampjes." else "No warning lights."
            else -> ""
        }
        val head = when { dutch -> "De auto: "; butler -> "The car, sir: "; else -> "Car status: " }
        return (head + parts.joinToString(", ") + ". " + faults).trim()
    }
}

/** Resting battery voltage before a start. Pure, unit tested. */
object RestingBattery {
    /** Highest reading while the engine is off and not cranking (rpm 0 / unknown). */
    fun track(max: Double?, s: CarSnapshot): Double? {
        val v = s.volts ?: return max
        if (s.engineRunning || (s.rpm ?: 0) != 0) return max
        return maxOf(max ?: v, v)
    }
    /** What to log once the engine starts: null if there was no believable reading. */
    fun value(max: Double?): Double? = max?.takeIf { it in 9.0..16.0 }
}

/** How often to ask the car. Pure, unit tested. */
object PollPace {
    const val SLOW_MS = 5_000L
    const val SLOW_FOR_MS = 5 * 60_000L

    /** More than 15% unanswered in a minute (with enough questions to tell): the adapter can't keep up. */
    fun fallingBehind(questions: Int, missed: Int) = questions >= 10 && missed * 100 > questions * 15

    fun interval(liveView: Boolean, countingDown: Boolean, silent: Int, slowed: Boolean): Long = when {
        silent >= 3 -> ObdManager.QUIET_POLL_MS      // ignition off: don't hammer a car that isn't listening
        slowed -> SLOW_MS                            // the adapter is falling behind: give it room
        liveView -> ObdManager.LIVE_POLL_MS
        countingDown -> ObdManager.FAST_POLL_MS
        else -> ObdManager.POLL_MS
    }
}
