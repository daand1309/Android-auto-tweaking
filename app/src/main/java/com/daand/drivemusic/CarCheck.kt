package com.daand.drivemusic

import android.net.Uri

/**
 * The backup check on Android Auto (see DriveMonitorService.startCarCheck): after a drop, ask Android Auto
 * directly whether it's connected, in case its "connected again" announcement never comes. Pure, unit tested.
 */
object CarCheck {
    /** Where CarConnection itself asks (androidx.car.app CarConnectionTypeLiveData). */
    val URI: Uri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()
    const val COLUMN = "CarConnectionState"

    const val EVERY_MS = 20_000L
    /** A Senzo restart takes under a minute; half an hour covers a stop for fuel or a coffee too. */
    const val FOR_MS = 30 * 60_000L

    fun keepAsking(sinceDropMs: Long): Boolean = sinceDropMs in 0 until FOR_MS

    /** 2 = Android Auto (projection), 1 = Android Automotive (native); 0 = not connected. */
    fun isConnected(type: Int): Boolean = type == 1 || type == 2
}
