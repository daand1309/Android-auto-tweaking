package com.daand.drivemusic.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Drive Music screen shown on the car display (Android Auto).
 *
 * To use it on your own phone: Android Auto → Settings → tap "Version" 10 times to unlock
 * Developer settings → turn on "Unknown sources". Drive Music then appears in the car's app grid.
 */
class DriveCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds: accept any host so the Desktop Head Unit test tool works.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release builds: only the real Android Auto / Automotive hosts (Google-signed).
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DriveSession()
}
