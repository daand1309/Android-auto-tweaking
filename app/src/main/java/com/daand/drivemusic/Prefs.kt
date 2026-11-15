package com.daand.drivemusic

import android.content.Context

class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = context.applicationContext.getSharedPreferences("drive_music", Context.MODE_PRIVATE)

    private companion object {
        /** How long a detected music app is trusted (see [musicPackage]). */
        const val DETECT_TTL_MS = 60_000L
        /** package + when it was found, shared across Prefs instances (one is made per call site). */
        @Volatile var detected: Pair<String, Long>? = null
    }

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
        get() = sp.getInt("resume_delay", 8).coerceIn(2, 60)
        set(v) = sp.edit().putInt("resume_delay", v).apply()

    /** Package of the YouTube Music app to control (official or patched e.g. Morphe). Auto-detected if not set. */
    var musicPackage: String
        get() {
            val stored = sp.getString("music_pkg", null)
            if (stored != null && MusicApps.isInstalled(ctx.packageManager, stored)) return stored
            // Nothing usable stored (or the app was uninstalled): find it. That asks the package
            // manager about every launcher app, so the answer is kept for a minute - this getter runs
            // on every page render and every playback event, and re-scanning (and re-writing the
            // preference) each time was pure waste. A fresh answer matters only when the installed
            // apps changed, hence the short lifetime.
            val now = System.currentTimeMillis()
            detected?.let { (pkg, at) ->
                if (now - at < DETECT_TTL_MS && MusicApps.isInstalled(ctx.packageManager, pkg)) return pkg
            }
            val found = MusicApps.autoDetect(ctx)
            detected = found to now
            if (found != stored) sp.edit().putString("music_pkg", found).apply()  // once, not on every read
            return found
        }
        set(v) {
            detected = null
            sp.edit().putString("music_pkg", v).apply()
        }

        /** Test hook: forget a cached auto-detection (each test starts from a clean set of installed apps). */
        @androidx.annotation.VisibleForTesting
        fun clearDetectedForTest() { detected = null }

    /** Seconds to wait after Android Auto connects before pressing play (lets the car settle). */
    var startDelaySec: Int
        get() = sp.getInt("start_delay", 2).coerceIn(0, 30)
        set(v) = sp.edit().putInt("start_delay", v).apply()

    var setVolumeOnConnect: Boolean
        get() = sp.getBoolean("set_volume", false)
        set(v) = sp.edit().putBoolean("set_volume", v).apply()

    var connectVolumePct: Int
        get() = sp.getInt("volume_pct", 60).coerceIn(10, 100)
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

    /** During a drive: speak up when weather / road conditions change. */
    var updatesWeather: Boolean
        get() = sp.getBoolean("updates_weather", true)
        set(v) = sp.edit().putBoolean("updates_weather", v).apply()

    /** During a drive: breaking news (strictly filtered). */
    var updatesNews: Boolean
        get() = sp.getBoolean("updates_news", true)
        set(v) = sp.edit().putBoolean("updates_news", v).apply()

    /** Ask ("there is an update, shall I go ahead?") with green/red buttons before reading updates. */
    var askBeforeUpdates: Boolean
        get() = sp.getBoolean("ask_before_updates", true)
        set(v) = sp.edit().putBoolean("ask_before_updates", v).apply()

    /** A story you follow during drives: comma-separated keywords ("kabinet, coalitie, Jetten"). */
    var followTopic: String
        get() = sp.getString("follow_topic", "") ?: ""
        set(v) = sp.edit().putString("follow_topic", v).apply()

    /** Following stops by itself at this time (so an old story doesn't keep nagging). */
    var followUntil: Long
        get() = sp.getLong("follow_until", 0L)
        set(v) = sp.edit().putLong("follow_until", v).apply()

    val followActive get() = followTopic.isNotBlank() && System.currentTimeMillis() < followUntil

    /** Headlines already read out, so a story is never told twice. */
    var toldNews: List<String>
        get() = (sp.getString("told_news", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("told_news", v.joinToString("\n")).apply()

    /** Read the car through an OBD Bluetooth adapter while driving. */
    var obdEnabled: Boolean
        get() = sp.getBoolean("obd_enabled", false)
        set(v) = sp.edit().putBoolean("obd_enabled", v).apply()

    /** Bluetooth address of the paired OBD adapter (e.g. "Android-Vlink"). */
    var obdDevice: String
        get() = sp.getString("obd_device", "") ?: ""
        set(v) = sp.edit().putString("obd_device", v).apply()

    var obdDeviceName: String
        get() = sp.getString("obd_device_name", "") ?: ""
        set(v) = sp.edit().putString("obd_device_name", v).apply()

    /** Say when the engine has warmed up (after a cold start). */
    var obdWarmup: Boolean
        get() = sp.getBoolean("obd_warmup", true)
        set(v) = sp.edit().putBoolean("obd_warmup", v).apply()

    /** After a cold start, say when the engine has idled long enough to drive off. 0 = off. */
    var obdReadySeconds: Int
        get() = sp.getInt("obd_ready_s", 30).let { if (it == 0) 0 else it.coerceIn(10, 120) }
        set(v) = sp.edit().putInt("obd_ready_s", v).apply()

    /** Fault codes already told, with when ("P0420=1731000000000"), so known ones aren't news at every start. */
    var obdToldCodes: Map<String, Long>
        get() = (sp.getString("obd_told", "") ?: "").split(',').mapNotNull {
            val (k, v) = it.split('=').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            v.toLongOrNull()?.let { t -> k to t }
        }.toMap()
        set(v) = sp.edit().putString("obd_told", v.entries.take(50).joinToString(",") { "${it.key}=${it.value}" }).apply()

    /** Engine size in litres (for fuel use when the car reports intake pressure but no air flow). */
    var obdEngineLitres: Double
        get() = sp.getFloat("obd_engine_l", 1.6f).toDouble().let { if (it.isNaN()) 1.6 else it.coerceIn(0.8, 6.0) }
        set(v) = sp.edit().putFloat("obd_engine_l", v.toFloat()).apply()

    /** Fuel tank size in litres (for the range estimate). The 2011 ASX: about 60 L. */
    var tankLitres: Double
        get() = sp.getFloat("tank_l", 60f).toDouble().let { if (it.isNaN()) 60.0 else it.coerceIn(20.0, 150.0) }
        set(v) = sp.edit().putFloat("tank_l", v.toFloat()).apply()

    /** Fuel price per litre (0 = not set: costs are hidden). Used when automatic lookup is off or stale. */
    var fuelPrice: Double
        get() = sp.getFloat("fuel_price", 0f).toDouble().let { if (it.isNaN() || it < 0) 0.0 else it.coerceAtMost(10.0) }
        set(v) = sp.edit().putFloat("fuel_price", v.toFloat()).apply()

    /** Look the fuel price up for the area you're in (ANWB stations), instead of typing it in. */
    var fuelPriceAuto: Boolean
        get() = sp.getBoolean("fuel_price_auto", true)
        set(v) = sp.edit().putBoolean("fuel_price_auto", v).apply()

    /** Which fuel this car takes (see FuelPrices.Fuel: EURO95 / DIESEL / AUTOGAS). */
    var fuelType: String
        get() = sp.getString("fuel_type", FuelPrices.Fuel.EURO95.code) ?: FuelPrices.Fuel.EURO95.code
        set(v) = sp.edit().putString("fuel_type", v).apply()

    /** The price fetched last: € per litre (0 = never), when, and where it came from. */
    var fuelPriceQuoted: Double
        get() = sp.getFloat("fuel_quoted", 0f).toDouble().let { if (it.isNaN() || it < 0) 0.0 else it.coerceAtMost(10.0) }
        set(v) = sp.edit().putFloat("fuel_quoted", v.toFloat()).apply()
    var fuelPriceQuoteAt: Long
        get() = sp.getLong("fuel_quoted_at", 0L)
        set(v) = sp.edit().putLong("fuel_quoted_at", v).apply()
    /**
     * When a lookup was last *attempted* (0 = never). Without this, a lookup that can never succeed -
     * no location permission, abroad, the service down - was retried on every single app open.
     */
    var fuelPriceTriedAt: Long
        get() = sp.getLong("fuel_tried_at", 0L)
        set(v) = sp.edit().putLong("fuel_tried_at", v).apply()
    /** e.g. "Esso Cartesiusweg, Utrecht · 1,2 km · 14 stations" - shown with the price. */
    var fuelPriceQuoteSource: String
        get() = sp.getString("fuel_quoted_src", "") ?: ""
        set(v) = sp.edit().putString("fuel_quoted_src", v.take(120)).apply()

    /** The price the app actually charges for costs: fetched while fresh, else what you typed in. */
    val fuelPriceForCosts: Double
        get() = FuelPrices.effectivePrice(
            auto = fuelPriceAuto, quoted = fuelPriceQuoted, quotedAt = fuelPriceQuoteAt,
            manual = fuelPrice, now = System.currentTimeMillis(),
        )

    /** Last service (0 = not set) and the interval. */
    var serviceDate: Long
        get() = sp.getLong("service_date", 0L)
        set(v) = sp.edit().putLong("service_date", v).apply()
    var serviceKm: Int
        get() = sp.getInt("service_km", 15_000).coerceIn(1_000, 60_000)
        set(v) = sp.edit().putInt("service_km", v).apply()
    var serviceMonths: Int
        get() = sp.getInt("service_months", 12).coerceIn(1, 36)
        set(v) = sp.edit().putInt("service_months", v).apply()
    /** Remind me in the greeting when the service is due (off = the car's own reminder is enough). */
    var serviceReminderOn: Boolean
        get() = sp.getBoolean("service_reminder_on", true)
        set(v) = sp.edit().putBoolean("service_reminder_on", v).apply()
    /** Synced with the car's dashboard: when, and the km / days it showed then (0 = never synced). */
    var serviceSyncAt: Long
        get() = sp.getLong("service_sync_at", 0L)
        set(v) = sp.edit().putLong("service_sync_at", v).apply()
    var serviceSyncKm: Int
        get() = sp.getInt("service_sync_km", 0).coerceIn(-60_000, 60_000)
        set(v) = sp.edit().putInt("service_sync_km", v).apply()
    var serviceSyncDays: Int
        get() = sp.getInt("service_sync_days", 0).coerceIn(-3650, 3650)
        set(v) = sp.edit().putInt("service_sync_days", v).apply()
    /** Early-warning trends: when each kind was last told (kind=time), and kinds still to say in a greeting. */
    var trendTold: Map<String, Long>
        get() = (sp.getString("trend_told", "") ?: "").split(',').mapNotNull {
            val kv = it.split('='); if (kv.size != 2) null else kv[1].toLongOrNull()?.let { t -> kv[0] to t }
        }.toMap()
        set(v) = sp.edit().putString("trend_told", v.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    /** How often each stored / pending fault code showed up in reads (see CodeTracker), across drives. */
    var storedCodeLog: String
        get() = sp.getString("code_log_stored", "") ?: ""
        set(v) = sp.edit().putString("code_log_stored", v).apply()
    var pendingCodeLog: String
        get() = sp.getString("code_log_pending", "") ?: ""
        set(v) = sp.edit().putString("code_log_pending", v).apply()
    var trendToSay: List<String>
        get() = (sp.getString("trend_say", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("trend_say", v.joinToString(",")).apply()
    /** Premium voice (Microsoft Azure, free tier) - see CloudVoice. The key is never backed up or logged. */
    var cloudVoiceOn: Boolean
        get() = sp.getBoolean("cloud_voice_on", false)
        set(v) = sp.edit().putBoolean("cloud_voice_on", v).apply()
    var azureKey: String
        get() = sp.getString("azure_key", "") ?: ""
        set(v) = sp.edit().putString("azure_key", v.trim()).apply()
    var azureRegion: String
        get() = sp.getString("azure_region", CloudVoice.DEFAULT_REGION) ?: CloudVoice.DEFAULT_REGION
        set(v) = sp.edit().putString("azure_region", v).apply()
    var azureVoice: String
        get() = sp.getString("azure_voice", "en-GB-ThomasNeural") ?: "en-GB-ThomasNeural"
        set(v) = sp.edit().putString("azure_voice", v).apply()
    /** Characters sent this month (counted against the free allowance) and which month that is. */
    var cloudVoiceChars: Int
        get() = sp.getInt("cloud_voice_chars", 0)
        set(v) = sp.edit().putInt("cloud_voice_chars", v).apply()
    var cloudVoiceMonth: String
        get() = sp.getString("cloud_voice_month", "") ?: ""
        set(v) = sp.edit().putString("cloud_voice_month", v).apply()
    /** The app's animations (launch, pages, Drive Mode power-up); independent of the phone's setting. */
    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()
    /** After a drive with the OBD adapter, remind me to take it out (stays until a button is tapped). */
    var obdUnplugReminder: Boolean
        get() = sp.getBoolean("obd_unplug_reminder", true)
        set(v) = sp.edit().putBoolean("obd_unplug_reminder", v).apply()
    /** "Take the adapter out?" was asked after the last drive and not answered yet (shown in the app too). */
    var adapterPromptPending: Boolean
        get() = sp.getBoolean("adapter_prompt_pending", false)
        set(v) = sp.edit().putBoolean("adapter_prompt_pending", v).apply()
    /** Battery readings before each start, with parked time and adapter state (see BatteryLog). */
    var batteryEvents: List<com.daand.drivemusic.obd.BatteryLog.Entry>
        get() = com.daand.drivemusic.obd.BatteryLog.decode(sp.getString("battery_events", "") ?: "")
        set(v) = sp.edit().putString("battery_events", com.daand.drivemusic.obd.BatteryLog.encode(v)).apply()
    /** When the last drive ended (for "parked 14 h"). */
    var lastDriveEndAt: Long
        get() = sp.getLong("last_drive_end", 0L)
        set(v) = sp.edit().putLong("last_drive_end", v).apply()
    /** The OBD adapter while parked since the last drive: BatteryLog.ADAPTER_IN / _OUT / _UNKNOWN. */
    var adapterWhileParked: Int
        get() = sp.getInt("adapter_parked", -1)
        set(v) = sp.edit().putInt("adapter_parked", v).apply()
    var lastServiceReminder: Long
        get() = sp.getLong("service_reminded", 0L)
        set(v) = sp.edit().putLong("service_reminded", v).apply()

    /** Resting battery voltage before each start: "day=volts,..." (last 30). */
    var batteryLog: List<Pair<Long, Double>>
        get() = (sp.getString("battery_log", "") ?: "").split(',').mapNotNull {
            val p = it.split('='); if (p.size != 2) null else { val d = p[0].toLongOrNull(); val v = p[1].toDoubleOrNull(); if (d == null || v == null) null else d to v }
        }
        set(v) = sp.edit().putString("battery_log", v.takeLast(30).joinToString(",") { "${it.first}=${it.second}" }).apply()
    var lastBatteryWarning: Long
        get() = sp.getLong("battery_warned", 0L)
        set(v) = sp.edit().putLong("battery_warned", v).apply()

    /** Say when fuel gets low. */
    var obdFuel: Boolean
        get() = sp.getBoolean("obd_fuel", true)
        set(v) = sp.edit().putBoolean("obd_fuel", v).apply()

    /** "sassy" (default) or "butler". */
    var greetingStyle: String
        get() = sp.getString("greeting_style", "sassy") ?: "sassy"
        set(v) = sp.edit().putString("greeting_style", v).apply()

    val butler get() = greetingStyle == "butler"

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
        get() = sp.getFloat("speech_rate", 0.95f).let { if (it.isNaN()) 0.95f else it.coerceIn(0.6f, 1.4f) }
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    var speechPitch: Float
        get() = sp.getFloat("speech_pitch", 1.0f).let { if (it.isNaN()) 1.0f else it.coerceIn(0.6f, 1.4f) }
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

    /** The "allow full-screen pop-ups" card on the home page was waved away. */
    var fullScreenNagDismissed: Boolean
        get() = sp.getBoolean("fsi_nag_dismissed", false)
        set(v) = sp.edit().putBoolean("fsi_nag_dismissed", v).apply()

    /** Auto-open the phone Drive Mode screen when the car connects (Android Auto, or the adapter). */
    var autoOpenDriveMode: Boolean
        get() = sp.getBoolean("auto_open_drive_mode", true)
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
        "parked_spot", "told_news", "follow_topic", "follow_until", "obd_told",
        "service_reminded", "battery_log", "battery_warned", "trend_told", "trend_say", "code_log_stored", "code_log_pending", "battery_events", "last_drive_end", "adapter_parked",
        // The Azure key never leaves the phone; the usage count must not be reset by restoring an old backup.
        "azure_key", "cloud_voice_chars", "cloud_voice_month",
    )

    /**
     * The settings a backup may restore, with their type. Anything else in a file is ignored, so a
     * damaged or edited backup can't plant a wrong-typed value that would crash the app on start.
     */
    private val restorable = mapOf(
        "announce_song" to "bool", "auto_open_drive_mode" to "bool", "auto_resume" to "bool", "auto_start" to "bool",
        "auto_update" to "bool", "greeting" to "bool", "greeting_dutch" to "bool", "pause_on_disconnect" to "bool",
        "remember_parking" to "bool", "road_conditions" to "bool", "set_volume" to "bool", "show_splash" to "bool",
        "weather" to "bool", "updates_weather" to "bool", "updates_news" to "bool", "ask_before_updates" to "bool",
        "obd_enabled" to "bool", "service_reminder_on" to "bool", "cloud_voice_on" to "bool", "obd_unplug_reminder" to "bool", "animations" to "bool", "obd_warmup" to "bool", "obd_fuel" to "bool",
        "resume_delay" to "int", "start_delay" to "int", "volume_pct" to "int", "obd_ready_s" to "int", "service_km" to "int", "service_months" to "int", "service_sync_km" to "int", "service_sync_days" to "int",
        "speech_pitch" to "float", "speech_rate" to "float", "obd_engine_l" to "float",
        "tank_l" to "float", "fuel_price" to "float", "service_date" to "long", "service_sync_at" to "long", "weather_lat" to "float", "weather_lon" to "float",
        "birthday" to "string", "car_name" to "string", "greeting_name" to "string", "music_pkg" to "string",
        "name_pronunciation" to "string", "theme" to "string", "tts_engine" to "string", "voice_name" to "string",
        "weather_place" to "string", "greeting_style" to "string", "azure_region" to "string", "azure_voice" to "string",
        "obd_device" to "string", "obd_device_name" to "string",
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
            val item = settings.optJSONObject(k) ?: continue
            val type = item.optString("t")
            if (restorable[k] != type) continue  // unknown setting or wrong type: skip it
            if (type == "string" && item.optString("v").length > 500) continue
            val ok = runCatching {  // a single bad value is skipped, not the whole restore
                when (type) {
                    "bool" -> e.putBoolean(k, item.getBoolean("v"))
                    "int" -> e.putInt(k, item.getInt("v"))
                    "float" -> e.putFloat(k, item.getDouble("v").toFloat())
                    "string" -> e.putString(k, item.getString("v"))
                }
            }.isSuccess
            if (ok) n++
        }
        e.apply()
        return n
    }
}
