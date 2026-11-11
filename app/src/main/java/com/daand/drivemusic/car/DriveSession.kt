package com.daand.drivemusic.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class DriveSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DriveScreen(carContext)
}
