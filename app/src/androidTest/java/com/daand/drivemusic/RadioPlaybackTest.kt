package com.daand.drivemusic

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The radio on a real Android: a real station tunes in, plays, changes station and stops. Skipped
 * (not failed) when the emulator has no internet - the stream itself isn't what's being tested then.
 */
@RunWith(AndroidJUnit4::class)
class RadioPlaybackTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun online(): Boolean = try {
        val c = java.net.URL("https://icecast.omroep.nl/radio2-bb-mp3").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        try { c.responseCode == 200 } finally { c.disconnect() }
    } catch (e: Exception) { false }

    private fun waitFor(what: String, ms: Long, ok: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) { if (ok()) return; SystemClock.sleep(250) }
        throw AssertionError("timed out waiting for $what (state ${RadioPlayer.state}, ${RadioPlayer.problem})")
    }

    @After fun off() = onMain { RadioPlayer.stop("test over") }

    @Test fun tunesInChangesStationAndStops() {
        assumeTrue("no internet on this emulator", online())
        Prefs(ctx).radioStations = ""
        val r2 = Radio.DEFAULTS.first { it.name == "NPO Radio 2" }
        onMain { RadioPlayer.play(ctx, r2) }
        waitFor("NPO Radio 2 to play", 25_000) { RadioPlayer.state == RadioPlayer.State.PLAYING }
        assertEquals("remembered for the one-tap switch", r2, RadioPlayer.lastStation(ctx))
        SystemClock.sleep(3_000)
        assertEquals("still playing after 3 s", RadioPlayer.State.PLAYING, RadioPlayer.state)

        onMain { RadioPlayer.step(ctx, 1) }     // ⏭ = the next station in the list
        assertEquals("NPO 3FM", RadioPlayer.station?.name)
        waitFor("3FM to play", 25_000) { RadioPlayer.state == RadioPlayer.State.PLAYING }

        onMain { RadioPlayer.stop("you pressed stop") }
        assertEquals(RadioPlayer.State.OFF, RadioPlayer.state)
    }

    @Test fun aDeadStationSaysSoInsteadOfHanging() {
        assumeTrue("no internet on this emulator", online())
        onMain { RadioPlayer.play(ctx, Radio.Station("Nowhere FM", "https://icecast.omroep.nl/this-station-does-not-exist-mp3")) }
        waitFor("the error", 50_000) { RadioPlayer.state == RadioPlayer.State.ERROR }
        assertEquals("Nowhere FM", RadioPlayer.station?.name)
    }

    /** What Gemini / the Assistant sends for "play Radio 2 on Drive Music" on the phone. */
    @Test fun voiceOnThePhonePicksTheStation() {
        Prefs(ctx).radioStations = ""
        ctx.startActivity(android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(ctx.packageName).putExtra(android.app.SearchManager.QUERY, "radio 2")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("the voice request to pick a station", 10_000) { RadioPlayer.station?.name == "NPO Radio 2" && RadioPlayer.state != RadioPlayer.State.OFF }
    }

    /** "Near you" for real: radio-browser around Noordwijk, then the most-listened one plays. */
    @Test fun stationsNearNoordwijkPlay() {
        assumeTrue("no internet on this emulator", online())
        val near = Radio.nearby(52.24, 4.44)
        assumeTrue("radio-browser didn't answer", near.isNotEmpty())
        org.junit.Assert.assertTrue("only a few: ${near.size}", near.size >= 8)
        org.junit.Assert.assertTrue(near.all { it.station.url.startsWith("https://") && it.km <= 80 })
        // The first one that plays within the time limit (a station can be down - that's what the app skips).
        var played: String? = null
        for (n in near.take(4)) {
            onMain { RadioPlayer.play(ctx, n.station) }
            val end = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < end && RadioPlayer.state == RadioPlayer.State.CONNECTING) SystemClock.sleep(250)
            if (RadioPlayer.state == RadioPlayer.State.PLAYING) { played = n.station.name; break }
        }
        org.junit.Assert.assertNotNull("none of the 4 nearest-popular stations played", played)
    }

    /** 19 Nov on the phone: Radio Rijnmond's AAC+ stream failed (1/-2147483648). Now it must end up playing. */
    @Test fun rijnmondAacPlaysOneWayOrAnother() {
        assumeTrue("no internet on this emulator", online())
        val aac = Radio.Station("Radio Rijnmond (AAC+)", "https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-aacp")
        onMain { RadioPlayer.play(ctx, aac) }
        waitFor("Rijnmond to play (AAC+ or its MP3 stream)", 60_000) { RadioPlayer.state == RadioPlayer.State.PLAYING }
        android.util.Log.i("DriveMusic", "Rijnmond played from ${RadioPlayer.station?.url}")
    }
}
