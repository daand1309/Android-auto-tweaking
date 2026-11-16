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

    /** How much history is kept: this file plus two older ones. A slow problem needs weeks of log. */
    private const val KEEP = 2
    private lateinit var dir: File
    /**
     * The name(s) the user configured for the greeting, removed from every line (see [LogPrivacy]).
     * Set at start-up and whenever the setting changes, because the greeting is logged in full and the
     * log is shared.
     */
    @Volatile private var privateNames: List<String> = emptyList()

    fun setPrivateNames(vararg names: String) { privateNames = names.filter { it.isNotBlank() } }
    private val current get() = File(dir, "drive.log")
    private fun older(n: Int) = File(dir, "drive.log.$n")

    fun init(context: Context) {
        if (!::dir.isInitialized) {
            dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        }
    }

    @Synchronized
    fun log(raw: String) {
        // One entry = one line: text from outside (song titles, Bluetooth names) can't forge extra entries.
        val msg = LogPrivacy.redact(
            raw.replace('\r', ' ').replace('\n', ' ').replace('\u2028', ' ').replace('\u2029', ' '),
            privateNames,
        )
        Log.i(TAG, msg)
        if (!::dir.isInitialized) return
        try {
            if (current.length() > MAX_BYTES) {
                older(KEEP).delete()
                for (n in KEEP - 1 downTo 1) older(n).renameTo(older(n + 1))
                current.renameTo(older(1))
            }
            current.appendText("${fmt.format(Date())}  $msg\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing log", e)
        }
    }

    @Synchronized
    fun readAll(): String = buildString {
        for (n in KEEP downTo 1) older(n).takeIf { it.exists() }?.let { append(it.readText()) }
        if (current.exists()) append(current.readText())
    }

    @Synchronized
    fun clear() {
        for (n in 1..KEEP) older(n).delete()
        current.delete()
    }
}
