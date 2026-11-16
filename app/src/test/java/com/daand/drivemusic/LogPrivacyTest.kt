package com.daand.drivemusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The drive log gets shared when something needs fixing, so the name in it has to be the "{name}"
 * kind - not the name off the phone. Two of these lines are verbatim from a real drive log.
 */
@RunWith(RobolectricTestRunner::class)
class LogPrivacyTest {

    @Test fun theGreetingLineKeepsItsShapeWithoutTheName() {
        val line = """GREETING: "Good morning, Master, Dick-hoff. It is 6 degrees and light rain in Noordwijk. "Now playing: Ayesha Erotica by Vacation Bible School."""
        val out = LogPrivacy.redact(line, listOf("Dickhoff", "Dick-hoff"))
        assertFalse("the name is gone: $out", out.contains("Dick", ignoreCase = true))
        assertTrue("but the rest is still readable: $out", out.contains("Good morning, Master, (name)."))
        assertTrue("including the song: $out", out.contains("Now playing: Ayesha Erotica by Vacation Bible School."))
    }

    @Test fun bothSpellingsAreRemovedWhatEverTheCase() {
        val out = LogPrivacy.redact("Greeting for dAAN (spoken: daan)", listOf("Daan", "D-aan"))
        assertEquals("Greeting for (name) (spoken: (name))", out)
    }

    @Test fun theLongerSpellingIsNotHalfEatenByTheShorterOne() {
        val out = LogPrivacy.redact("Master, Dick-hoff", listOf("Dick", "Dick-hoff"))
        assertEquals("Master, (name)", out)
    }

    @Test fun shortAndBlankEntriesWouldWreckTheLogSoTheyAreIgnored() {
        assertEquals("Driving with music", LogPrivacy.redact("Driving with music", listOf("", "  ", "Dr")))
        assertEquals("Driving with music", LogPrivacy.redact("Driving with music", emptyList()))
    }

    @Test fun aVinNeverReachesTheLogButCodesDo() {
        val out = LogPrivacy.redact("OBD test: Adapter ✓ VIN: WP0ZZZ99ZTS392124 P0420 P0141", emptyList())
        assertEquals("OBD test: Adapter ✓ VIN: (hidden) P0420 P0141", out)
        // A 17-character code that is not announced as a VIN stays as it is (a fault code list is not personal).
        val codes = LogPrivacy.redact("OBD: codes P0420,P0141 12345678901234567", emptyList())
        assertEquals("OBD: codes P0420,P0141 12345678901234567", codes)
    }

    // ---------------------------------------------------------------- and the logger actually uses it

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        DriveLog.init(ctx)
        DriveLog.clear()
        DriveLog.setPrivateNames("Dickhoff", "Dick-hoff")
    }

    @Test fun theDriveLogItselfWritesTheRedactedLine() {
        DriveLog.log("""GREETING: "Welcome back, Dick-hoff. Have a good drive." VIN: WP0ZZZ99ZTS392124""")
        val written = DriveLog.readAll()
        assertFalse("no name in the file: $written", written.contains("Dick", ignoreCase = true))
        assertFalse("no VIN in the file: $written", written.contains("WP0ZZZ99ZTS392124"))
        assertTrue(written, written.contains("Welcome back, (name). Have a good drive."))
        assertTrue(written, written.contains("VIN: (hidden)"))
    }
}
