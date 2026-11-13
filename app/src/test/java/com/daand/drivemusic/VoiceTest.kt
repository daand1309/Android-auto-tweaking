package com.daand.drivemusic

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
}
