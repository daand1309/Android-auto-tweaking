package com.daand.drivemusic

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * "Near you" for the Radio tab and the car screen: looked up when asked, kept for 10 minutes or until
 * you've moved ~15 km - checked when the 10 minutes are up (a trip through Belgium brings Belgian stations). Only your rounded position is sent.
 */
object RadioNearby {
    @Volatile var list: List<Radio.Nearby>? = null; private set
    @Volatile var looking = false; private set
    @Volatile private var at = 0L
    @Volatile private var where: Pair<Double, Double>? = null
    const val FRESH_MS = 10 * 60_000L
    /** A look-up that found nothing (no signal): try again after a minute, not after the full 10. */
    private const val RETRY_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())

    /** The stations near you minus the ones that failed today; null while not looked up yet. */
    fun current(): List<Radio.Nearby>? = list?.filterNot { it.station.url in Radio.failed }
        ?.map { n -> Radio.substitutes[n.station.url]?.let { n.copy(station = it.copy(name = n.station.name)) } ?: n }

    /** Look up again if stale; [done] runs on the main thread when there's a new list. */
    fun refresh(ctx: Context, done: () -> Unit = {}) {
        if (looking) return
        // Fresh enough: nothing to do (and no callback - the caller redraws on callbacks, so calling
        // back every time would loop).
        val fresh = System.currentTimeMillis() - at < FRESH_MS
        if (fresh && list != null) return
        looking = true
        Thread({
            val here = Here.position(ctx.applicationContext)
            val moved = here != null && where?.let { FuelPrices.distanceKm(it.first, it.second, here.first, here.second) > 15 } ?: true
            val result = if (here == null || (fresh && !moved && list != null)) null else Radio.nearby(here.first, here.second)
            main.post {
                looking = false
                val before = list
                // A failed look-up (no signal) keeps the stations you already had.
                if (!result.isNullOrEmpty()) {
                    list = result; where = here
                    DriveLog.log("RADIO: ${result.size} stations near you")
                    at = System.currentTimeMillis()
                } else {
                    // Nothing (no position / no signal): keep what we had; say "nothing found" only if we
                    // had nothing, and try again in a minute.
                    if (list == null) list = emptyList()
                    at = System.currentTimeMillis() - FRESH_MS + RETRY_MS
                }
                if (list != before) done()
            }
        }, "radio-near").start()
    }

    /** Blocking (car screen, off the main thread): the list now, looked up if needed. */
    fun blocking(ctx: Context): List<Radio.Nearby> {
        // The phone is already looking (Drive Mode's Radio tab): wait for that answer instead of a second
        // look-up with its own 30 stream checks (20 Nov: two in 3 seconds).
        val until = System.currentTimeMillis() + 45_000
        // A stop ends the wait with the list we have; it must not throw the interrupt away.
        try {
            while (looking && System.currentTimeMillis() < until) Thread.sleep(250)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return current().orEmpty()
        }
        current()?.let { if (System.currentTimeMillis() - at < FRESH_MS) return it }
        if (looking) return current().orEmpty()
        looking = true      // the phone's refresh waits for this one too
        try {
            val here = Here.position(ctx.applicationContext) ?: return current().orEmpty()
            val r = Radio.nearby(here.first, here.second)
            if (r.isNotEmpty()) { list = r; at = System.currentTimeMillis(); where = here }   // no signal: keep the old list
            return current().orEmpty()
        } finally { looking = false }
    }

    /** The station behind a car-screen entry - by its stream, so a list that changed meanwhile can't mix them up. */
    fun byUrl(url: String): Radio.Station? = (current().orEmpty() + list.orEmpty()).firstOrNull { it.station.url == url }?.station
}
