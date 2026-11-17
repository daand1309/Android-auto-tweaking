package com.daand.drivemusic

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (swipe down twice → edit → drag "Auto-resume" in) to switch auto-resume
 * on/off without opening the app, e.g. when you want silence for a phone conversation. During a
 * drive it counts for this drive only; parked, it is the setting (see [ResumeToggle]).
 */
class AutoResumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        DriveLog.init(this)
        ResumeToggle.tapNow(this, "Quick Settings tile")   // this drive while driving, else the setting
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = ResumeToggle.isOnNow(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Auto-resume"
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
