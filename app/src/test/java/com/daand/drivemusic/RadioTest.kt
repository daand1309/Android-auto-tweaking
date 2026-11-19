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

    // ---- stations near you (shapes from radio-browser.info, 19 Nov 2026)

    @Test fun nearbyKeepsPlayableStationsOnceEach() {
        val json = """[
          {"name":"Radio 538","url_resolved":"http://playerservices.streamtheworld.com/api/livestream-redirect/RADIO538.mp3","codec":"MP3","geo_distance":32512.3},
          {"name":"BNR Nieuwsradio","url_resolved":"https://stream.bnr.nl/bnr_mp3_128_20","codec":"MP3","geo_distance":34700.1},
          {"name":"Radio Rijnmond (AAC+)","url_resolved":"https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-aac","codec":"AAC+","geo_distance":37500},
          {"name":"BNR nieuwsradio - http","url_resolved":"https://stream.bnr.nl/bnr_mp3_128_20","codec":"MP3","geo_distance":32000},
          {"name":"Hardcore Power Radio","url_resolved":"https://loa.beheerstream.nl:8011/stream","codec":"MP3","geo_distance":28400},
          {"name":"Hardcore Power Radio (2nd server)","url_resolved":"https://loa.beheerstream.nl:8012/stream","codec":"MP3","geo_distance":28400},
          {"name":"WDR5","url_resolved":"https://wdr.example/wdr5/master.m3u8","codec":"UNKNOWN","geo_distance":1000},
          {"name":"","url_resolved":"https://x/y","codec":"MP3"}]"""
        val near = Radio.parseNearby(json)
        assertEquals(listOf("BNR Nieuwsradio", "Radio Rijnmond (AAC+)", "Hardcore Power Radio"), near.map { it.station.name })
        assertEquals(listOf(34, 37, 28), near.map { it.km })
        assertEquals(emptyList<Radio.Nearby>(), Radio.parseNearby("<html>"))
    }

    @Test fun nearbyOnlySendsARoundedPosition() {
        val u = Radio.nearbyUrl(52.2417, 4.4431, 40)
        assertTrue(u, u.contains("geo_lat=52.2&") && u.contains("geo_long=4.4&") && u.contains("geo_distance=40000"))
    }

    @Test fun carIdsForNearbyStations() {
        val url = "https://stream.bnr.nl/bnr_mp3_128_20"
        assertEquals("by the stream, not the place in the list", url, com.daand.drivemusic.car.MediaMenu.nearUrl(com.daand.drivemusic.car.MediaMenu.nearId(url)))
        assertEquals(null, com.daand.drivemusic.car.MediaMenu.nearUrl(com.daand.drivemusic.car.MediaMenu.radioId(4)))
        assertEquals(null, com.daand.drivemusic.car.MediaMenu.radioIndex(com.daand.drivemusic.car.MediaMenu.nearId(url)))
        assertEquals("never anything but a stream", null, com.daand.drivemusic.car.MediaMenu.nearUrl("near:intent://x"))
    }
}
