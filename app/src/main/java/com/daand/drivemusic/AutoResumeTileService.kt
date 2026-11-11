package com.daand.drivemusic

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (swipe down twice → edit → drag "Auto-resume" in) to switch auto-resume
 * on/off without opening the app, e.g. when you want silence for a phone conversation.
 */
class AutoResumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        DriveLog.init(this)
        val prefs = Prefs(this)
        prefs.autoResume = !prefs.autoResume
        if (prefs.autoResume) DriveMonitorService.instance?.resumeOffThisDrive = false
        DriveLog.log("Quick Settings tile: auto-resume = ${prefs.autoResume}")
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Prefs(this).autoResume
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Auto-resume"
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
