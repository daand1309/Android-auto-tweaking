package com.daand.drivemusic.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class DriveSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { CarLink.attach(carContext) }
            override fun onDestroy(owner: LifecycleOwner) { CarLink.detach(carContext) }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = DriveScreen(carContext)
}

/**
 * Drive Music's own session on the car screen, while it runs. Through it the app may ask Android
 * Auto to navigate somewhere: your navigation app (Waze) opens on the car display - instead of on
 * the phone, on top of Drive Mode.
 */
object CarLink {
    @Volatile private var ref: java.lang.ref.WeakReference<CarContext>? = null

    fun attach(c: CarContext) { ref = java.lang.ref.WeakReference(c) }
    fun detach(c: CarContext) { if (ref?.get() === c) ref = null }

    /** Navigate on the car screen; false when Drive Music isn't running there (or it refused). Main thread. */
    fun navigate(lat: Double, lon: Double, c: CarContext? = ref?.get()): Boolean {
        c ?: return false
        return runCatching {
            c.startCarApp(Intent(CarContext.ACTION_NAVIGATE, android.net.Uri.parse(com.daand.drivemusic.FuelStops.carNavUri(lat, lon))))
        }.isSuccess
    }

    /**
     * Call through the car (hands-free, the car's own phone screen). A straight call where Android Auto
     * allows it; otherwise the number comes up ready to dial with one tap. Main thread.
     */
    fun call(number: String, c: CarContext? = ref?.get()): Boolean {
        c ?: return false
        val uri = android.net.Uri.parse(com.daand.drivemusic.Shortcuts.telUri(number))
        return runCatching { c.startCarApp(Intent(Intent.ACTION_CALL, uri)) }.isSuccess ||
            runCatching { c.startCarApp(Intent(Intent.ACTION_DIAL, uri)) }.isSuccess
    }
}
