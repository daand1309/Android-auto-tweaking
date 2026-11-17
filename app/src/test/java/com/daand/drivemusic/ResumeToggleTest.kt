package com.daand.drivemusic

import com.daand.drivemusic.ResumeToggle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeToggleTest {
    @Test fun thisDrivesChoiceWinsOverTheSetting() {
        assertTrue(ResumeToggle.isOn(setting = true, thisDrive = null))
        assertFalse(ResumeToggle.isOn(setting = false, thisDrive = null))
        assertFalse("the app turned it off for this drive (pauses looked deliberate)", ResumeToggle.isOn(setting = true, thisDrive = false))
        assertTrue("on for this drive although the setting is off", ResumeToggle.isOn(setting = false, thisDrive = true))
    }

    @Test fun inADriveATapNeverChangesTheSetting() {
        // A button pressed in the car must not change what the next drives do - in either direction.
        assertEquals(State(setting = true, thisDrive = false), ResumeToggle.tap(State(true, null), inDrive = true))
        assertEquals(State(setting = false, thisDrive = true), ResumeToggle.tap(State(false, null), inDrive = true))
    }

    @Test fun tappingBackToTheSettingClearsThisDrivesChoice() {
        assertEquals(State(setting = true, thisDrive = null), ResumeToggle.tap(State(true, false), inDrive = true))
        assertEquals(State(setting = false, thisDrive = null), ResumeToggle.tap(State(false, true), inDrive = true))
    }

    @Test fun outsideADriveATapIsTheSettingItself() {
        // There is no "this drive" when parked (it resets when the next drive starts): the Quick
        // Settings tile then changes the setting, in both directions.
        assertEquals(State(setting = false, thisDrive = null), ResumeToggle.tap(State(true, null), inDrive = false))
        assertEquals(State(setting = true, thisDrive = null), ResumeToggle.tap(State(false, null), inDrive = false))
    }

    @Test fun aStalePerDriveChoiceIsIgnoredWhileParked() {
        // Setting off, tapped on in the car, drive over: parked, the tile must show the setting (off),
        // and a tap there must then switch the setting on - not look like it did nothing.
        assertFalse(ResumeToggle.shown(setting = false, inDrive = false, thisDrive = true))
        assertTrue(ResumeToggle.shown(setting = false, inDrive = true, thisDrive = true))
        assertEquals(State(setting = true, thisDrive = null), ResumeToggle.tap(State(false, true), inDrive = false))
    }
}
