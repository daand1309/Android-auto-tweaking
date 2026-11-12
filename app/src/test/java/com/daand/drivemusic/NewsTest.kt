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
        assertEquals("A12 dicht - AD.nl", items[1].title)  // national feeds: title kept as-is
        assertEquals("A12 dicht", News.parseRegional(xml).single { it.title.startsWith("A12") }.title)  // Google: source removed
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

    // ------------------------------------------------------------------ red team

    @Test fun wordsThatMerelyStartWithAKeywordDontCount() {
        assertTrue(!passes(item("Belastingdienst stuurt aanslagbiljetten te laat")))   // aanslag-biljet = tax bill
        assertTrue(!passes(item("Oorlogsmonument krijgt nieuwe plek")))
    }

    @Test fun softWordsDontHideRealEmergencies() {
        assertTrue(passes(item("Serie explosies in Rotterdam: NL-Alert verstuurd, ramen en deuren dicht")))
        assertTrue(!passes(item("Kat vast in boom: NL-Alert-test bleek grap van buurman")))  // a TEST alert stays soft
    }

    @Test fun entitiesInHeadlinesSurviveParsing() {
        val xml = """<rss><channel><item><title>Brand &amp; explosie in haven</title>
            <pubDate>Thu, 12 Nov 2026 10:12:39 +0100</pubDate></item></channel></rss>"""
        assertEquals("Brand & explosie in haven", News.parse(xml).single().title)
    }

    @Test fun nationalHeadlinesKeepTheirDashes() {
        val xml = """<rss><channel><item><title>Kabinet gevallen - premier treedt af</title>
            <pubDate>Thu, 12 Nov 2026 10:12:39 +0100</pubDate></item></channel></rss>"""
        assertEquals("Kabinet gevallen - premier treedt af", News.parse(xml).single().title)
    }

    @Test fun regionalNewsOnlyFromKnownNewsrooms() {
        val xml = """<rss><channel>
            <item><title>NL-Alert: gifwolk boven Noordwijk - Omroep West</title><pubDate>Thu, 12 Nov 2026 10:12:39 +0100</pubDate></item>
            <item><title>NL-Alert: gifwolk boven Noordwijk - totaal-echt-nieuws.biz</title><pubDate>Thu, 12 Nov 2026 10:12:39 +0100</pubDate></item>
        </channel></rss>"""
        val items = News.parseRegional(xml)
        assertEquals(1, items.size)
        assertEquals("NL-Alert: gifwolk boven Noordwijk", items.single().title)
        assertTrue(items.single().regional)
    }

    @Test fun slightlyFutureDatesFromClockSkewAreFineButNotFarFuture() {
        val urgent = "NL-Alert: grote brand, sluit ramen en deuren"
        assertNotNull(News.pick(listOf(News.Item(urgent, "", now + 5 * 60_000)), now, emptyList()))
        assertNull(News.pick(listOf(News.Item(urgent, "", now + 3 * 60 * 60_000)), now, emptyList()))
    }

    @Test fun englishPluralsCount() {
        assertTrue(passes(item("Floods force evacuations in Limburg, code red issued")))
    }

    // ------------------------------------------------------------------ follow a story

    @Test fun followKeywordsAreParsedSafely() {
        assertEquals(listOf("kabinet", "coalitie", "jetten"), News.keywords(" kabinet, Coalitie ;Jetten,, k "))
        assertEquals(8, News.keywords((1..20).joinToString(",") { "woord$it" }).size)
    }

    @Test fun followPicksTheNewestMatchingHeadline() {
        val kws = News.keywords("kabinet, coalitie, Jetten")
        val items = listOf(
            item("Live | Kabinet-Jetten in totale coalitiecrisis richting cruciaal debat", minsAgo = 90),
            item("VVD stapt uit het kabinet, Jetten naar de koning", minsAgo = 5),
            item("Video | Jetten voor cruciaal belastingdebat", minsAgo = 2),          // clips aren't updates
            item("Live | Teruglezen | Zo verliep de dag in Den Haag", minsAgo = 1),   // recap
            item("Friese munt brengt recordbedrag op", minsAgo = 1),                  // not about the story
            item("Kabinetscrisis: Kamer debatteert vanavond", minsAgo = 14 * 60),     // too old (>12 h)
        )
        assertEquals("VVD stapt uit het kabinet, Jetten naar de koning", News.pickFollowed(items, kws, now, emptyList())?.title)
        // Already told -> the next newest, with "Live |" removed.
        assertEquals("Kabinet-Jetten in totale coalitiecrisis richting cruciaal debat",
            News.pickFollowed(items, kws, now, listOf("VVD stapt uit het kabinet, Jetten naar de koning"))?.title)
        assertNull(News.pickFollowed(items, emptyList(), now, emptyList()))
    }

    @Test fun followedUpdateWording() {
        val f = item("VVD stapt uit het kabinet")
        val nl = DriveUpdates.compose(emptyList(), null, true, true, "", withIntro = false, followed = f, source = "NU.nl")!!
        assertTrue(nl, nl.startsWith("Over het verhaal dat je volgt, meldt NU.nl: VVD stapt uit het kabinet."))
        assertTrue(DriveUpdates.askLine(false, true, followedOnly = true).contains("the story you're following"))
    }
}
