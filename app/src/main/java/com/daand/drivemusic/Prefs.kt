package com.daand.drivemusic

import android.content.Context

class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = context.applicationContext.getSharedPreferences("drive_music", Context.MODE_PRIVATE)

    /** Start YouTube Music playback automatically when Android Auto connects. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** Automatically resume if music pauses while driving and nothing else is using the audio. */
    var autoResume: Boolean
        get() = sp.getBoolean("auto_resume", true)
        set(v) = sp.edit().putBoolean("auto_resume", v).apply()

    /** Seconds to wait after an unexpected pause before resuming. */
    var resumeDelaySec: Int
        get() = sp.getInt("resume_delay", 8)
        set(v) = sp.edit().putInt("resume_delay", v).apply()

    /** Package of the YouTube Music app to control (official or patched e.g. Morphe). Auto-detected if not set. */
    var musicPackage: String
        get() = sp.getString("music_pkg", null)?.takeIf { MusicApps.isInstalled(ctx.packageManager, it) }
            ?: MusicApps.autoDetect(ctx).also { sp.edit().putString("music_pkg", it).apply() }
        set(v) = sp.edit().putString("music_pkg", v).apply()

    /** Seconds to wait after Android Auto connects before pressing play (lets the car settle). */
    var startDelaySec: Int
        get() = sp.getInt("start_delay", 2)
        set(v) = sp.edit().putInt("start_delay", v).apply()

    var setVolumeOnConnect: Boolean
        get() = sp.getBoolean("set_volume", false)
        set(v) = sp.edit().putBoolean("set_volume", v).apply()

    var connectVolumePct: Int
        get() = sp.getInt("volume_pct", 60)
        set(v) = sp.edit().putInt("volume_pct", v).apply()

    /** Pause the music when Android Auto disconnects, so it doesn't carry on from the phone speaker. */
    var pauseOnDisconnect: Boolean
        get() = sp.getBoolean("pause_on_disconnect", true)
        set(v) = sp.edit().putBoolean("pause_on_disconnect", v).apply()

    var lastDrive: String
        get() = sp.getString("last_drive", "") ?: ""
        set(v) = sp.edit().putString("last_drive", v).apply()

    /** Check GitHub for app updates once a day (never while driving). */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** Version name of an available update we already notified about ("" = none). */
    var notifiedUpdate: String
        get() = sp.getString("notified_update", "") ?: ""
        set(v) = sp.edit().putString("notified_update", v).apply()

    var updateStatus: String
        get() = sp.getString("update_status", "") ?: ""
        set(v) = sp.edit().putString("update_status", v).apply()

    var lastCarState: String
        get() = sp.getString("last_car_state", "unknown") ?: "unknown"
        set(v) = sp.edit().putString("last_car_state", v).apply()
}
