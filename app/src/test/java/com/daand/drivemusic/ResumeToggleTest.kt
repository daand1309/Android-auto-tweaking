package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeToggleTest {
    @Test fun offForThisDriveShowsOff() {
        assertTrue(ResumeToggle.isOn(setting = true, offThisDrive = false))
        assertFalse("the app turned it off for this drive (pauses looked deliberate)", ResumeToggle.isOn(setting = true, offThisDrive = true))
        assertFalse(ResumeToggle.isOn(setting = false, offThisDrive = false))
    }

    @Test fun turningItOffOnlyLastsThisDrive() {
        // The guide promises "auto-resume for this drive": the setting itself must stay on.
        assertEquals(ResumeToggle.State(setting = true, offThisDrive = true), ResumeToggle.tap(ResumeToggle.State(true, false), inDrive = true))
    }

    @Test fun turningItBackOnUndoesThisDrivesOff() {
        assertEquals(ResumeToggle.State(setting = true, offThisDrive = false), ResumeToggle.tap(ResumeToggle.State(true, true), inDrive = true))
    }

    @Test fun turningItOnWhenTheSettingIsOffSwitchesTheSettingOn() {
        assertEquals(ResumeToggle.State(setting = true, offThisDrive = false), ResumeToggle.tap(ResumeToggle.State(false, false), inDrive = true))
    }

    @Test fun outsideADriveATapIsTheSettingItself() {
        // There is no "this drive" when parked (it resets when the next drive starts): the Quick
        // Settings tile then changes the setting, in both directions.
        assertEquals(ResumeToggle.State(setting = false, offThisDrive = false), ResumeToggle.tap(ResumeToggle.State(true, false), inDrive = false))
        assertEquals(ResumeToggle.State(setting = true, offThisDrive = false), ResumeToggle.tap(ResumeToggle.State(false, false), inDrive = false))
    }
}
