package com.daand.drivemusic

import android.app.Application
import android.util.Log

/** Writes any crash into the drive log (View / share logs) so it can be reported and fixed. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DriveLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = Log.getStackTraceString(error).lines().take(12).joinToString(" | ") { it.trim() }
                DriveLog.log("CRASH in ${thread.name}: $trace")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
