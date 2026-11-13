package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)  // org.json
class RainForecastTest {
    /** An Open-Meteo answer at 10:05 with a 15-minute forecast. */
    private fun json(precipNow: Double, quarterHours: List<Double>?) = """
        {"current":{"time":"2026-11-13T10:05","temperature_2m":8.0,"weather_code":${if (precipNow > 0) 61 else 3},
          "wind_speed_10m":12,"wind_gusts_10m":20,"precipitation":$precipNow,"snowfall":0,"visibility":20000,"is_day":1},
         "daily":{"temperature_2m_max":[10],"temperature_2m_min":[5],"precipitation_probability_max":[60],"sunset":["2026-11-13T16:45"]}
         ${quarterHours?.let { q -> ""","minutely_15":{"time":[${q.indices.joinToString(",") { "\"2026-11-13T%02d:%02d\"".format(10 + (it + 1) * 15 / 60, (it + 1) * 15 % 60) }}],
           "precipitation":[${q.joinToString(",")}]}""" } ?: ""}}
    """.trimIndent()

    @Test fun rainOnItsWayAndEasingOffComeFromTheShortForecast() {
        val dryNow = Weather.parse(json(0.0, listOf(0.0, 0.0, 0.6, 1.2, 0.8, 0.0)))   // rain from 10:45
        assertEquals(40, dryNow.rainInMin); assertNull(dryNow.dryInMin)
        val wetNow = Weather.parse(json(1.0, listOf(0.9, 0.4, 0.0, 0.0, 0.0, 0.0)))   // dry from 10:45
        assertEquals(40, wetNow.dryInMin); assertNull(wetNow.rainInMin)
        // No forecast in the answer, or a broken one: no guess.
        assertNull(Weather.parse(json(0.0, null)).rainInMin)
        assertNull(Weather.parse(json(0.0, listOf(Double.NaN, -5.0, 999.0))).rainInMin)
    }

    @Test fun theButlerSaysItOnceBeforeItRains() {
        val dry = Weather.parse(json(0.0, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val coming = Weather.parse(json(0.0, listOf(0.0, 0.5, 0.9, 0.0, 0.0, 0.0)))
        val lines = DriveUpdates.changes(dry, coming, dutch = false, butler = true)
        assertTrue(lines.joinToString(), lines.any { it.contains("rain is on its way") })
        assertTrue("not again at the next check", DriveUpdates.changes(coming, coming.copy(rainInMin = 10), false, true).none { it.contains("on its way") })
        // The first look of a drive (no greeting weather to compare with) still warns.
        assertTrue(DriveUpdates.forecastLines(null, coming, dutch = true, butler = false).single().contains("regen aan"))
        // Raining, easing off soon.
        val wet = Weather.parse(json(1.0, listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)))
        val easing = Weather.parse(json(1.0, listOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.0)))
        assertTrue(DriveUpdates.changes(wet, easing, false).any { it.contains("ease off in about") })
    }
}
