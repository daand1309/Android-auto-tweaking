package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)  // for the real org.json implementation
class GreetingTest {

    // Fixed to 15 June (an ordinary, non-holiday day) so tests don't change on real holidays.
    private fun at(hour: Int, day: Int = Calendar.WEDNESDAY): Calendar = Calendar.getInstance().apply {
        set(Calendar.MONTH, Calendar.JUNE); set(Calendar.DAY_OF_MONTH, 15)
        set(Calendar.DAY_OF_WEEK, day); set(Calendar.HOUR_OF_DAY, hour)
    }

    private fun onDate(month: Int, dayOfMonth: Int, hour: Int = 10): Calendar = Calendar.getInstance().apply {
        set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, dayOfMonth); set(Calendar.HOUR_OF_DAY, hour)
    }

    private val sunny = GreetingText.WeatherNow(tempNow = 18.4, code = 0, windKmh = 10.0, tempMin = 9.6, tempMax = 21.2, rainChance = 5)

    @Test fun greetsByNameWithTimeOfDay() {
        val t = GreetingText.build("Mr. Smith", false, at(8), null, "", Random(1))
        assertTrue(t, t.startsWith("Good morning, Mister Smith. Welcome back."))
        assertTrue(t, t.endsWith("Starting your music. Have a great drive!"))
    }

    @Test fun worksWithoutAName() {
        val t = GreetingText.build("", false, at(20), null, "", Random(1))
        assertTrue(t, t.startsWith("Good evening. Welcome back."))
    }

    @Test fun speaksDutch() {
        val t = GreetingText.build("meneer Jansen", true, at(14), null, "", Random(1))
        assertTrue(t, t.startsWith("Goedemiddag, meneer Jansen. Welkom terug."))
        assertTrue(t, t.contains("Goede reis"))
    }

    @Test fun addsTwoDifferentRandomLines() {
        val t = GreetingText.build("X", false, at(10), null, "", Random(7))
        val used = GreetingText.LINES_EN.filter { t.contains(it) }
        assertEquals(2, used.size)
    }

    @Test fun randomLinesVaryBetweenDrives() {
        val texts = (1..10).map { GreetingText.build("X", false, at(10), null, "", Random(it)) }.toSet()
        assertTrue("expected variety, got ${texts.size}", texts.size > 5)
    }

    @Test fun lateNightWarning() {
        assertTrue(GreetingText.build("X", false, at(1), null, "", Random(1)).contains("drive extra carefully"))
    }

    @Test fun includesWeather() {
        val t = GreetingText.build("X", false, at(10), sunny, "Utrecht", Random(1))
        assertTrue(t, t.contains("In Utrecht it's currently 18 degrees with clear skies."))
        assertTrue(t, t.contains("between 10 and 21 degrees, with a 5 percent chance of rain"))
        assertTrue(t, t.contains("Sunglasses on"))
    }

    @Test fun dutchWeatherAndIcyWarning() {
        val icy = sunny.copy(tempNow = -2.0, code = 71, rainChance = 80)
        val t = GreetingText.weatherSentence(icy, "Utrecht", true)
        assertTrue(t, t.contains("In Utrecht is het nu min 2 graden met sneeuw."))
        assertTrue(t, t.contains("80 procent kans op regen"))
        assertTrue(t, t.contains("glad"))
    }

    @Test fun parsesOpenMeteoResponse() {
        val json = """
            {"current":{"time":"2026-11-11T14:00","temperature_2m":11.3,"weather_code":61,"wind_speed_10m":22.5},
             "daily":{"time":["2026-11-11"],"temperature_2m_max":[12.8],"temperature_2m_min":[6.1],"precipitation_probability_max":[85]}}
        """.trimIndent()
        val w = Weather.parse(json)
        assertEquals(11.3, w.tempNow, 0.001)
        assertEquals(61, w.code)
        assertEquals(85, w.rainChance)
        assertTrue(GreetingText.weatherSentence(w, "", false).contains("light rain"))
    }

    // ---------------- road conditions

    private val clearDay = sunny.copy(visibilityM = 30_000.0, nowTime = "11:00", sunset = "17:05")

    @Test fun goodRoads() {
        assertEquals("Road conditions look good: dry with good visibility.", GreetingText.roadSentence(clearDay, false))
    }

    @Test fun blackIceWhenWetAndFreezing() {
        val t = GreetingText.roadSentence(clearDay.copy(tempNow = 0.5, code = 61, precipMm = 0.4), false)
        assertTrue(t, t.contains("black ice is possible"))
    }

    @Test fun snow() {
        val t = GreetingText.roadSentence(clearDay.copy(tempNow = -1.0, code = 73, snowfallCm = 0.6), false)
        assertTrue(t, t.contains("snow on the roads"))
    }

    @Test fun fogWithVisibility() {
        val t = GreetingText.roadSentence(clearDay.copy(code = 45, visibilityM = 180.0), false)
        assertTrue(t, t.contains("visibility is only 200 metres"))
    }

    @Test fun heavyRainAndGusts() {
        val t = GreetingText.roadSentence(clearDay.copy(code = 65, precipMm = 6.0, gustsKmh = 72.4), false)
        assertTrue(t, t.contains("aquaplaning"))
        assertTrue(t, t.contains("gusts up to 72 kilometres per hour"))
    }

    @Test fun lightsWhenDarkOrSunsetSoon() {
        assertTrue(GreetingText.roadSentence(clearDay.copy(isDay = false), false).contains("It's dark out, so lights on."))
        assertTrue(GreetingText.roadSentence(clearDay.copy(nowTime = "16:20"), false).contains("The sun sets at 5:05 PM"))
        assertFalse(GreetingText.roadSentence(clearDay, false).contains("sun sets"))
    }

    @Test fun dutchRoads() {
        val t = GreetingText.roadSentence(clearDay.copy(tempNow = 0.0, code = 61, precipMm = 1.0, isDay = false), true)
        assertTrue(t, t.startsWith("Wegcondities: er is kans op ijzel"))
        assertTrue(t, t.endsWith("Het is donker, dus lichten aan."))
    }

    @Test fun roadsReplaceGenericAdviceInGreeting() {
        val t = GreetingText.build("X", false, at(10), clearDay, "", Random(1), roads = true)
        assertTrue(t, t.contains("Road conditions look good"))
        assertFalse(t, t.contains("Sunglasses on"))  // no duplicate advice
    }

    @Test fun parsesRoadFieldsFromOpenMeteo() {
        val json = """
            {"current":{"time":"2026-11-11T16:30","temperature_2m":0.4,"weather_code":61,"wind_speed_10m":20,
                        "wind_gusts_10m":65.0,"precipitation":0.6,"snowfall":0.0,"visibility":800.0,"is_day":1},
             "daily":{"temperature_2m_max":[3.0],"temperature_2m_min":[-1.0],"precipitation_probability_max":[90],
                      "sunset":["2026-11-11T16:58"]}}
        """.trimIndent()
        val w = Weather.parse(json)
        assertEquals("16:30", w.nowTime)
        assertEquals("16:58", w.sunset)
        val t = GreetingText.roadSentence(w, false)
        assertTrue(t, t.contains("black ice"))
        assertTrue(t, t.contains("visibility is only 800 metres"))
        assertTrue(t, t.contains("gusts up to 65"))
        assertTrue(t, t.contains("sun sets at 4:58 PM"))
    }

    // ---------------- song

    @Test fun announcesSong() {
        val song = GreetingText.Song("Bohemian Rhapsody", "Queen")
        assertEquals("Up first: Bohemian Rhapsody by Queen.", GreetingText.songSentence(song, false, upFirst = true))
        assertEquals("Nu speelt: Bohemian Rhapsody van Queen.", GreetingText.songSentence(song, true, upFirst = false))
        assertEquals("Up first: Intro.", GreetingText.songSentence(GreetingText.Song("Intro", ""), false, true))
        val t = GreetingText.build("X", false, at(10), null, "", Random(1), song = song)
        assertTrue(t, t.endsWith("Up first: Bohemian Rhapsody by Queen. Starting your music. Have a great drive!"))
    }

    // ---------------- special occasions

    @Test fun birthdayGreeting() {
        val t = GreetingText.build("X", false, onDate(Calendar.APRIL, 27), null, "", Random(1), birthday = "04-27")
        assertTrue(t, t.contains("happy birthday"))
    }

    @Test fun birthdayOnlyOnTheRightDay() {
        val t = GreetingText.build("X", false, onDate(Calendar.APRIL, 28), null, "", Random(1), birthday = "04-27")
        assertFalse(t, t.contains("happy birthday"))
    }

    @Test fun holidayLines() {
        assertTrue(GreetingText.build("X", false, onDate(Calendar.DECEMBER, 25), null, "", Random(1)).contains("Merry Christmas"))
        assertTrue(GreetingText.build("X", true, onDate(Calendar.JANUARY, 1), null, "", Random(1)).contains("Gelukkig nieuwjaar"))
        assertTrue(GreetingText.build("X", false, onDate(Calendar.OCTOBER, 31), null, "", Random(1)).contains("Halloween"))
    }

    @Test fun occasionReplacesOneRandomLineNotBoth() {
        // On a holiday, one occasion line + one random line (still a full greeting).
        val t = GreetingText.build("X", false, onDate(Calendar.DECEMBER, 25), null, "", Random(3))
        val used = GreetingText.LINES_EN.filter { t.contains(it) }
        assertEquals(1, used.size)
    }

    @Test fun compactWeatherForTileAndCarScreen() {
        assertEquals("18° · clear skies · 5% rain", GreetingText.tileWeather(sunny, false))
        assertEquals("-2° · sneeuw · 80% regen", GreetingText.tileWeather(sunny.copy(tempNow = -2.0, code = 71, rainChance = 80), true))
        assertEquals("18° · heldere lucht · 5% regen", GreetingText.tileWeather(sunny, true))
    }

    @Test fun locationIsRoundedForPrivacy() {
        assertEquals(52.1, Weather.round(52.0907), 0.0001)
        assertTrue(Weather.url(52.0907, 5.1214).contains("latitude=52.1&longitude=5.1"))
        assertFalse(Weather.url(52.0907, 5.1214).contains("52.09"))
    }

    private fun weatherJson(temp: String, gust: String = "10") = """
        {"current":{"time":"2026-11-11T14:00","temperature_2m":$temp,"weather_code":3,"wind_gusts_10m":$gust},
         "daily":{"temperature_2m_max":[12.0],"temperature_2m_min":[2.0],"precipitation_probability_max":[250]}}
    """.trimIndent()

    @Test fun glitchyWeatherIsRejectedInsteadOfCrashingLater() {
        for (bad in listOf("\"NaN\"", "\"Infinity\"", "1e308", "-500")) {
            try { Weather.parse(weatherJson(bad)); fail("accepted temperature $bad") } catch (_: Exception) {}
        }
        val w = Weather.parse(weatherJson("4.4", gust = "\"NaN\""))
        assertEquals(0.0, w.gustsKmh, 0.0)   // bad optional value -> ignored
        assertEquals(100, w.rainChance)      // 250% clamped
        GreetingText.build("Daan", false, java.util.Calendar.getInstance(), w, "Noordwijk")  // must not throw
    }

    @Test fun songTitlesAreTreatedAsUntrusted() {
        assertEquals("Evil === CAR: fake ===", GreetingText.cleanMeta("Evil\n=== CAR: fake ==="))
        assertEquals("speak Boom /speak", GreetingText.cleanMeta("<speak>Boom</speak>"))
        assertEquals(150, GreetingText.cleanMeta("x".repeat(10_000)).length)
        assertEquals("Freedom", GreetingText.cleanMeta("  Freedom \t "))
    }
}
