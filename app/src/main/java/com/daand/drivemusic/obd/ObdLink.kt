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

/** ELM327 over any pair of streams (Bluetooth socket in the car, a fake adapter in tests). */
class StreamLink(private val input: InputStream, private val output: OutputStream, private val onClose: () -> Unit = {}) : ObdLink {
    override fun send(cmd: String, timeoutMs: Long): String {
        while (input.available() > 0) input.read()  // drop anything left over from a previous answer
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && sb.length < 4096) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("adapter closed the connection")
                if (c.toChar() == '>') return sb.toString()
                sb.append(c.toChar())
            } else Thread.sleep(10)
        }
        if (sb.isEmpty()) throw IOException("adapter didn't answer '$cmd'")
        return sb.toString()  // partial answer: let the parser decide
    }

    override fun close() = onClose()
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
    fun connect(ctx: Context, address: String, socketOut: (BluetoothSocket) -> Unit): ObdLink {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        fun attempt(secure: Boolean): BluetoothSocket {
            val s = if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
            socketOut(s)
            try { s.connect(); return s } catch (e: IOException) { runCatching { s.close() }; throw e }
        }
        val socket = try { attempt(secure = true) } catch (e: IOException) { attempt(secure = false) }
        return StreamLink(socket.inputStream, socket.outputStream) { runCatching { socket.close() } }
    }
}
