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
        // 538's http address is tried over https; "(AAC+)" isn't part of the name; copies are dropped.
        assertEquals(listOf("Radio 538", "BNR Nieuwsradio", "Radio Rijnmond", "Hardcore Power Radio"), near.map { it.station.name })
        assertEquals(listOf(32, 34, 37, 28), near.map { it.km })
        assertTrue(near.all { it.station.url.startsWith("https://") })
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

    @Test fun nearbyPrefersTheMp3StreamOfAStation() {
        // Real listings, 19 Nov: the AAC+ stream fails in Android's player, the MP3 one plays.
        val json = """[
          {"name":"Radio Rijnmond (AAC+)","url_resolved":"https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-aacp","codec":"AAC+","geo_distance":37500},
          {"name":"Radio Rijnmond","url_resolved":"http://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-mp3","codec":"MP3","geo_distance":37400}]"""
        val near = Radio.parseNearby(json)
        assertEquals(1, near.size)
        assertEquals("https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-mp3", near.single().station.url)
        assertEquals("Radio Rijnmond", near.single().station.name)
    }

    @Test fun aFailingAacStreamFindsItsMp3Twin() {
        // radio-browser's answer for "Radio Rijnmond" (19 Nov).
        val json = """[
          {"name":"Radio Rijnmond","url_resolved":"http://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-mp3","codec":"MP3"},
          {"name":"Radio Rijnmond (AAC+)","url_resolved":"https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-aacp","codec":"AAC+"},
          {"name":"Radio Rijnmond Extra","url_resolved":"https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-extra-mp3","codec":"MP3"}]"""
        val alt = Radio.mp3Alternative(json, "Radio Rijnmond (AAC+)", "https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-aacp")
        assertEquals("the same station, not 'Extra'", "https://d2e9xgjjdd9cr5.cloudfront.net/icecast/rijnmond/radio-mp3", alt?.url)
        assertEquals(null, Radio.mp3Alternative(json, "Radio West", "x"))
        assertEquals("Radio Rijnmond", Radio.baseName("Radio Rijnmond (AAC+)"))
        assertEquals("SLAM!", Radio.baseName("SLAM! (128 kbps)"))
    }

    // ---- the filter (19 Nov: Radio Rijnmond AAC+ and three silent stations reached the list)

    @Test fun whatAStreamSendsDecidesIfItsListed() {
        assertEquals(Radio.Probe.PLAYS, Radio.judge("audio/mpeg"))
        assertEquals(Radio.Probe.PLAYS, Radio.judge("audio/aac; charset=x"))
        assertEquals(Radio.Probe.AAC_PLUS, Radio.judge("audio/aacp"))
        assertEquals(Radio.Probe.NO, Radio.judge("text/html"))
        assertEquals("a playlist file isn't a stream", Radio.Probe.NO, Radio.judge("audio/x-mpegurl"))
        assertEquals(Radio.Probe.NO, Radio.judge(null))
    }

    @Test fun nearYouOnlyListsStationsThatPlay() {
        val n = { name: String, url: String -> Radio.Nearby(Station(name, url), 30) }
        val list = listOf(n("Radio 538", "https://a/538.mp3"), n("Radio Rijnmond", "https://r/radio-aacp"),
            n("All Day Jazz", "https://dead/stream"), n("SLAM! Housuh", "https://s/aacp-only"))
        val answers = mapOf("https://a/538.mp3" to Radio.Probe.PLAYS, "https://r/radio-aacp" to Radio.Probe.AAC_PLUS,
            "https://r/radio-mp3" to Radio.Probe.PLAYS, "https://s/aacp-only" to Radio.Probe.AAC_PLUS)
        val kept = Radio.checked(list, test = { answers[it] ?: Radio.Probe.NO },
            twin = { st -> if (st.name == "Radio Rijnmond") Station("Radio Rijnmond", "https://r/radio-mp3") else null })
        assertEquals(listOf("Radio 538", "Radio Rijnmond"), kept.map { it.station.name })
        assertEquals("Rijnmond on its MP3 stream", "https://r/radio-mp3", kept[1].station.url)
    }
}
