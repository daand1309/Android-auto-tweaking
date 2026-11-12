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
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null

    // Trip stats for the log at the end of the drive.
    @Volatile private var maxCoolant: Int? = null
    @Volatile private var maxSpeed: Int? = null

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
        latest = null; maxCoolant = null; maxSpeed = null
        val app = ctx.applicationContext
        val address = p.obdDevice
        thread = Thread({ loop(app, address, onAlert) }, "obd").apply { isDaemon = true; start() }
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
        val parts = listOfNotNull(maxCoolant?.let { "max coolant $it °C" }, maxSpeed?.let { "max speed $it km/h" },
            latest?.codes?.takeIf { it.isNotEmpty() }?.let { "fault codes ${it.joinToString()}" })
        if (parts.isNotEmpty()) DriveLog.log("OBD: trip - ${parts.joinToString(", ")}")
    }

    private fun loop(ctx: Context, address: String, onAlert: (CarAlerts.Alert) -> Unit) {
        var tries = 0
        while (running && tries < CONNECT_TRIES) {
            tries++
            status = "Connecting (try $tries)..."
            try {
                val l = BluetoothObd.connect(ctx, address) { socket = it }
                link = l
                val session = ObdSession(l) { DriveLog.log(it) }
                val problem = session.start()
                if (problem != null) {
                    status = "Adapter found, but $problem"
                    DriveLog.log("OBD: $problem")
                    session.close()
                    sleep(RETRY_MS); continue
                }
                tries = 0  // connected: a later drop gets a fresh set of retries
                status = "Connected"
                poll(session, onAlert)
                session.close()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                status = "Can't reach the adapter (${e.message})"
                DriveLog.log("OBD: connection failed (${e.javaClass.simpleName}: ${e.message})")
                sleep(RETRY_MS)
            }
        }
        if (running && tries >= CONNECT_TRIES) { status = "Gave up for this drive - adapter not reachable"; DriveLog.log("OBD: gave up for this drive") }
    }

    private fun poll(session: ObdSession, onAlert: (CarAlerts.Alert) -> Unit) {
        val alerts = CarAlerts()
        var n = 0
        var lastCodes = 0L
        while (running) {
            val full = n % FULL_EVERY == 0
            val codesDue = full && System.currentTimeMillis() - lastCodes > CODES_EVERY_MS
            val snap = session.read(latest, full, readCodes = codesDue)
            if (codesDue) lastCodes = System.currentTimeMillis()
            latest = snap
            snap.coolantC?.let { c -> maxCoolant = maxOf(maxCoolant ?: c, c) }
            snap.speedKmh?.let { v -> maxSpeed = maxOf(maxSpeed ?: v, v) }
            alerts.check(snap).forEach { a ->
                DriveLog.log("OBD: alert ${a.kind}" + (a.value?.let { " ($it)" } ?: "") + (if (a.codes.isNotEmpty()) " ${a.codes}" else ""))
                onAlert(a)
            }
            n++
            sleep(POLL_MS)
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
            val session = ObdSession(l) { DriveLog.log(it) }
            try {
                session.start()?.let { return "Adapter found ✓, but $it" }
                val snap = session.read(null, full = true, readCodes = true)
                "Adapter ✓  Car ✓  (${session.protocol})\n\n" + CarStatusText.describe(snap, dutch) +
                    "\n\nYour car shares ${session.supported.size} values."
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

    /** Spoken summary ("Engine 90 degrees, battery 14.1 volts, ... no warning lights."). */
    fun spoken(s: CarSnapshot?, dutch: Boolean, butler: Boolean): String {
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
