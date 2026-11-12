package com.daand.drivemusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * "The butler comes back later": during a drive, every so often check whether the weather / road
 * conditions changed in a way that matters, and whether there's breaking news you need to know.
 * Says nothing when nothing changed - silence is the default.
 */
object DriveUpdates {
    const val FIRST_CHECK_MS = 12 * 60 * 1000L
    const val INTERVAL_MS = 15 * 60 * 1000L
    const val MAX_NEWS_PER_DRIVE = 3
    const val MAX_FOLLOW_PER_DRIVE = 6

    /** Weather at the start of the drive (from the greeting) and after each check. */
    @Volatile var lastWeather: GreetingText.WeatherNow? = null
    @Volatile var newsThisDrive = 0
    @Volatile var followedThisDrive = 0

    fun newDrive() { lastWeather = null; newsThisDrive = 0; followedThisDrive = 0 }

    // ------------------------------------------------------------------ weather change (unit tested)

    private fun raining(w: GreetingText.WeatherNow) = w.precipMm >= 0.2 || w.code in 51..67 || w.code in 80..82
    private fun snowing(w: GreetingText.WeatherNow) = w.snowfallCm > 0.0 || w.code in 71..77 || w.code in 85..86
    private fun foggy(w: GreetingText.WeatherNow) = w.code == 45 || w.code == 48 || (w.visibilityM ?: 99_999.0) < 1000
    private fun storm(w: GreetingText.WeatherNow) = w.code in 95..99
    private fun icy(w: GreetingText.WeatherNow) = w.code in 56..57 || w.code in 66..67

    /** Sentences about what changed since [old] that matter for driving; empty if nothing did. */
    fun changes(old: GreetingText.WeatherNow, new: GreetingText.WeatherNow, dutch: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun say(nl: String, en: String) { out += if (dutch) nl else en }
        when {
            icy(new) && !icy(old) -> say("Er valt nu ijzel. Rij heel voorzichtig, de weg kan spiegelglad zijn.",
                "Freezing rain has started. Please take great care, the roads may be like glass.")
            snowing(new) && !snowing(old) -> say("Het is gaan sneeuwen. Houd extra afstand.",
                "It has started to snow. Do keep a little extra distance.")
            storm(new) && !storm(old) -> say("Er trekt onweer over, met kans op hevige buien.",
                "A thunderstorm is moving in, with heavy showers possible.")
            raining(new) && !raining(old) -> say("Het is gaan regenen, dus de weg wordt nat.",
                "It has started to rain, so the roads will be wet.")
            raining(old) && !raining(new) && !snowing(new) -> say("Goed nieuws: het is droog geworden.",
                "Good news: the rain has stopped.")
        }
        if (new.tempNow <= 1.0 && old.tempNow > 1.0 && !icy(new)) {
            say("Het is rond het vriespunt, dus pas op voor gladheid.", "It's now around freezing, so watch out for slippery patches.")
        }
        if (foggy(new) && !foggy(old)) say("Er hangt nu mist. Doe je mistlampen aan als het zicht slecht is.",
            "There's fog about now. Fog lights on if visibility drops.")
        else if (foggy(old) && !foggy(new)) say("De mist is opgetrokken.", "The fog has cleared.")
        if (new.gustsKmh >= 60 && old.gustsKmh < 60) {
            val g = SpeechText.number(Math.round(new.gustsKmh).toInt(), dutch)
            say("Er staan nu windstoten tot $g kilometer per uur. Houd het stuur stevig vast.",
                "Gusts are now reaching $g kilometres per hour. A firm grip on the wheel, if you would.")
        }
        return out
    }

    // ------------------------------------------------------------------ wording (unit tested)

    /** The polite question before an update ("I do apologise, sir, but there is an update..."). */
    fun askLine(dutch: Boolean, butler: Boolean, followedOnly: Boolean = false): String = when {
        butler && dutch && followedOnly -> "Neemt u mij niet kwalijk, meneer, maar er is nieuws over het verhaal dat u volgt. Zal ik het voorlezen?"
        butler && followedOnly -> "I do apologise, sir, but there is an update on the story you're following. Shall I go ahead?"
        butler && dutch -> "Neemt u mij niet kwalijk, meneer, maar er is een update. Zal ik hem voorlezen?"
        butler -> "I do apologise, sir, but there is an update. Shall I go ahead?"
        dutch -> "Ik heb een update voor je. Wil je hem horen?"
        else -> "I've got an update for you. Want to hear it?"
    }

    /** After the red button. */
    fun declinedLine(dutch: Boolean, butler: Boolean): String = when {
        butler && dutch -> "Uiteraard, meneer. Ik zwijg als het graf."
        butler -> "Very good, sir. I shall say no more."
        dutch -> "Prima, dan houd ik het voor me."
        else -> "Fine, I'll keep it to myself."
    }

