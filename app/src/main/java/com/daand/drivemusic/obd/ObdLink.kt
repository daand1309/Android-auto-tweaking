package com.daand.drivemusic.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Send one ELM327 command, get the raw answer (up to the ">" prompt). */
interface ObdLink : Closeable {
    fun send(cmd: String, timeoutMs: Long = 2_000): String
}

/**
 * The only things this app ever says to the adapter: questions. Reading values (mode 01), fault
 * codes (03 stored, 07 pending, 0A permanent), the VIN (09 02), and adapter set-up / info commands.
 * Anything else - above all 04 (clear fault codes) or anything that controls the car - is refused
 * here, at the lowest level, whatever code above asks for it. Pure, unit tested.
 */
object ReadOnly {
    private val ALLOWED = Regex("""^(01(?:[0-9A-F]{2}){1,6}|03|07|0A|0902|AT(?:Z|E0|L0|S0|H0|AT1|SP0|DP|I|RV))$""")
    fun allowed(cmd: String) = ALLOWED.matches(cmd)
}

/** ELM327 over any pair of streams (Bluetooth socket in the car, a fake adapter in tests). */
class StreamLink(private val input: InputStream, private val output: OutputStream, private val onClose: () -> Unit = {}) : ObdLink {
    override fun send(cmd: String, timeoutMs: Long): String {
        if (!ReadOnly.allowed(cmd)) throw IllegalArgumentException("refused to send '$cmd': this app only reads from the car")
        while (input.available() > 0) input.read()  // drop anything left over from a previous answer
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        // The answer ends with the ">" prompt. If it's late, keep listening a little longer: giving up
        // early would leave the rest of this answer to be mistaken for the answer to the NEXT question.
        val deadline = System.currentTimeMillis() + timeoutMs + GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("adapter closed the connection")
                if (c == '>'.code) return clean(sb.toString(), cmd)
                // Printable text and line ends only (some adapters send stray zero bytes); a runaway
                // answer is cut off, but still read up to its prompt.
                if ((c in 0x20..0x7E || c == '\r'.code || c == '\n'.code) && sb.length < MAX_ANSWER) sb.append(c.toChar())
            } else Thread.sleep(10)
        }
        // No prompt at all: the adapter is stuck. Start over with a fresh connection rather than
        // reading on with question and answers out of step.
        throw IOException(if (sb.isEmpty()) "adapter didn't answer '$cmd'" else "adapter stopped mid-answer to '$cmd'")
    }

    /** Drops our own command if the adapter echoes it (echo-off isn't honoured by every clone). */
    private fun clean(answer: String, cmd: String): String {
        val t = answer.trimStart()
        return if (t.startsWith(cmd) && t.drop(cmd.length).let { it.isEmpty() || it[0] == '\r' || it[0] == '\n' }) t.drop(cmd.length) else answer
    }

    override fun close() = onClose()

    companion object {
        const val GRACE_MS = 2_000L
        const val MAX_ANSWER = 4096
    }
}

/** Classic Bluetooth (serial port profile) - how the Vgate iCar Pro 2S talks to Android ("Android-Vlink"). */
object BluetoothObd {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Names OBD adapters usually have - used to suggest the right paired device. */
    fun looksLikeObd(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return listOf("vlink", "vgate", "icar", "obd", "elm", "v-link", "obdii", "obd2").any { it in n }
    }

    /** Blocking (up to ~15 s). [socketOut] receives the socket first, so another thread can abort by closing it. */
    @SuppressLint("MissingPermission")  // callers check BLUETOOTH_CONNECT first
    fun connect(ctx: Context, address: String, cancelled: () -> Boolean = { false }, socketOut: (BluetoothSocket) -> Unit): ObdLink {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        fun attempt(secure: Boolean): BluetoothSocket {
            val s = if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
            socketOut(s)
            try { s.connect(); return s } catch (e: IOException) { runCatching { s.close() }; throw e }
        }
        // Some adapters only accept the "insecure" kind of connection - but not after we were told to stop.
        val socket = try { attempt(secure = true) } catch (e: IOException) { if (cancelled()) throw e; attempt(secure = false) }
        return StreamLink(socket.inputStream, socket.outputStream) { runCatching { socket.close() } }
    }
}
