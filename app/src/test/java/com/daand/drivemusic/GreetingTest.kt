package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)  // for the real org.json implementation
class GreetingTest {

    private fun at(hour: Int, day: Int = Calendar.WEDNESDAY): Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, day); set(Calendar.HOUR_OF_DAY, hour)
    }

    private val sunny = GreetingText.WeatherNow(tempNow = 18.4, code = 0, windKmh = 10.0, tempMin = 9.6, tempMax = 21.2, rainChance = 5)

    @Test fun greetsByNameWithTimeOfDay() {
        val t = GreetingText.build("Mr. Smith", false, at(8), null, "", Random(1))
        assertTrue(t, t.startsWith("Good morning, Mr. Smith. Welcome back."))
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
        assertTrue(t, t.contains("In Utrecht is het nu -2 graden met sneeuw."))
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

    @Test fun locationIsRoundedForPrivacy() {
        assertEquals(52.1, Weather.round(52.0907), 0.0001)
        assertTrue(Weather.url(52.0907, 5.1214).contains("latitude=52.1&longitude=5.1"))
        assertFalse(Weather.url(52.0907, 5.1214).contains("52.09"))
    }
}
