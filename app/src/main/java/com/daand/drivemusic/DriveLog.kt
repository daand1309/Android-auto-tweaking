package com.daand.drivemusic

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple rotating file logger. Logs live in the app's private storage and can be shared from the Logs screen. */
object DriveLog {
    private const val TAG = "DriveMusic"
    private const val MAX_BYTES = 512 * 1024L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private lateinit var dir: File
    private val current get() = File(dir, "drive.log")
    private val previous get() = File(dir, "drive.log.1")

    fun init(context: Context) {
        if (!::dir.isInitialized) {
            dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        }
    }

    @Synchronized
    fun log(raw: String) {
        // One entry = one line: text from outside (song titles, Bluetooth names) can't forge extra entries.
        val msg = raw.replace('\r', ' ').replace('\n', ' ').replace('\u2028', ' ').replace('\u2029', ' ')
        Log.i(TAG, msg)
        if (!::dir.isInitialized) return
        try {
            if (current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText("${fmt.format(Date())}  $msg\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing log", e)
        }
    }

    @Synchronized
    fun readAll(): String = buildString {
        if (previous.exists()) append(previous.readText())
        if (current.exists()) append(current.readText())
    }

    @Synchronized
    fun clear() {
        previous.delete()
        current.delete()
    }
}
