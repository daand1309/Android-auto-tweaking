package com.daand.drivemusic

import com.daand.drivemusic.Radio.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RadioTest {
    @Test fun noListYetMeansTheDutchStations() {
        assertEquals(Radio.DEFAULTS, Radio.decode(""))
        assertTrue(Radio.DEFAULTS.all { Radio.validUrl(it.url) })
        assertEquals(Radio.DEFAULTS.size, Radio.DEFAULTS.map { it.url }.toSet().size)
    }

    @Test fun yourListSurvivesSavingAndRefusesNonStreams() {
        val list = listOf(Station("Joe | Gold", "https://example.org/joe.mp3"), Station("Radio West", "http://example.org/west"))
        val back = Radio.decode(Radio.encode(list))
        assertEquals(listOf("Joe / Gold", "Radio West"), back.map { it.name })
        assertEquals(listOf(Station("Ok", "https://a.b/c")), Radio.decode("Ok|https://a.b/c;Bad|file:///sdcard/x;|https://x.y"))
        assertFalse(Radio.validUrl("intent://evil"))
    }

    @Test fun addingTheSameStreamTwiceKeepsOne() {
        val l = Radio.add(Radio.add(emptyList(), Station("A", "https://a/s")), Station("A again", "https://a/s"))
        assertEquals(listOf(Station("A again", "https://a/s")), l)
    }

    @Test fun searchKeepsPlayableStreamsOnly() {
        val json = """[
          {"name":"QMUSIC  NEDERLAND","url_resolved":"https://stream.qmusic.nl/qmusic/mp3","codec":"MP3"},
          {"name":"Some HLS","url_resolved":"https://x/live.m3u8","codec":"UNKNOWN"},
          {"name":"","url_resolved":"https://x/y.mp3","codec":"MP3"},
          {"name":"Dup","url_resolved":"https://stream.qmusic.nl/qmusic/mp3","codec":"MP3"},
          {"name":"AAC one","url":"https://x/aac","url_resolved":"","codec":"AAC"}]"""
        assertEquals(listOf(Station("QMUSIC NEDERLAND", "https://stream.qmusic.nl/qmusic/mp3"), Station("AAC one", "https://x/aac")),
            Radio.parseSearch(json))
        assertEquals(emptyList<Station>(), Radio.parseSearch("not json"))
        assertTrue(Radio.searchUrl("radio west").contains("name=radio+west"))
    }

    // ---- song titles (real ICY blocks from 18 Nov 2026)

    @Test fun songTitleFromTheStream() {
        assertEquals("Ozzy Osbourne - Dreamer", IcyMeta.parse("StreamTitle='Ozzy Osbourne - Dreamer';StreamUrl='';\u0000\u0000"))
        assertEquals("Breakfast At Tiffany's - DEEP BLUE SOMETHING", IcyMeta.parse("StreamTitle='Breakfast At Tiffany's - DEEP BLUE SOMETHING';"))
        assertEquals("Armin Van Buuren, Chef'special - Larger Than Life", IcyMeta.parse("StreamTitle='Armin Van Buuren, Chef'special - Larger Than Life';StreamUrl='x';"))
        assertEquals(null, IcyMeta.parse("StreamTitle='';"))
        assertEquals(null, IcyMeta.parse("StreamTitle=' - ';"))
        assertEquals(null, IcyMeta.parse("StreamTitle='Advertisement';"))
        assertEquals(null, IcyMeta.parse("nothing here"))
        assertEquals("no markup or control characters", "a b c", IcyMeta.parse("StreamTitle='a<b>\u0007c';"))
    }

    // ---- "Hey Google, play ... on Drive Music"

    @Test fun voiceFindsTheStation() {
        val list = Radio.DEFAULTS
        assertEquals("NPO Radio 2", Radio.match("radio 2", list)?.name)
        assertEquals("NPO Radio 2", Radio.match("NPO Radio 2", list)?.name)
        assertEquals("NPO 3FM", Radio.match("3FM", list)?.name)
        assertEquals("Radio 538", Radio.match("538", list)?.name)
        assertEquals("Qmusic", Radio.match("play qmusic", list)?.name)
        assertEquals("100% NL", Radio.match("100% NL", list)?.name)
        assertEquals("Sky Radio", Radio.match("sky radio", list)?.name)
        assertEquals("a song name goes to YouTube Music", null, Radio.match("Bohemian Rhapsody", list))
        assertEquals(null, Radio.match("", list))
    }

    @Test fun httpStationsAreTriedOverHttps() {
        assertEquals("https://stream.slam.nl/slam", Radio.httpsVariant("http://stream.slam.nl/slam"))
        assertEquals("https://a/b", Radio.httpsVariant("https://a/b"))
    }
}
