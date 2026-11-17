package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)   // the real org.json
class BuienradarTest {
    /** 24 five-minute steps, like gpsgadget.buienradar.nl/data/raintext (value 0-255 -> mm/h). */
    private fun radar(vararg values: Int) = values.mapIndexed { i, v -> "%03d|%02d:%02d".format(v, 16, (i * 5) % 60) }.joinToString("\n")

    @Test fun rainArrivingIsMinutesAhead() {
        val dry = IntArray(24) { 0 }
        dry[4] = 110   // ~1 mm/h from the 5th step: 20 minutes from now
        assertEquals(20 to null, Buienradar.parseRain(radar(*dry)))
    }

    @Test fun rainStoppingIsMinutesAhead() {
        val wet = IntArray(24) { if (it < 3) 120 else 0 }
        assertEquals(null to 15, Buienradar.parseRain(radar(*wet)))
    }

    @Test fun nothingComingOrGarbageIsNoForecast() {
        assertEquals(null to null, Buienradar.parseRain(radar(*IntArray(24) { 0 })))
        assertEquals(null to null, Buienradar.parseRain("<html>error</html>"))
        assertEquals(null to null, Buienradar.parseRain(""))
    }

    @Test fun radarOnlyWhereItReaches() {
        assertTrue(Buienradar.covers(52.23, 4.45))     // Noordwijk
        assertTrue(Buienradar.covers(50.85, 4.35))     // Brussels
        assertFalse(Buienradar.covers(48.85, 2.35))    // Paris: abroad, no radar - not "dry"
    }

    @Test fun theRadarIsAskedForARoundedPlace() {
        // ~5 km steps: enough for a rain shower, not your street.
        assertEquals("https://gpsgadget.buienradar.nl/data/raintext?lat=52.25&lon=4.45", Buienradar.rainUrl(52.2361, 4.4637))
    }

    private val feed = """{"actual":{"sunrise":"2026-11-17T08:04:00","sunset":"2026-11-17T16:44:00","stationmeasurements":[
        {"stationname":"Meetstation Arnhem","lat":52.07,"lon":5.88,"weatherdescription":"Vrijwel onbewolkt (zonnig/helder)",
         "temperature":11.6,"visibility":39700.0,"windgusts":16.6,"windspeed":8.4,"precipitation":0.0},
        {"stationname":"Meetstation Voorschoten","lat":52.12,"lon":4.43,"weatherdescription":"Lichte regen",
         "temperature":9.2,"visibility":8000.0,"windgusts":12.0,"windspeed":6.0,"precipitation":0.4}]},
      "forecast":{"fivedayforecast":[
        {"day":"2026-11-16T00:00:00","mintemperatureMin":2,"mintemperatureMax":7,"maxtemperatureMin":9,"maxtemperatureMax":13,"rainChance":40},
        {"day":"2026-11-17T00:00:00","mintemperatureMin":3,"mintemperatureMax":5,"maxtemperatureMin":10,"maxtemperatureMax":12,"rainChance":80}]}}"""

    @Test fun theNearestStationAndTodaysForecast() {
        val w = Buienradar.parseFeed(feed, 52.23, 4.45, LocalDateTime.parse("2026-11-17T12:00:00"))!!
        assertEquals("Voorschoten is nearest to Noordwijk", 9.2, w.tempNow, 0.01)
        assertEquals("light rain", 61, w.code)
        assertEquals(6.0 * 3.6, w.windKmh, 0.01)
        assertEquals(12.0 * 3.6, w.gustsKmh, 0.01)
        assertEquals(0.4, w.precipMm, 0.01)
        assertEquals(8000.0, w.visibilityM!!, 0.1)
        assertEquals("today, not yesterday", 80, w.rainChance)
        assertEquals(4.0, w.tempMin, 0.01)
        assertEquals(11.0, w.tempMax, 0.01)
        assertTrue(w.isDay)
        assertEquals("16:44", w.sunset)
    }

    @Test fun afterSunsetIsDark() {
        assertFalse(Buienradar.parseFeed(feed, 52.23, 4.45, LocalDateTime.parse("2026-11-17T18:00:00"))!!.isDay)
    }

    @Test fun dutchDescriptionsBecomeWeatherCodes() {
        assertEquals(0, Buienradar.code("Vrijwel onbewolkt (zonnig/helder)"))
        assertEquals(3, Buienradar.code("Zwaar bewolkt"))
        assertEquals(45, Buienradar.code("Mist"))
        assertEquals(51, Buienradar.code("Motregen"))
        assertEquals(63, Buienradar.code("Zwaar bewolkt en regen"))
        assertEquals(95, Buienradar.code("Onweer"))
        assertEquals(71, Buienradar.code("Sneeuw"))
        assertEquals(66, Buienradar.code("IJzel"))
    }

    @Test fun aBrokenFeedIsNoWeather() {
        assertNull(Buienradar.parseFeed("{}", 52.2, 4.4, LocalDateTime.now()))
        assertNull(Buienradar.parseFeed("not json", 52.2, 4.4, LocalDateTime.now()))
    }
}
