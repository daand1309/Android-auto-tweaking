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
    private val main = Handler(Looper.getMainLooper())

    /** The stations near you minus the ones that failed today; null while not looked up yet. */
    fun current(): List<Radio.Nearby>? = list?.filterNot { it.station.url in Radio.failed }

    /** Look up again if stale; [done] runs on the main thread when there's a new list. */
    fun refresh(ctx: Context, done: () -> Unit = {}) {
        if (looking) return
        // Fresh enough: nothing to do (and no callback - the caller redraws on callbacks, so calling
        // back every time would loop).
        val fresh = System.currentTimeMillis() - at < 10 * 60_000L
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
                if (result != null && (result.isNotEmpty() || list.isNullOrEmpty())) {
                    list = result; where = here
                    DriveLog.log("RADIO: ${result.size} stations near you")
                } else if (list == null) list = emptyList()   // no position: say "nothing found", don't keep asking
                at = System.currentTimeMillis()
                if (list != before) done()
            }
        }, "radio-near").start()
    }

    /** Blocking (car screen, off the main thread): the list now, looked up if needed. */
    fun blocking(ctx: Context): List<Radio.Nearby> {
        current()?.let { if (System.currentTimeMillis() - at < 15 * 60_000L) return it }
        val here = Here.position(ctx.applicationContext) ?: return current().orEmpty()
        val r = Radio.nearby(here.first, here.second)
        list = r; at = System.currentTimeMillis(); where = here
        return current().orEmpty()
    }
}
