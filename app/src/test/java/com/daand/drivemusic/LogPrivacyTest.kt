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

    @Test fun aNameIsNeverFoundInsideAnotherWord() {
        // The crude version replaced bare text, so "Max" would have mangled "maximum" in another line.
        assertEquals("maximum and (name)", LogPrivacy.redact("maximum and Max", listOf("Max")))
        assertEquals("Maxim is not (name)", LogPrivacy.redact("Maxim is not Max", LogPrivacy.names("Max")))
        // A street or a place that merely starts with the name stays readable.
        assertEquals("Daanstraat, 2 min", LogPrivacy.redact("Daanstraat, 2 min", listOf("Daan")))
    }

    @Test fun aHyphenatedNameAlsoHidesItsPartsAndPunctuationDoesNotHideIt() {
        val spellings = LogPrivacy.names("Dick-hoff")
        assertTrue("both parts and the whole: $spellings", spellings.containsAll(listOf("Dick-hoff", "Dick", "hoff")))
        assertEquals("(name), welcome", LogPrivacy.redact("Dick, welcome", spellings))
        assertEquals("(name) and (name)", LogPrivacy.redact("Dick-hoff and Dickhoff", LogPrivacy.names("Dick-hoff", "Dickhoff")))
        // A name in quotes or before a comma is still a whole word.
        assertEquals("Master, (name).", LogPrivacy.redact("Master, Dick-hoff.", spellings))
    }

    @Test fun shortPartsOfANameAreNotSplitOut() {
        // "D-aan": the parts are one letter each, so only the name itself is matched - no stray redaction.
        val spellings = LogPrivacy.names("D-aan", "Daan")
        assertTrue(spellings.toString(), spellings.none { it.length < 3 })
        assertEquals("(name) arrived", LogPrivacy.redact("Daan arrived", spellings))
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
