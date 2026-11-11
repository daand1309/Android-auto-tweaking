package com.daand.drivemusic

import android.content.Context

class Prefs(context: Context) {
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

    var lastCarState: String
        get() = sp.getString("last_car_state", "unknown") ?: "unknown"
        set(v) = sp.edit().putString("last_car_state", v).apply()
}
