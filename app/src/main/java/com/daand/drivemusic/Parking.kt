package com.daand.drivemusic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/**
 * "Where did I park?" - when Android Auto disconnects, the phone's position is saved. Android Auto
 * was just using GPS for navigation, so the last fix is usually fresh and accurate.
 *
 * Bluetooth trackers (SmartTag / Find Hub / AirTag) don't let other apps read their location, so
 * for the tracker we only open its own app.
 */
object Parking {
    data class Spot(val lat: Double, val lon: Double, val accuracyM: Float, val time: Long) {
        fun encode() = "$lat,$lon,$accuracyM,$time"

        companion object {
            fun decode(s: String): Spot? = try {
                val p = s.split(",")
                if (p.size != 4) null else Spot(p[0].toDouble(), p[1].toDouble(), p[2].toFloat(), p[3].toLong())
            } catch (e: NumberFormatException) { null }
        }
    }

    /** A fix older than this at disconnect isn't trusted; we ask for a new one instead. */
    private const val FRESH_MS = 2 * 60 * 1000L

    /** Tracker / find-my apps we can open, in order of preference. */
    val TRACKER_APPS = listOf(
        "com.samsung.android.oneconnect" to "SmartThings Find",
        "com.samsung.android.fmm" to "Samsung Find",
        "com.google.android.apps.adm" to "Google Find Hub",
    )

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Needed to read the location after the car disconnects with the app in the background. */
    fun hasBackgroundPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun spot(ctx: Context): Spot? = Spot.decode(Prefs(ctx).parkedSpot)

    /**
     * The car's position at the moment Android Auto dropped. The drive may only end a while later
     * (stall safeguard), by which time you may have walked away - so the spot is taken now, saved later.
     */
    @Volatile private var dropSpot: Location? = null

    @android.annotation.SuppressLint("MissingPermission")  // checked first
    fun rememberDropSpot(ctx: Context) {
        dropSpot = null
        if (!Prefs(ctx).rememberParking || !hasPermission(ctx) || !hasBackgroundPermission(ctx)) return
        try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val now = System.currentTimeMillis()
            dropSpot = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < FRESH_MS && (!it.hasAccuracy() || it.accuracy <= 50f) }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else 9999f }
        } catch (e: Exception) { dropSpot = null }
    }

    /** End of the drive: the spot from the moment Android Auto dropped, or a fresh look if there was none. */
    fun captureAtDrop(ctx: Context) {
        val spot = dropSpot
        dropSpot = null
        if (spot != null && Prefs(ctx).rememberParking) save(ctx, spot, "fix from when the car stopped") else capture(ctx)
    }

    /** Called when Android Auto disconnects. (Permissions are checked first - lint can't see through the helpers.) */
    @android.annotation.SuppressLint("MissingPermission")
    fun capture(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.rememberParking) return
        if (!hasPermission(ctx) || !hasBackgroundPermission(ctx)) {
            DriveLog.log("PARKING: not saved - location permission 'Allow all the time' is missing")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        try {
            val now = System.currentTimeMillis()
            val last = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < FRESH_MS }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else 9999f }
            if (last != null && last.accuracy <= 50f) { save(ctx, last, "last GPS fix"); return }

            if (Build.VERSION.SDK_INT < 30) { if (last != null) save(ctx, last, "last fix") else miss(); return }
            val provider = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER))
                LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            val cancel = CancellationSignal()
            Handler(Looper.getMainLooper()).postDelayed({ cancel.cancel() }, 30_000)
            lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { loc ->
                when {
                    loc != null -> save(ctx, loc, "new fix")
                    last != null -> save(ctx, last, "last fix")
                    else -> miss()
                }
            }
        } catch (e: Exception) {
            DriveLog.log("PARKING: failed - ${e.message}")
        }
    }

    /** The car is moving again (Android Auto connected): forget the old spot. */
    fun clear(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.parkedSpot.isEmpty()) return
        prefs.parkedSpot = ""
        DriveLog.log("PARKING: car is driving - old spot removed")
    }

    private fun miss() = DriveLog.log("PARKING: no location available")

    private fun save(ctx: Context, l: Location, how: String) {
        if (Prefs(ctx).lastCarState in setOf("Android Auto connected", "Android Automotive")) {
            DriveLog.log("PARKING: location arrived after you drove off - not saved"); return
        }
        val spot = Spot(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f, System.currentTimeMillis())
        Prefs(ctx).parkedSpot = spot.encode()
        // Position itself stays out of the log (logs may be shared).
        DriveLog.log("PARKING: spot saved (±${spot.accuracyM.toInt()} m, $how)")
    }

    /** Opens the spot in a maps app, with walking directions where supported. */
    fun mapsIntent(s: Spot): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}&travelmode=walking"))

    /** Launch intent + name of the first installed tracker app, or null. */
    fun trackerApp(ctx: Context): Pair<Intent, String>? =
        TRACKER_APPS.firstNotNullOfOrNull { (pkg, name) ->
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { it to name }
        }

    /** "Today 14:32" / "Yesterday 09:10" / "Mon 3 Nov 18:05". */
    fun whenText(time: Long, now: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = time
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
        return when (day) {
            today -> "Today $hm"
            yesterday -> "Yesterday $hm"
            else -> java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
        }
    }
}
