package com.daand.drivemusic

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SpeechTextTest {

    // ---------------- names & titles

    @Test fun expandsTitlesInEnglish() {
        assertEquals("Mister Dickhoff", SpeechText.spokenName("Mr. Dickhoff", false))
        assertEquals("Mister Dickhoff", SpeechText.spokenName("mr Dickhoff", false))
        assertEquals("Doctor Who", SpeechText.spokenName("Dr. Who", false))
        assertEquals("Missus Jones", SpeechText.spokenName("Mrs. Jones", false))
    }

    @Test fun expandsTitlesInDutch() {
        assertEquals("meneer Dickhoff", SpeechText.spokenName("Mr. Dickhoff", true))
        assertEquals("meneer Dickhoff", SpeechText.spokenName("Dhr. Dickhoff", true))
        assertEquals("mevrouw Jansen", SpeechText.spokenName("Mevr. Jansen", true))
    }

    @Test fun leavesPlainNamesAlone() {
        assertEquals("Daan", SpeechText.spokenName("  Daan ", false))
        assertEquals("", SpeechText.spokenName("", true))
    }

    // ---------------- numbers & times

    @Test fun negativeNumbers() {
        assertEquals("minus 3", SpeechText.number(-3, false))
        assertEquals("min 3", SpeechText.number(-3, true))
        assertEquals("12", SpeechText.number(12, false))
    }

    @Test fun times() {
        assertEquals("5:05 PM", SpeechText.time("17:05", false))
        assertEquals("12 PM", SpeechText.time("12:00", false))
        assertEquals("12:30 AM", SpeechText.time("00:30", false))
        assertEquals("17.05 uur", SpeechText.time("17:05", true))
        assertEquals("8 uur", SpeechText.time("08:00", true))
    }

    // ---------------- YouTube song titles

    @Test fun cleansArtists() {
        assertEquals("Queen", SpeechText.cleanArtist("Queen - Topic"))
        assertEquals("Queen", SpeechText.cleanArtist("QueenVEVO"))
        assertEquals("Marco Borsato", SpeechText.cleanArtist("Marco Borsato"))
    }

    @Test fun cleansTitles() {
        assertEquals("Bohemian Rhapsody", SpeechText.cleanTitle("Queen - Bohemian Rhapsody (Official Video Remastered)", "Queen", false))
        assertEquals("Blinding Lights", SpeechText.cleanTitle("Blinding Lights [Official Audio] [4K]", "The Weeknd", false))
        assertEquals("Mr. Brightside", SpeechText.cleanTitle("Mr. Brightside", "The Killers", false))
        assertEquals("Here Comes the Sun", SpeechText.cleanTitle("Here Comes the Sun - Remastered 2019", "The Beatles", false))
        assertEquals("Song", SpeechText.cleanTitle("Song | Official Music Video", "X", false))
    }

    @Test fun keepsMeaningfulBrackets() {
        assertEquals("Hallelujah (Live at Wembley)", SpeechText.cleanTitle("Hallelujah (Live at Wembley)", "X", false))
        assertEquals("Shadow (Shadows Remix)", SpeechText.cleanTitle("Shadow (Shadows Remix)", "X", false))
    }

    @Test fun featuring() {
        assertEquals("Stay featuring Justin Bieber", SpeechText.cleanTitle("Stay (feat. Justin Bieber)", "The Kid LAROI", false))
        assertEquals("Stay met Justin Bieber", SpeechText.cleanTitle("Stay ft. Justin Bieber", "The Kid LAROI", true))
    }

    @Test fun removesEmojisAndHashtags() {
        assertEquals("Summer Vibes", SpeechText.cleanTitle("Summer Vibes 🌴🔥 #summer #2026", "X", false))
    }

    @Test fun songSentenceUsesCleanedText() {
        assertEquals(
            "Up first: Bohemian Rhapsody by Queen.",
            GreetingText.songSentence(GreetingText.Song("Queen - Bohemian Rhapsody (Official Video)", "Queen - Topic"), false, true)
        )
    }

    @Test fun splitsSentences() {
        assertEquals(listOf("Good evening, Mister Smith.", "Welcome back!", "Ready?"),
            SpeechText.sentences("Good evening, Mister Smith. Welcome back! Ready?"))
    }

    @Test fun doesNotPauseInsideTitlesWithAbbreviations() {
        assertEquals(listOf("Up first: Mr. Brightside by The Killers.", "Starting your music."),
            SpeechText.sentences("Up first: Mr. Brightside by The Killers. Starting your music."))
        assertEquals(listOf("Now playing: No Role Modelz by J. Cole."),
            SpeechText.sentences("Now playing: No Role Modelz by J. Cole."))
    }

    // ---------------- voice choice

    private fun voice(name: String, locale: Locale, quality: Int, network: Boolean = false, notInstalled: Boolean = false) =
        Voice(name, locale, quality, Voice.LATENCY_NORMAL, network,
            if (notInstalled) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet())

    private val voices = listOf(
        voice("en-us-x-low", Locale.US, Voice.QUALITY_LOW),
        voice("en-gb-x-gba-local", Locale.UK, Voice.QUALITY_HIGH),
        voice("en-gb-x-gbb-local", Locale.UK, Voice.QUALITY_VERY_HIGH),
        voice("en-gb-x-gbc-network", Locale.UK, Voice.QUALITY_VERY_HIGH, network = true),
        voice("en-gb-x-gbd-missing", Locale.UK, 500, notInstalled = true),
        voice("nl-nl-x-tfb-local", Locale("nl", "NL"), Voice.QUALITY_HIGH),
        voice("nl-be-x-bec-local", Locale("nl", "BE"), Voice.QUALITY_VERY_HIGH),
    )

    @Test fun picksBestOfflineUkVoice() {
        assertEquals("en-gb-x-gbb-local", VoicePicker.best(voices, dutch = false)!!.name)
    }

    @Test fun prefersNetherlandsDutchOverBelgian() {
        assertEquals("nl-nl-x-tfb-local", VoicePicker.best(voices, dutch = true)!!.name)
    }

    @Test fun respectsSavedChoiceButIgnoresWrongLanguage() {
        assertEquals("en-gb-x-gba-local", VoicePicker.best(voices, false, preferred = "en-gb-x-gba-local")!!.name)
        assertEquals("nl-nl-x-tfb-local", VoicePicker.best(voices, true, preferred = "en-gb-x-gba-local")!!.name)
    }

    @Test fun skipsVoicesThatAreNotDownloaded() {
        assertEquals(false, VoicePicker.usable(voices, false).any { it.name == "en-gb-x-gbd-missing" })
        assertNull(VoicePicker.best(listOf(voices[4]), false))
    }

    @Test fun friendlyLabel() {
        assertEquals("English (United Kingdom) · voice gbb · offline · very high quality", VoicePicker.label(voices[2]))
    }
}
