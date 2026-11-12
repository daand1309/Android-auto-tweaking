package com.daand.drivemusic

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Breaking news for the drive - deliberately very picky. Only domestic (Netherlands) feeds, only very
 * recent, only stories that hit "you need to know this" keywords, and anything soft (animals, sport,
 * celebrities, recipes...) is thrown out. A kitten stuck in a tree does not get read out.
 */
object News {
    /** [regional] = from the search for your own town (counts a bit more). */
    data class Item(val title: String, val description: String, val published: Long?, val regional: Boolean = false)

    /** Domestic-only feeds (no foreign news), matched to the greeting language. */
    fun feedsFor(dutch: Boolean): List<String> =
        if (dutch) listOf("https://feeds.nos.nl/nosnieuwsbinnenland")
        else listOf("https://nltimes.nl/rssfeed2")

    /** Regional: a news search for your town (Dutch only - the results are Dutch articles). */
    fun regionalFeed(place: String): String? =
        place.trim().takeIf { it.length in 2..40 }?.let {
            "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode("\"$it\"", "UTF-8")}+when:1d&hl=nl&gl=NL&ceid=NL:nl"
        }

    // ------------------------------------------------------------------ parsing (unit tested)

    fun parse(xml: String): List<Item> {
        val out = mutableListOf<Item>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        var inItem = false
        var tag = ""
        var title = StringBuilder(); var desc = StringBuilder(); var date = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && out.size < 60) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = StringBuilder(); desc = StringBuilder(); date = StringBuilder() }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) when (tag) {
                    "title" -> title.append(p.text)
                    "description" -> desc.append(p.text)
                    "pubDate" -> date.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val t = clean(title.toString())
                        if (t.isNotBlank()) out += Item(t, clean(stripHtml(desc.toString())).take(400), parseDate(date.toString().trim()))
                    }
                    tag = ""
                }
            }
            ev = p.nextToken()
        }
        return out
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    /** Untrusted text: one line, no markup, sane length; drops Google's " - Source name" suffix. */
    fun clean(s: String): String = GreetingText.cleanMeta(s.replace(Regex("""\s+-\s+[^-]{2,40}$"""), ""))

    private val DATE_FORMATS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMMM yyyy - HH:mm")

    fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        for (f in DATE_FORMATS) {
            try {
                val fmt = SimpleDateFormat(f, Locale.ENGLISH)
                if (f.startsWith("dd MMMM")) fmt.timeZone = java.util.TimeZone.getTimeZone("Europe/Amsterdam")
                return fmt.parse(s)?.time
            } catch (_: Exception) {}
        }
        return null
    }

    // ------------------------------------------------------------------ the filter (unit tested)

    /** Things you need to know about now. Weight 10 = enough on its own; lower ones need company. */
    private val IMPORTANT = listOf(
        // Official warnings / emergencies
        "nl-alert" to 10, "code rood" to 10, "code oranje" to 10, "weeralarm" to 10, "noodtoestand" to 10,
        "noodverordening" to 9, "ramen en deuren" to 10, "evacuatie" to 9, "evacueren" to 9, "geëvacueerd" to 9,
        "code red" to 10, "code orange" to 10, "state of emergency" to 10, "evacuat" to 9, "keep windows and doors" to 10,
        // Disasters / attacks
        "aanslag" to 10, "terreur" to 10, "terroris" to 10, "explosie" to 8, "ontploffing" to 8, "aardbeving" to 9,
        "overstroming" to 10, "dijkdoorbraak" to 10, "gijzeling" to 10, "schietpartij" to 8, "zeer grote brand" to 9,
        "grote brand" to 7, "gifwolk" to 10, "giftige rook" to 9, "kernramp" to 10, "tornado" to 9, "orkaan" to 9, "zware storm" to 9,
        "terror" to 10, "explosion" to 8, "earthquake" to 9, "flood" to 9, "hostage" to 10, "shooting" to 8,
        "major fire" to 8, "toxic smoke" to 9, "severe storm" to 9, "hurricane" to 9,
        // Things that break your day: outages, transport, roads
        "landelijke storing" to 10, "grote storing" to 8, "stroomstoring" to 8, "storing 112" to 10, "112 storing" to 10,
        "112 niet bereikbaar" to 10, "geen treinen" to 9, "treinverkeer plat" to 10, "landelijke staking" to 9, "staking ov" to 8,
        "luchtruim gesloten" to 10, "schiphol dicht" to 10, "snelweg dicht" to 9, "snelweg afgesloten" to 9,
        "kettingbotsing" to 8, "tunnel dicht" to 7, "brug dicht" to 6,
        "power outage" to 8, "blackout" to 9, "nationwide outage" to 10, "no trains" to 9, "train traffic halted" to 10,
        "national strike" to 9, "airspace closed" to 10, "schiphol closed" to 10, "motorway closed" to 9, "highway closed" to 9, "pile-up" to 8,
        // Big national politics / society
        "kabinet gevallen" to 10, "kabinet is gevallen" to 10, "kabinet valt" to 9, "premier treedt af" to 10,
        "cabinet collapse" to 10, "cabinet has fallen" to 10, "government collapses" to 10, "government has fallen" to 10,
        // Makes a disruption more serious
        "urenlang" to 3, "langdurig" to 3, "de hele dag" to 3, "for hours" to 3, "all day" to 3,
        "lockdown" to 9, "pandemie" to 8, "pandemic" to 8, "oorlog" to 6, "mobilisatie" to 10, "war" to 5,
    )

    /** Soft news: any of these and it's out, no matter what else it says. */
    private val SOFT = listOf(
        "kat", "katten", "kitten", "poes", "hond", "honden", "puppy", "dier", "dieren", "eend", "zeehond", "uil", "vogel",
        "koe", "pony", "egel", "cat", "cats", "dog", "dogs", "animal", "animals", "pet", "rescued",
        "voetbal", "eredivisie", "ajax", "psv", "feyenoord", "f1", "formule 1", "verstappen", "wielren", "schaats",
        "tennis", "darts", "olymp", "songfestival", "eurovision", "football", "soccer", "match", "champions league",
        "bn'er", "celebrity", "influencer", "realityster", "tv-programma", "film", "serie", "concert", "festival", "album",
        "recept", "recipe", "horoscoop", "horoscope", "loterij", "lotto", "lottery", "quiz", "column", "opinie", "opinion",
        "review", "podcast", "tips", "wekdienst", "puzzel", "veiling", "auction", "record", "jubileum", "verjaardag", "birthday",
        "sinterklaas", "kerstpakket", "mode", "fashion", "royalty", "prinses", "koningin draagt",
    )

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}'-]+""")).filter { it.isNotBlank() }.toSet()

    /** How urgent this story is (0 = not at all). Soft topics always score 0. */
    fun score(item: Item): Int {
        val text = (item.title + " " + item.description).lowercase()
        val ws = words(text)
        if (SOFT.any { s -> if (' ' in s) text.contains(s) else s in ws }) return 0
        // Whole words / phrases only ("war" must not match "warm" or "waarschuwing").
        var total = IMPORTANT.filter { (k, _) -> if (' ' in k || '-' in k) text.contains(k) else k in ws || ws.any { w -> k.length >= 6 && w.startsWith(k) } }
            .sumOf { it.second }
        // A motorway closure ("A12 dicht", "A4 closed"): worth 7 - needs to be lasting or local to pass.
        if (ws.any { MOTORWAY.matches(it) } && ws.any { it in CLOSED }) total += 7
        if (item.regional) total += 3
        return total
    }

    private val MOTORWAY = Regex("""a\d{1,3}""")
    private val CLOSED = setOf("dicht", "afgesloten", "closed", "blocked", "versperd")

    const val THRESHOLD = 10
    const val MAX_AGE_MS = 3 * 60 * 60 * 1000L

    /** True when two headlines are about the same story (live blogs post many versions). */
    fun sameStory(a: String, b: String): Boolean {
        val wa = words(a).filter { it.length > 3 }.toSet()
        val wb = words(b).filter { it.length > 3 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return a.equals(b, ignoreCase = true)
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size) >= 0.6
    }

    /**
     * The one story worth interrupting your music for, or null. Recent, urgent, not soft, and not
     * something you've already been told.
     */
    fun pick(items: List<Item>, now: Long, alreadyTold: List<String>): Item? =
        items.asSequence()
            .filter { it.published == null || now - it.published in 0..MAX_AGE_MS }
            .filter { it.published != null }  // undated = can't prove it's fresh -> skip
            .map { it to score(it) }
            .filter { it.second >= THRESHOLD }
            .filter { (i, _) -> alreadyTold.none { sameStory(it, i.title) } }
            .sortedByDescending { it.second }
            .firstOrNull()?.first

    // ------------------------------------------------------------------ network

    /** National feed(s) for the language, plus your town's news (Dutch only), marked as regional. */
    fun fetchAll(dutch: Boolean, place: String, onError: (String?) -> Unit): List<Item> {
        val out = mutableListOf<Item>()
        for (url in feedsFor(dutch)) {
            try { out += fetch(url) } catch (e: Exception) { onError(e.message) }
        }
        if (dutch) regionalFeed(place)?.let { url ->
            try { out += fetch(url).map { it.copy(regional = true) } } catch (e: Exception) { onError(e.message) }
        }
        return out
    }

    /** Fetches a feed (HTTPS, short timeouts, size-capped). Throws on failure. */
    fun fetch(url: String): List<Item> {
        require(url.startsWith("https://")) { "news must use https" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DriveMusic")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("news feed returned HTTP ${conn.responseCode}")
            val xml = conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192); val sb = StringBuilder()
                while (sb.length < 1_500_000) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
                sb.toString()
            }
            return parse(xml)
        } finally {
            conn.disconnect()
        }
    }
}
