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

    // ---- welcome greeting
    var greetingEnabled: Boolean
        get() = sp.getBoolean("greeting", true)
        set(v) = sp.edit().putBoolean("greeting", v).apply()

    /** How the voice addresses you, e.g. "Mr. Smith". Stored only on the phone. */
    var greetingName: String
        get() = sp.getString("greeting_name", "") ?: ""
        set(v) = sp.edit().putString("greeting_name", v).apply()

    var greetingDutch: Boolean
        get() = sp.getBoolean("greeting_dutch", false)
        set(v) = sp.edit().putBoolean("greeting_dutch", v).apply()

    var weatherEnabled: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = sp.edit().putBoolean("weather", v).apply()

    /** Weather location (already rounded to ~10 km). NaN = not set. */
    var weatherLat: Double
        get() = sp.getFloat("weather_lat", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("weather_lat", v.toFloat()).apply()

    var weatherLon: Double
        get() = sp.getFloat("weather_lon", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("weather_lon", v.toFloat()).apply()

    var weatherPlace: String
        get() = sp.getString("weather_place", "") ?: ""
        set(v) = sp.edit().putString("weather_place", v).apply()

    var roadConditionsEnabled: Boolean
        get() = sp.getBoolean("road_conditions", true)
        set(v) = sp.edit().putBoolean("road_conditions", v).apply()

    /** Say which song is about to play. */
    var announceSong: Boolean
        get() = sp.getBoolean("announce_song", true)
        set(v) = sp.edit().putBoolean("announce_song", v).apply()

    // ---- voice
    /** Text-to-speech engine package ("" = Google if installed, else phone default). */
    var ttsEngine: String
        get() = sp.getString("tts_engine", "") ?: ""
        set(v) = sp.edit().putString("tts_engine", v).apply()

    /** Chosen voice name ("" = pick the best automatically). */
    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) = sp.edit().putString("voice_name", v).apply()

    var speechRate: Float
        get() = sp.getFloat("speech_rate", 0.95f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    var speechPitch: Float
        get() = sp.getFloat("speech_pitch", 1.0f)
        set(v) = sp.edit().putFloat("speech_pitch", v).apply()

    /** Optional phonetic spelling of the name, e.g. "Mister Dik-hof". Overrides the name when speaking. */
    var namePronunciation: String
        get() = sp.getString("name_pronunciation", "") ?: ""
        set(v) = sp.edit().putString("name_pronunciation", v).apply()

    /** Optional birthday as "MM-DD" for a birthday greeting. */
    var birthday: String
        get() = sp.getString("birthday", "") ?: ""
        set(v) = sp.edit().putString("birthday", v).apply()

    /** Short weather line cached from the last greeting, shown on the car screen. */
    var lastWeatherSummary: String
        get() = sp.getString("last_weather", "") ?: ""
        set(v) = sp.edit().putString("last_weather", v).apply()

    var lastWeatherAt: Long
        get() = sp.getLong("last_weather_at", 0L)
        set(v) = sp.edit().putLong("last_weather_at", v).apply()

    var lastGreetingAt: Long
        get() = sp.getLong("last_greeting", 0L)
        set(v) = sp.edit().putLong("last_greeting", v).apply()

    /** Auto-open the phone Drive Mode screen when Android Auto connects. */
    var autoOpenDriveMode: Boolean
        get() = sp.getBoolean("auto_open_drive_mode", false)
        set(v) = sp.edit().putBoolean("auto_open_drive_mode", v).apply()

    /** Shown in the Drive Mode intro animation. */
    var carName: String
        get() = sp.getString("car_name", "ASX") ?: "ASX"
        set(v) = sp.edit().putString("car_name", v).apply()

    var showSplash: Boolean
        get() = sp.getBoolean("show_splash", true)
        set(v) = sp.edit().putBoolean("show_splash", v).apply()

    /** Music app + version seen on the previous drive, to spot Morphe updates/renames. */
    var lastSeenMusicApp: String
        get() = sp.getString("last_seen_music_app", "") ?: ""
        set(v) = sp.edit().putString("last_seen_music_app", v).apply()

    var lastMusicChangeAt: Long
        get() = sp.getLong("last_music_change_at", 0L)
        set(v) = sp.edit().putLong("last_music_change_at", v).apply()

    /** UI theme id (see Style.THEMES). */
    var theme: String
        get() = sp.getString("theme", Style.THEME_RED) ?: Style.THEME_RED
        set(v) = sp.edit().putString("theme", v).apply()

    var lastCarState: String
        get() = sp.getString("last_car_state", "unknown") ?: "unknown"
        set(v) = sp.edit().putString("last_car_state", v).apply()

    // ---- backup / restore

    /** Save where the car is when Android Auto disconnects. */
    var rememberParking: Boolean
        get() = sp.getBoolean("remember_parking", false)
        set(v) = sp.edit().putBoolean("remember_parking", v).apply()

    /** Last parked spot as "lat,lon,accuracyMeters,timeMillis" (see [Parking.Spot]); "" = none. */
    var parkedSpot: String
        get() = sp.getString("parked_spot", "") ?: ""
        set(v) = sp.edit().putString("parked_spot", v).apply()

    /** Your own choices only - not run-time state like the last drive or update checks. */
    private val notBackedUp = setOf(
        "last_car_state", "last_drive", "last_greeting", "last_weather", "last_weather_at",
        "last_update_check", "notified_update", "update_status", "last_seen_music_app", "last_music_change_at",
        "parked_spot",
    )

    /** All settings as JSON (each value tagged with its type so it restores exactly). */
    fun exportJson(): String {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            if (k in notBackedUp || v == null) continue
            val type = when (v) {
                is Boolean -> "bool"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"
                else -> continue
            }
            o.put(k, org.json.JSONObject().put("t", type).put("v", if (v is Float) v.toDouble() else v))
        }
        return org.json.JSONObject().put("app", "drive-music").put("version", 1).put("settings", o).toString(2)
    }

    /** Restores settings from [exportJson]. Returns how many were restored; throws if it isn't a backup. */
    fun importJson(json: String): Int {
        val root = org.json.JSONObject(json)
        require(root.optString("app") == "drive-music") { "Not a Drive Music settings file" }
        val settings = root.getJSONObject("settings")
        val e = sp.edit()
        var n = 0
        for (k in settings.keys()) {
            if (k in notBackedUp) continue
            val item = settings.getJSONObject(k)
            when (item.getString("t")) {
                "bool" -> e.putBoolean(k, item.getBoolean("v"))
                "int" -> e.putInt(k, item.getInt("v"))
                "long" -> e.putLong(k, item.getLong("v"))
                "float" -> e.putFloat(k, item.getDouble("v").toFloat())
                "string" -> e.putString(k, item.getString("v"))
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }
}
