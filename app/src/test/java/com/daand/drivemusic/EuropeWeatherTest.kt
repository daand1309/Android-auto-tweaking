package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)   // the real org.json
class EuropeWeatherTest {
    // ---- MET Norway (locationforecast 2.0 complete), shaped like a real answer for Utrecht
    private fun metStep(hour: Int, t: Double, sym1: String, mm: Double, extra: String = "") =
        """{"time":"2026-11-17T${"%02d".format(hour)}:00:00Z","data":{"instant":{"details":{"air_temperature":$t,"wind_speed":8.5,"fog_area_fraction":0.0$extra}},
        "next_1_hours":{"summary":{"symbol_code":"$sym1"},"details":{"precipitation_amount":$mm}}}}"""
    private val met = """{"properties":{"timeseries":[
        ${metStep(15, 11.7, "lightrain_day", 0.6)}, ${metStep(16, 11.0, "rain", 1.2)}, ${metStep(17, 10.2, "cloudy", 0.0)},
        ${metStep(18, 9.5, "fair_night", 0.0)}, ${metStep(19, 9.3, "clearsky_night", 0.0)}]}}"""

    @Test fun metNorwayNowAndTheHoursAhead() {
        val w = MetNorway.parse(met)!!
        assertEquals(11.7, w.tempNow, 0.01)
        assertEquals("light rain", 61, w.code)
        assertEquals(8.5 * 3.6, w.windKmh, 0.01)
        assertEquals(0.6, w.precipMm, 0.01)
        assertEquals(9.3, w.tempMin, 0.01)
        assertEquals(11.7, w.tempMax, 0.01)
        assertEquals("2 of the 5 hours have rain", 40, w.rainChance)
        assertTrue(w.isDay)
    }

    @Test fun metNorwaySymbols() {
        assertEquals(0, MetNorway.code("clearsky_day")); assertEquals(1, MetNorway.code("fair_night"))
        assertEquals(2, MetNorway.code("partlycloudy_day")); assertEquals(3, MetNorway.code("cloudy"))
        assertEquals(45, MetNorway.code("fog")); assertEquals(80, MetNorway.code("lightrainshowers_day"))
        assertEquals(65, MetNorway.code("heavyrain")); assertEquals(71, MetNorway.code("snow"))
        assertEquals(66, MetNorway.code("sleet")); assertEquals(95, MetNorway.code("rainandthunder"))
        assertFalse(MetNorway.isDay("clearsky_night", fallback = true))
    }

    @Test fun metNorwayAsksWithTheAppsContactAndARoundedPlace() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=52.2&lon=4.5", MetNorway.url(52.2361, 4.4637))
        assertTrue(MetNorway.USER_AGENT.startsWith("DriveMusic/") && "github.com" in MetNorway.USER_AGENT)
    }

    // ---- wttr.in (format=j1), shaped like a real answer
    private val wttr = """{"current_condition":[{"temp_C":"13","weatherCode":"356","windspeedKmph":"32","precipMM":"0.1","visibility":"7",
        "weatherDesc":[{"value":"Moderate or heavy rain shower"}]}],
      "weather":[{"date":"2026-11-17","mintempC":"3","maxtempC":"12","astronomy":[{"sunrise":"08:03 AM","sunset":"04:45 PM"}],
        "hourly":[{"time":"0","chanceofrain":"25","WindGustKmph":"40"},{"time":"1200","chanceofrain":"85","WindGustKmph":"55"}]}]}"""

    @Test fun wttrNowAndToday() {
        val w = Wttr.parse(wttr, nowMinutes = 15 * 60 + 18)!!
        assertEquals(13.0, w.tempNow, 0.01)
        assertEquals("heavy rain shower", 81, w.code)
        assertEquals(32.0, w.windKmh, 0.01)
        assertEquals(55.0, w.gustsKmh, 0.01)
        assertEquals(7000.0, w.visibilityM!!, 0.1)
        assertEquals(3.0, w.tempMin, 0.01); assertEquals(12.0, w.tempMax, 0.01)
        assertEquals(85, w.rainChance)
        assertEquals("16:45", w.sunset)
        assertTrue(w.isDay)
        assertFalse(Wttr.parse(wttr, nowMinutes = 18 * 60)!!.isDay)
    }

    @Test fun wttrCodes() {
        assertEquals(0, Wttr.code(113)); assertEquals(2, Wttr.code(116)); assertEquals(3, Wttr.code(122))
        assertEquals(45, Wttr.code(248)); assertEquals(51, Wttr.code(266)); assertEquals(63, Wttr.code(302))
        assertEquals(65, Wttr.code(308)); assertEquals(71, Wttr.code(338)); assertEquals(66, Wttr.code(311))
        assertEquals(95, Wttr.code(389))
    }

    @Test fun brokenAnswersAreNoWeather() {
        assertNull(MetNorway.parse("{}")); assertNull(MetNorway.parse("<html>"))
        assertNull(Wttr.parse("{}", 600)); assertNull(Wttr.parse("Unknown location", 600))
    }
}
