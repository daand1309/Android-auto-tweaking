package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)  // for the real XML parser
class NewsTest {
    private val now = System.currentTimeMillis()
    private fun item(title: String, desc: String = "", minsAgo: Long = 20, regional: Boolean = false) =
        News.Item(title, desc, now - minsAgo * 60_000, regional)
    private fun passes(i: News.Item) = News.pick(listOf(i), now, emptyList()) != null

    @Test fun needToKnowNewsGetsThrough() {
        for (t in listOf(
            "NL-Alert: grote brand in Rotterdam, sluit ramen en deuren",
            "Kabinet gevallen na ruzie over belastingplan",
            "Code oranje: zware windstoten in heel Nederland",
            "Landelijke storing bij NS: geen treinen tot vanavond",
            "A12 urenlang dicht na ongeluk met vrachtwagen",
            "Cabinet has fallen after tax dispute",
            "Schiphol closed: airspace closed after drone sightings",
            "Storing 112: alarmnummer in heel Nederland niet bereikbaar",
        )) assertTrue("should pass: $t", passes(item(t)))
    }

    @Test fun softNewsNeverGetsThroughEvenWithScaryWords() {
        for (t in listOf(
            "Brandweer redt kitten uit boom in Noordwijk",
            "Kitten stuck in tree for hours during major fire scare",
            "Kat vast in boom: NL-Alert-test bleek grap van buurman",
            "Ajax wint na dramatische ontknoping",
            "Verstappen wint in Brazilië na explosie van emoties",
            "Recept: zo maak je de perfecte stamppot",
            "Friese munt uit Tachtigjarige Oorlog brengt recordbedrag op bij veiling",
        )) assertNull("should be blocked: $t", News.pick(listOf(item(t)), now, emptyList()))
    }

    @Test fun localIncidentsAndVagueWordsAreNotEnough() {
        assertTrue(!passes(item("Schietpartij in Amsterdam, kogel komt in rijdende bus terecht")))
        assertTrue(!passes(item("War of words between ministers over budget")))
        assertTrue(!passes(item("Warm weather expected this weekend")))  // "war" must not match "warm"
        assertTrue(!passes(item("A4 dicht na ongeluk")))                  // short closure somewhere: not alone
        assertTrue(passes(item("A44 dicht na ongeluk bij Noordwijk", regional = true)))  // ...but yes near you
    }

    @Test fun onlyFreshNeverRepeatedNeverUndated() {
        val urgent = "NL-Alert: gifwolk boven Moerdijk, sluit ramen en deuren"
        assertNull(News.pick(listOf(item(urgent, minsAgo = 5 * 60)), now, emptyList()))       // 5 h old
        assertNull(News.pick(listOf(News.Item(urgent, "", null)), now, emptyList()))           // no date
        assertNull(News.pick(listOf(item(urgent)), now, listOf("NL-Alert: gifwolk boven Moerdijk")))  // told already
        assertNotNull(News.pick(listOf(item(urgent)), now, listOf("Kabinet gevallen")))        // different story
    }

    @Test fun parsesRssWithCdataDatesAndSourceSuffix() {
        val xml = """<?xml version="1.0"?><rss><channel><title>Feed</title>
            <item><title><![CDATA[Grote brand in haven]]></title><description><![CDATA[<p>Rook &amp; vlammen</p>]]></description>
              <pubDate>Thu, 12 Nov 2026 10:12:39 +0100</pubDate></item>
            <item><title>A12 dicht - AD.nl</title><pubDate>Wed, 11 Nov 2026 19:00:00 GMT</pubDate></item>
            <item><title>Cabinet falls</title><pubDate>12 November 2026 - 08:12</pubDate></item>
            <item><title>Evil
            line</title></item>
        </channel></rss>"""
        val items = News.parse(xml)
        assertEquals(4, items.size)
        assertEquals("Grote brand in haven", items[0].title)
        assertTrue(items[0].description.contains("Rook & vlammen"))
        assertEquals("A12 dicht", items[1].title)  // " - AD.nl" removed
        assertTrue(items.take(3).all { it.published != null })
        assertEquals("Evil line", items[3].title)  // one line, untrusted text cleaned
    }

    @Test fun weatherChangesThatMatterAndSilenceOtherwise() {
        val dry = GreetingText.WeatherNow(tempNow = 8.0, code = 3, windKmh = 10.0, tempMin = 5.0, tempMax = 10.0, rainChance = 20)
        assertTrue(DriveUpdates.changes(dry, dry.copy(tempNow = 7.0), dutch = false).isEmpty())
        assertTrue(DriveUpdates.changes(dry, dry.copy(code = 63, precipMm = 1.2), false).single().contains("started to rain"))
        assertTrue(DriveUpdates.changes(dry.copy(code = 63, precipMm = 1.0), dry, false).single().contains("rain has stopped"))
        assertTrue(DriveUpdates.changes(dry, dry.copy(tempNow = 0.5), false).single().contains("freezing"))
        assertTrue(DriveUpdates.changes(dry, dry.copy(code = 45), true).single().contains("mist"))
        assertTrue(DriveUpdates.changes(dry, dry.copy(gustsKmh = 75.0), false).single().contains("Gusts"))
    }

    @Test fun updateWordingPerStyle() {
        val news = item("Kabinet gevallen")
        assertNull(DriveUpdates.compose(emptyList(), null, false, true, "Mr. Smith"))
        val butler = DriveUpdates.compose(listOf("It has started to rain, so the roads will be wet."), news, false, true, "Mr. Smith")!!
        assertTrue(butler, butler.startsWith("Pardon the interruption, Mister Smith. A brief update."))
        assertTrue(butler, butler.contains("Some news you should know: Kabinet gevallen.") && butler.endsWith("I'll leave you to your music."))
        assertTrue(DriveUpdates.compose(emptyList(), news, true, false, "")!!.startsWith("Even een update. Belangrijk nieuws:"))
    }
}
