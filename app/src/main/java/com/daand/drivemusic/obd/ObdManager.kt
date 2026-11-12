package com.daand.drivemusic.obd

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
    const val POLL_MS = 5_000L
    const val FULL_EVERY = 6               // fuel / voltage / warning light every 30 s
    const val CODES_EVERY_MS = 10 * 60_000L
    const val CONNECT_TRIES = 6
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
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null

    /** When the car's computer last answered (it only does with the ignition on). */
    @Volatile private var lastEcuAnswerAt = 0L

    /** The adapter is connected and polling. */
    fun isConnected() = running && status == "Connected"

    /** How long the car's computer has been silent (ignition off), or Long.MAX_VALUE if never heard. */
    fun ecuSilentMs(): Long = if (lastEcuAnswerAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastEcuAnswerAt

    /** This drive's trip numbers (distance, fuel, idle...). */
    @Volatile var trip: Trip = Trip(); private set
    /** Chassis number, once read. */
    @Volatile var vin: String? = null; private set
    @Volatile var supportedCount = 0; private set
    @Volatile private var engineLitres = 1.6

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Fresh reading (less than 20 s old) or null. */
    fun live(): CarSnapshot? = latest?.takeIf { System.currentTimeMillis() - it.at < 20_000 }

    @Synchronized
    fun start(ctx: Context, onAlert: (CarAlerts.Alert) -> Unit) {
        val p = Prefs(ctx)
        if (running || !p.obdEnabled || p.obdDevice.isBlank()) return
        if (!hasPermission(ctx)) { status = "Needs the 'Nearby devices' permission"; DriveLog.log("OBD: no Bluetooth permission"); return }
        running = true
        val myGen = ++gen
        latest = null; trip = Trip(); lastEcuAnswerAt = 0L; connectedThisDrive = false
        engineLitres = p.obdEngineLitres
        val app = ctx.applicationContext
        val address = p.obdDevice
        readySeconds = p.obdReadySeconds
        val now = System.currentTimeMillis()
        val recent = p.obdToldCodes.filter { now - it.value < CarAlerts.REMIND_AFTER_MS }.keys
        alerts = CarAlerts(readySeconds, recent)
        val remember: (CarAlerts.Alert) -> Unit = { a ->
            if (a.kind == CarAlerts.Kind.CHECK_ENGINE) {
                val told = Prefs(app).obdToldCodes.toMutableMap()
                (a.codes.ifEmpty { listOf(CarAlerts.MIL) }).forEach { told[it] = System.currentTimeMillis() }
                Prefs(app).obdToldCodes = told
            }
            onAlert(a)
        }
        thread = Thread({ loop(app, address, myGen, remember) }, "obd").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        // Closing the socket also aborts a connect() that's still waiting.
        runCatching { link?.close() }
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
        status = "Off"
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
                val l = BluetoothObd.connect(ctx, address) { socket = it }
                link = l
                val session = ObdSession(l, engineLitres) { DriveLog.log(it) }
                val problem = session.start()
                if (problem != null) {
                    status = "Adapter found, but $problem"
                    DriveLog.log("OBD: $problem")
                    session.close()
                    sleep(RETRY_MS); continue
                }
                tries = 0  // connected: a later drop gets a fresh set of retries
                status = "Connected"
                connectedThisDrive = true
                vin = session.vin ?: vin
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

    private fun poll(session: ObdSession, myGen: Int, onAlert: (CarAlerts.Alert) -> Unit) {
        val alerts = this.alerts ?: CarAlerts(readySeconds).also { this.alerts = it }
        var n = 0
        var lastCodes = 0L
        while (alive(myGen)) {
            val full = n % FULL_EVERY == 0
            val codesDue = full && System.currentTimeMillis() - lastCodes > CODES_EVERY_MS
            val snap = session.read(latest, full, readCodes = codesDue)
            if (codesDue) lastCodes = System.currentTimeMillis()
            latest = snap
            if (snap.coolantC != null || snap.rpm != null || snap.speedKmh != null) lastEcuAnswerAt = snap.at
            trip.add(snap)
            if (!alive(myGen)) break
            alerts.check(snap).forEach { a ->
                DriveLog.log("OBD: alert ${a.kind}" + (a.value?.let { " ($it)" } ?: "") + (if (a.codes.isNotEmpty()) " ${a.codes}" else ""))
                onAlert(a)
            }
            n++
            sleep(when { liveView -> LIVE_POLL_MS; alerts.countingDown() -> FAST_POLL_MS; else -> POLL_MS })
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e } }

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