    /**
     * The update itself. [withIntro] = false when it follows the question (then it starts with the
     * news straight away).
     */
    fun compose(weather: List<String>, news: News.Item?, dutch: Boolean, butler: Boolean, name: String, withIntro: Boolean = true,
                followed: News.Item? = null, source: String = ""): String? {
        if (weather.isEmpty() && news == null && followed == null) return null
        val who = SpeechText.spokenName(name, dutch)
        val intro = if (!withIntro) null else when {
            butler && dutch -> "Excuseert u mij${if (who.isNotEmpty()) ", $who" else ""}. Een korte update."
            butler -> "Pardon the interruption${if (who.isNotEmpty()) ", $who" else ""}. A brief update."
            dutch -> "Even een update."
            else -> "Quick update."
        }
        val parts = mutableListOf<String>()
        if (intro != null) parts += intro
        parts += weather
        if (news != null) parts += if (dutch) "Belangrijk nieuws: ${news.title.trimEnd('.')}." else "Some news you should know: ${news.title.trimEnd('.')}."
        if (followed != null) {
            val h = followed.title.trimEnd('.')
            parts += when {
                dutch && source.isNotBlank() -> "Over het verhaal dat je volgt, meldt $source: $h."
                dutch -> "Over het verhaal dat je volgt: $h."
                source.isNotBlank() -> "On the story you're following, $source reports: $h."
                else -> "On the story you're following: $h."
            }
        }
        if (butler) parts += if (dutch) "Dat was alles. Ik laat u weer verder genieten." else "That is all. I'll leave you to your music."
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------ the check (network: background thread)

    /** Looks for something worth saying now (network: background thread), or null. */
    fun check(ctx: Context): Found? {
        val p = Prefs(ctx)
        val dutch = p.greetingDutch
        var weatherLines = emptyList<String>()
        if (p.updatesWeather && p.weatherEnabled) {
            val (lat, lon) = whereAreWe(ctx) ?: (p.weatherLat to p.weatherLon)
            if (!lat.isNaN() && !lon.isNaN()) {
                try {
                    val now = Weather.fetch(Weather.round(lat), Weather.round(lon))
                    lastWeather?.let { weatherLines = changes(it, now, dutch) }
                    lastWeather = now
                    p.lastWeatherSummary = GreetingText.tileWeather(now, dutch)
                    p.lastWeatherAt = System.currentTimeMillis()
                } catch (e: Exception) {
                    DriveLog.log("UPDATE CHECK: weather unavailable (${e.message})")
                }
            }
        }
        var story: News.Item? = null
        if (p.updatesNews && newsThisDrive < MAX_NEWS_PER_DRIVE) {
            val items = mutableListOf<News.Item>()
            items += News.fetchAll(dutch, p.weatherPlace) { DriveLog.log("UPDATE CHECK: news feed unavailable ($it)") }
            story = News.pick(items, System.currentTimeMillis(), p.toldNews)
            DriveLog.log("UPDATE CHECK: ${items.size} headlines, ${if (story == null) "none urgent enough" else "1 worth telling"}")
        }
        var followed: News.Item? = null
        if (p.followActive && followedThisDrive < MAX_FOLLOW_PER_DRIVE) {
            val items = News.fetchFollow(dutch) { DriveLog.log("UPDATE CHECK: feed unavailable ($it)") }
            val told = p.toldNews + listOfNotNull(story?.title)
            followed = News.pickFollowed(items, News.keywords(p.followTopic), System.currentTimeMillis(), told)
            DriveLog.log("UPDATE CHECK: following '${p.followTopic}' - ${if (followed == null) "nothing new" else "new headline"}")
        }
        if (weatherLines.isEmpty() && story == null && followed == null) {
            DriveLog.log("UPDATE CHECK: nothing worth interrupting for"); return null
        }
        if (story != null) {
            p.toldNews = (p.toldNews + story.title).takeLast(100)  // also when you decline it: never asked twice
            newsThisDrive++
        }
        if (followed != null) {
            p.toldNews = (p.toldNews + followed.title).takeLast(100)
            followedThisDrive++
        }
        return Found(weatherLines, story, followed, followed?.source ?: "")
    }

    /** What a check found worth telling. */
    data class Found(val weather: List<String>, val news: News.Item?, val followed: News.Item? = null, val source: String = "") {
        /** Short title for the buttons / car pop-up. */
        fun title(dutch: Boolean) = when {
            followed != null && news == null && weather.isEmpty() -> if (dutch) "Verhaal dat je volgt" else "Story you follow"
            news != null && weather.isNotEmpty() -> if (dutch) "Weer en nieuws" else "Weather & news"
            news != null -> if (dutch) "Belangrijk nieuws" else "Breaking news"
            else -> if (dutch) "Weer en wegen" else "Weather & roads"
        }
    }

    /** Where the car is now (rounded later), if location may be read in the background. */
    private fun whereAreWe(ctx: Context): Pair<Double, Double>? {
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse || !bg) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val now = System.currentTimeMillis()
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < 30 * 60 * 1000L }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) { null }
    }
}
