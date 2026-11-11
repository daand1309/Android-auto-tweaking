package com.daand.drivemusic.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.daand.drivemusic.DriveMonitorService
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.WelcomeAnnouncer

/** The Drive Music screen on the car display: now-playing, status and a few controls. */
class DriveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val prefs = Prefs(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()  // keep now-playing fresh while the screen is visible
            handler.postDelayed(this, 5000)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        handler.postDelayed(refresh, 5000)
    }

    override fun onPause(owner: LifecycleOwner) {
        handler.removeCallbacks(refresh)
    }

    override fun onGetTemplate(): Template {
        val service = DriveMonitorService.instance
        val song = service?.currentSong()

        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle("Now playing")
                .addText(song?.let { "${it.title}${if (it.artist.isNotBlank()) " — ${it.artist}" else ""}" } ?: "Nothing playing yet")
                .build()
        )
        list.addItem(
            Row.Builder()
                .setTitle("Status")
                .addText(if (service == null) "Monitor not running — open the app" else prefs.lastCarState)
                .build()
        )
        if (prefs.lastDrive.isNotBlank()) {
            list.addItem(Row.Builder().setTitle("Last drive").addText(prefs.lastDrive.replace("\n", " · ")).build())
        }

        val resumeOn = prefs.autoResume
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Play")
                    .setOnClickListener {
                        DriveMonitorService.instance?.startMusic("car screen Play button", requireCar = false)
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Welcome")
                    .setOnClickListener {
                        WelcomeAnnouncer.run(carContext, DriveMonitorService.instance?.currentSong())
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (resumeOn) "Resume: On" else "Resume: Off")
                    .setBackgroundColor(if (resumeOn) CarColor.GREEN else CarColor.DEFAULT)
                    .setOnClickListener {
                        prefs.autoResume = !prefs.autoResume
                        if (prefs.autoResume) DriveMonitorService.instance?.resumeOffThisDrive = false
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Drive Music")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .setActionStrip(actions)
            .build()
    }
}
