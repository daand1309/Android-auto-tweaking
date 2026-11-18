package com.daand.drivemusic

import android.content.Context
import android.content.pm.PackageManager

/** Where the phone is right now (for Find fuel and "set Home here"). Blocks up to 10 s: call off the main thread. */
object Here {
    fun position(ctx: Context): Pair<Double, Double>? {
        val granted = { p: String -> ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION) && !granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val lm = ctx.getSystemService(android.location.LocationManager::class.java)
            val now = System.currentTimeMillis()
            fun last(maxAge: Long) = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < maxAge }.maxByOrNull { it.time }
            last(2 * 60_000L)?.let { return it.latitude to it.longitude }   // Waze/Maps usually running: GPS is fresh
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val provider = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                    .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                if (provider != null) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var fix: android.location.Location? = null
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { fix = it; latch.countDown() }
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    fix?.let { return it.latitude to it.longitude }
                }
            }
            last(15 * 60_000L)?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) { null }
    }
}
