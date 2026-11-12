package com.daand.drivemusic.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.daand.drivemusic.Briefing
import com.daand.drivemusic.DriveMonitorService
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.WelcomeAnnouncer

/** The Drive Music screen on the car display: now-playing, media controls and a few settings. */
class DriveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val prefs = Prefs(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature = ""
    private val refresh = object : Runnable {
        override fun run() {
            // Only redraw when something actually changed - no needless template rebuilds.
            val sig = signature()
            if (sig != lastSignature) {
                lastSignature = sig
                invalidate()
            }
            handler.postDelayed(this, 8000)
        }
    }

    /** True while this screen is showing the briefing - then it closes itself when the greeting ends. */
    private var showingBriefing = false

    private val briefingChanged: () -> Unit = {
        if (Briefing.text != null) invalidate()
        else if (showingBriefing) {
            showingBriefing = false
            // Done: get out of the way so the car screen goes back to YouTube Music.
            runCatching { carContext.finishCarApp() }.onFailure { invalidate() }
        }
    }

    init {
        lifecycle.addObserver(this)
        Briefing.addListener(briefingChanged)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Briefing.removeListener(briefingChanged)
    }

    override fun onResume(owner: LifecycleOwner) {
        handler.postDelayed(refresh, 5000)
    }

    override fun onPause(owner: LifecycleOwner) {
        handler.removeCallbacks(refresh)
    }

    private fun state(): CarTemplate.State {
        val svc = DriveMonitorService.instance
        val song = svc?.currentSong()
        val weatherFresh = System.currentTimeMillis() - prefs.lastWeatherAt < 3 * 60 * 60 * 1000L
        val showWeather = prefs.lastWeatherSummary.isNotBlank() && weatherFresh
        return CarTemplate.State(
            nowPlaying = song?.let { "${it.title}${if (it.artist.isNotBlank()) " — ${it.artist}" else ""}" }
                ?: "Nothing playing yet",
            playing = svc?.isPlaying() == true,
            autoResume = prefs.autoResume,
            infoTitle = if (showWeather) "Weather" else "Status",
            infoText = when {
                showWeather -> prefs.lastWeatherSummary
                svc == null -> "Monitor not running — open the app on your phone"
                else -> prefs.lastCarState
            },
        )
    }

    private fun signature() = state().toString()

    override fun onGetTemplate(): Template {
        Briefing.text?.let { text ->
            showingBriefing = true
            return CarTemplate.briefing(Briefing.title, text) {
                showingBriefing = false
                runCatching { carContext.finishCarApp() }
            }
        }
        val s = state()
        lastSignature = s.toString()
        return CarTemplate.build(
            s,
            CarTemplate.Callbacks(
                onPrevious = { DriveMonitorService.instance?.skipPrevious(); invalidate() },
                onPlayPause = { DriveMonitorService.instance?.playPause(); invalidate() },
                onNext = { DriveMonitorService.instance?.skipNext(); invalidate() },
                onToggleResume = {
                    prefs.autoResume = !prefs.autoResume
                    if (prefs.autoResume) DriveMonitorService.instance?.resumeOffThisDrive = false
                    invalidate()
                },
                onWelcome = {
                    WelcomeAnnouncer.run(carContext, DriveMonitorService.instance?.currentSong(), recordAsGreeted = false)
                },
            ),
        )
    }
}
