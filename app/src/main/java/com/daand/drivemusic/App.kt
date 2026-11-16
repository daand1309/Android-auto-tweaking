package com.daand.drivemusic

import android.app.Application
import android.util.Log

/** Writes any crash into the drive log (View / share logs) so it can be reported and fixed. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DriveLog.init(this)
        Style.apply(Prefs(this).theme)
        // One line per process start: a shared log then says which build wrote the lines around it
        // (reading a log without that means guessing the version from timestamps).
        runCatching {
            DriveLog.log("Drive Music ${packageManager.getPackageInfo(packageName, 0).versionName} started")
        }
        // A daily update check that doesn't depend on the app being open or the car being used.
        // Re-armed on every process start, so a reboot only delays it until the app runs again.
        runCatching { DailyUpdateCheck.schedule(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // OBD poller is stopped with Thread.interrupt(); sleep() surfaces that as
            // InterruptedException. That is normal shutdown, not a crash.
            if (error is InterruptedException && thread.name == "obd") return@setDefaultUncaughtExceptionHandler
            try {
                val trace = Log.getStackTraceString(error).lines().take(12).joinToString(" | ") { it.trim() }
                DriveLog.log("CRASH in ${thread.name}: $trace")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
