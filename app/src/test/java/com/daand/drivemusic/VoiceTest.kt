package com.daand.drivemusic

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class VoiceTest {
    private fun v(name: String, lang: String, country: String, network: Boolean = false, quality: Int = Voice.QUALITY_HIGH, notInstalled: Boolean = false) =
        Voice(name, Locale(lang, country), quality, Voice.LATENCY_NORMAL, network,
            if (notInstalled) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet())

    /** Roughly what Google's speech engine offers on a phone. */
    private val phone = listOf(
        v("en-us-x-iob-local", "en", "US"), v("en-us-x-iol-local", "en", "US"),
        v("en-gb-x-gba-local", "en", "GB"), v("en-gb-x-gbb-local", "en", "GB"),
        v("en-gb-x-gbd-network", "en", "GB", network = true), v("en-gb-x-rjs-local", "en", "GB", notInstalled = true),
        v("en-au-x-aub-local", "en", "AU"), v("nl-nl-x-dma-local", "nl", "NL"),
    )

    /**
     * Skipping: the voice stops and whatever was waiting for it continues - once, however many times
     * you press it. (A double "start the music" is the bug that would come out of getting this wrong.)
     */
    @Test fun stoppingTheVoiceContinuesExactlyOnce() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        var done = 0
        val g = Greeter(app)
        g.speak("Hallo, meneer.", dutch = true) { done++ }
        g.stop("test")
        g.stop("test again")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the music must be started once, not twice", 1, done)
        // Fully qualified: this file imports android.speech.tts.Voice, the app's Voice is another thing.
        assertFalse("nothing is being said any more", com.daand.drivemusic.Voice.speaking)
    }

    @Test fun butlerPicksAnInstalledOfflineBritishMaleVoice() {
        // rjs would be first choice but isn't downloaded; gbd needs internet -> gbb (offline, male, British).
        assertEquals("en-gb-x-gbb-local", VoicePicker.butlerVoice(phone)?.name)
    }

    @Test fun butlerFallsBackToAnyBritishVoiceThenNothing() {
        val femaleOnly = listOf(v("en-gb-x-gba-local", "en", "GB"), v("en-us-x-iol-local", "en", "US"))
        assertEquals("en-gb-x-gba-local", VoicePicker.butlerVoice(femaleOnly)?.name)
        assertNull(VoicePicker.butlerVoice(listOf(v("en-us-x-iol-local", "en", "US"))))
    }

    @Test fun friendlyNamesAreReadable() {
        val names = VoicePicker.friendlyNames(VoicePicker.usable(phone, dutch = false))
        val (title, details) = names.getValue("en-gb-x-gbb-local")
        assertTrue(title, title.startsWith("🇬🇧 British") && title.endsWith("· male"))
        assertTrue(details, details.contains("works offline"))
        assertTrue(names.getValue("en-gb-x-gbd-network").second.contains("needs internet"))
        assertFalse("not-installed voices aren't offered", names.containsKey("en-gb-x-rjs-local"))
        assertNull(VoicePicker.gender("some-other-engine-voice"))
    }

    @Test fun butlerGreetingUsesButlerLines() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 15, 9, 0) }
        repeat(30) { seed ->
            val text = GreetingText.build("Mr. Smith", false, cal, null, "", Random(seed), butler = true)
            assertTrue(text, text.contains("Welcome back, sir. Everything is in order."))
            assertTrue(text, text.endsWith("I'll start your music now. Do drive carefully, sir."))
            assertTrue(text, GreetingText.BUTLER_EN.any { text.contains(it) })
            assertFalse(text, GreetingText.LINES_EN.any { text.contains(it) })
        }
        val nl = GreetingText.build("meneer Smit", true, cal, null, "", Random(1), butler = true)
        assertTrue(nl, nl.contains("Welkom terug, meneer. Alles staat voor u klaar.") && GreetingText.BUTLER_NL.any { nl.contains(it) })
    }

    @Test fun butlerLinesArePlentifulAndUnique() {
        assertTrue(GreetingText.BUTLER_EN.size >= 40)
        assertTrue(GreetingText.BUTLER_NL.size >= 25)
        assertEquals(GreetingText.BUTLER_EN.size, GreetingText.BUTLER_EN.toSet().size)
        assertEquals(GreetingText.BUTLER_NL.size, GreetingText.BUTLER_NL.toSet().size)
    }

    @Test fun theButlerSaysSirNaturallyNotConstantly() {
        val share = GreetingText.BUTLER_EN.count { Regex("""\bsir\b""").containsMatchIn(it) }.toDouble() / GreetingText.BUTLER_EN.size
        assertTrue("share of lines with sir: $share", share in 0.2..0.4)
        val nl = GreetingText.BUTLER_NL.count { it.contains("meneer") }.toDouble() / GreetingText.BUTLER_NL.size
        assertTrue("share of lines with meneer: $nl", nl in 0.2..0.4)
        val cal = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 15, 9, 0) }
        val w = GreetingText.WeatherNow(tempNow = 12.0, code = 3, windKmh = 10.0, tempMin = 8.0, tempMax = 15.0, rainChance = 20)
        repeat(200) { seed ->
            val text = GreetingText.build("Mr. Smith", false, cal, w, "Noordwijk", Random(seed),
                song = GreetingText.Song("Freedom", "Rage Against The Machine"), butler = true)
            val sirs = Regex("""\bsir\b""").findAll(text).count()
            assertTrue("$sirs x sir: $text", sirs in 2..3)
        }
        val dry = w.copy(code = 3)
        val rain = DriveUpdates.changes(dry, dry.copy(code = 63, precipMm = 1.0), dutch = false, butler = true).single()
        assertTrue(rain, rain.startsWith("It has started to rain.") && rain.contains("my mood"))  // butler wording, no forced sir
    }

    /**
     * The drive ended while the voice was still talking: it must stop, and its continuation must still
     * run exactly once - that is where the owner releases what it holds (WelcomeAnnouncer's busy flag,
     * the media menu's voice claim). Refusing the music is the service's job, not the voice's.
     */
    @Test fun endingTheDriveStopsTheVoiceAndStillReleasesTheOwner() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        var done = 0
        val g = Greeter(app)
        g.speak("Hallo, meneer.", dutch = true) { done++ }
        com.daand.drivemusic.Voice.stop("the drive ended")
        g.stop("you pressed skip too")            // a later Skip must not run it a second time
        com.daand.drivemusic.Voice.stop("the drive ended")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the owner is released exactly once", 1, done)
        assertFalse("nothing is being said any more", com.daand.drivemusic.Voice.speaking)
    }

    /** And the slot is free afterwards, which is what lets the next drive greet at all. */
    @Test fun theVoiceSlotIsFreeAgainAfterTheDriveEnded() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        val g = Greeter(app)
        g.speak("Hallo.", dutch = true) {}
        com.daand.drivemusic.Voice.stop("the drive ended")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the voice is free again", com.daand.drivemusic.Voice.speaking)
        val again = Greeter(app)
        again.speak("Hallo.", dutch = true) {}
        assertTrue("a new greeting can take the slot", com.daand.drivemusic.Voice.speaking)
        again.stop("test over")
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The greeting prepares its text (weather, location) on a background thread and only then starts a
     * Greeter. Ending the drive in that window used to leave nothing to stop - Voice.speaking was false
     * - and the later main-thread start spoke into an empty car anyway (Luna's finding 19).
     */
    @Test fun aGreetingCancelledWhileItIsBeingPreparedNeverStartsSpeaking() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        val p = Prefs(app)
        p.weatherEnabled = false      // keep the preparation short: the race is in the post, not the fetch
        p.greetingEnabled = true
        var thenRan = 0
        WelcomeAnnouncer.run(app, recordAsGreeted = false) { thenRan++ }
        // Wait for the background preparation to be underway, then end the drive before the looper runs.
        val deadline = System.currentTimeMillis() + 5_000
        while (!DriveLog.readAll().contains("GREETING: preparing") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        WelcomeAnnouncer.cancel("the drive ended")
        var released = false
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!WelcomeAnnouncer.isBusy) { released = true; break }
            Thread.sleep(20)
        }
        assertTrue("the busy flag is released again", released)
        assertFalse("nothing may start speaking after the drive ended", com.daand.drivemusic.Voice.speaking)
        assertEquals("and the caller is released exactly once", 1, thenRan)
        assertTrue("the log says why:\n" + DriveLog.readAll(),
            DriveLog.readAll().contains("dropped - the drive ended before it started") ||
                DriveLog.readAll().contains("GREETING: cancelled"))
    }

    /**
     * A greeting that never gets said must not leave marks: "greeted just now" would silence the next
     * one for ten minutes, and a reminder it "used up" would be gone (Luna's finding 24).
     */
    @Test fun aCancelledGreetingLeavesNoMarks() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        val p = Prefs(app)
        p.weatherEnabled = false
        p.lastGreetingAt = 0L
        p.trendToSay = listOf("CHARGE")
        WelcomeAnnouncer.run(app, recordAsGreeted = true) {}
        val deadline = System.currentTimeMillis() + 5_000
        while (!DriveLog.readAll().contains("GREETING: preparing") && System.currentTimeMillis() < deadline) Thread.sleep(20)
        WelcomeAnnouncer.cancel("the drive ended")
        val until = System.currentTimeMillis() + 5_000
        while (WelcomeAnnouncer.isBusy && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals("it was not greeted, so the next drive may greet", 0L, p.lastGreetingAt)
        assertEquals("and the warning it would have said is still queued", listOf("CHARGE"), p.trendToSay)
    }

    /** The other side of it: a greeting that does start applies the same bookkeeping. */
    @Test fun aGreetingThatStartsDoesItsBookkeeping() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        DriveLog.init(app)
        val p = Prefs(app)
        p.weatherEnabled = false
        p.lastGreetingAt = 0L
        p.trendToSay = listOf("CHARGE")
        WelcomeAnnouncer.run(app, recordAsGreeted = true) {}
        val until = System.currentTimeMillis() + 8_000
        while (p.lastGreetingAt == 0L && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("it was greeted, so it is recorded", p.lastGreetingAt > 0L)
        assertEquals("and the warning is used up", emptyList<String>(), p.trendToSay)
    }

    @Test fun theVeryFirstGreetingSaysWelcomeNotWelcomeBack() {
        val cal = java.util.Calendar.getInstance()
        fun g(first: Boolean, butler: Boolean = false, dutch: Boolean = false) =
            GreetingText.build("", dutch, cal, null, "", kotlin.random.Random(1), butler = butler, firstDrive = first)
        org.junit.Assert.assertFalse(g(true).contains("Welcome back"))
        org.junit.Assert.assertTrue(g(true).contains("Welcome."))
        org.junit.Assert.assertTrue(g(true, butler = true).contains("Welcome aboard, sir"))
        org.junit.Assert.assertFalse(g(true, dutch = true).contains("terug"))
        org.junit.Assert.assertTrue(g(false).contains("Welcome back"))
    }

    @Test fun uploadsWithTheArtistInTheTitleAreReadProperly() {
        // As in the real log: "Now playing: Kendrick Lamar - Prayer by icarus's archive."
        assertEquals(GreetingText.Song("Prayer", "Kendrick Lamar"), GreetingText.songFromMeta("Kendrick Lamar - Prayer", "icarus's archive"))
        assertEquals(GreetingText.Song("Freedom", "Rage Against The Machine"), GreetingText.songFromMeta("Rage Against The Machine - Freedom", "RATMVEVO"))
        // Normal songs, and a version after the dash, stay as they are.
        assertEquals(GreetingText.Song("Mr. Brightside", "The Killers"), GreetingText.songFromMeta("Mr. Brightside", "The Killers"))
        assertEquals(GreetingText.Song("Bohemian Rhapsody - Remastered 2011", "Queen"),
            GreetingText.songFromMeta("Bohemian Rhapsody - Remastered 2011", "Queen"))
        assertEquals(GreetingText.Song("Kendrick Lamar - Prayer", "Kendrick Lamar"), GreetingText.songFromMeta("Kendrick Lamar - Prayer", "Kendrick Lamar"))
    }
}
