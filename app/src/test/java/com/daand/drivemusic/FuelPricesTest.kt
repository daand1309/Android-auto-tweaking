package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuel prices from the ANWB station database: parsing, choosing a price, and what costs use. */
class FuelPricesTest {

    /** A trimmed real answer (Utrecht, August 2026): two stations, one without LPG. */
    private val payload = """
    {"value":[
      {"id":"appitup_3551","title":"Esso Cartesiusweg","coordinates":{"latitude":52.10008,"longitude":5.08617},
       "address":{"city":"Utrecht"},
       "prices":[{"fuelType":"EURO95","value":2.079},{"fuelType":"DIESEL","value":2.239},{"fuelType":"EURO98","value":2.609}]},
      {"id":"appitup_9","title":"Tango Laterweg","coordinates":{"latitude":52.0940,"longitude":5.0900},
       "address":{"city":"Utrecht"},
       "prices":[{"fuelType":"EURO95","value":2.019},{"fuelType":"AUTOGAS","value":1.089}]}
    ]}
    """.trimIndent()

    private fun quote() = FuelPrices.quote(FuelPrices.parseAll(payload), 52.10, 5.09, FuelPrices.Fuel.EURO95)

    @Test fun readsTheStationsAndTheirPrices() {
        val s = FuelPrices.parseAll(payload)
        assertEquals(2, s.size)
        assertEquals("Esso Cartesiusweg", s[0].name)
        assertEquals("Utrecht", s[0].place)
        assertEquals(2.079, s[0].prices[FuelPrices.Fuel.EURO95]!!, 0.0001)
        assertEquals(2.239, s[0].prices[FuelPrices.Fuel.DIESEL]!!, 0.0001)
        assertNull("this station doesn't sell LPG", s[0].prices[FuelPrices.Fuel.LPG])
        assertEquals(1.089, s[1].prices[FuelPrices.Fuel.LPG]!!, 0.0001)
    }

    @Test fun handlesAnswersThatMakeNoSense() {
        assertTrue(FuelPrices.parseAll("").isEmpty())
        assertTrue(FuelPrices.parseAll("<html>not json</html>").isEmpty())
        assertTrue(FuelPrices.parseAll("{}").isEmpty())
        assertTrue(FuelPrices.parseAll("""{"value":[{"title":"no coordinates","prices":[]}]}""").isEmpty())
        // A station with no believable price is dropped entirely (it can't be quoted).
        assertTrue(FuelPrices.parseAll("""{"value":[{"title":"x","coordinates":{"latitude":52.0,"longitude":5.0},
            "prices":[{"fuelType":"EURO95","value":0}]}]}""").isEmpty())
    }

    @Test fun quotesTheMedianOfWhatIsAroundYou() {
        val q = quote()!!
        assertEquals(2.049, q.euro, 0.0001)          // (2.079 + 2.019) / 2
        assertEquals(2, q.stations)
        assertEquals("Esso Cartesiusweg", q.nearestName)   // ~0.26 km
        assertEquals(0.3, q.nearestKm, 0.1)
        assertEquals(FuelPrices.Fuel.EURO95, q.fuel)
    }

    @Test fun oneOddCheapStationDoesNotDecideThePrice() {
        val stations = (1..5).map { i ->
            FuelPrices.Station("S$i", "Utrecht", 52.10 + i * 0.001, 5.09,
                mapOf(FuelPrices.Fuel.EURO95 to 2.20))
        } + FuelPrices.Station("Cheap motorway", "Utrecht", 52.101, 5.091,
            mapOf(FuelPrices.Fuel.EURO95 to 1.60))
        val q = FuelPrices.quote(stations, 52.10, 5.09, FuelPrices.Fuel.EURO95)!!
        assertEquals("a median ignores the one outlier", 2.20, q.euro, 0.0001)
        assertEquals(6, q.stations)
    }

    @Test fun ignoresStationsTooFarAway() {
        val far = listOf(FuelPrices.Station("Far", "Eindhoven", 51.44, 5.47,
            mapOf(FuelPrices.Fuel.EURO95 to 2.00)))
        assertNull("110 km away is not your area", FuelPrices.quote(far, 52.10, 5.09, FuelPrices.Fuel.EURO95))
    }

    @Test fun aFuelOnlyOneStationSellsStillGivesItsPrice() {
        // Diesel is sold by one of the two stations: the quote is that price, marked as one station.
        val q = FuelPrices.quote(FuelPrices.parseAll(payload), 52.10, 5.09, FuelPrices.Fuel.DIESEL)!!
        assertEquals(2.239, q.euro, 0.0001)
        assertEquals(1, q.stations)
        assertEquals("Esso Cartesiusweg", q.nearestName)
    }

    @Test fun noPriceWhenNoStationSellsThatFuel() {
        assertNull(FuelPrices.quote(listOf(FuelPrices.Station("A", "Utrecht", 52.10, 5.09, emptyMap())),
            52.10, 5.09, FuelPrices.Fuel.EURO95))
    }

    @Test fun theRequestNeverCarriesAPrecisePosition() {
        val url = FuelPrices.urlFor(52.10008, 5.08617)
        assertTrue(url.startsWith("https://api.anwb.nl/"))
        assertTrue(url.contains("type-filter=FUEL_STATION"))
        assertTrue("rounded to 2 decimals (~1 km)", url.contains("bounding-box-filter=51.95,4.94,52.25,5.24"))
        assertFalse("the exact fix must not be in the request", url.contains("52.10008"))
    }

    @Test fun distancesAreRoughlyRight() {
        // Utrecht -> Eindhoven is about 75 km straight line.
        val d = FuelPrices.distanceKm(52.09, 5.12, 51.44, 5.47)
        assertTrue("got $d", d in 70.0..85.0)
        assertEquals(0.0, FuelPrices.distanceKm(52.0, 5.0, 52.0, 5.0), 0.0001)
    }

    @Test fun costsUseTheFetchedPriceOnlyWhileItIsFresh() {
        val now = 1_000_000_000_000L
        // Fresh and automatic: the fetched price wins over the typed-in one.
        assertEquals(2.05, FuelPrices.effectivePrice(true, 2.05, now - 60_000, 1.90, now), 0.0001)
        // Automatic but stale: back to what you typed in.
        assertEquals(1.90, FuelPrices.effectivePrice(true, 2.05, now - 8 * 24 * 3_600_000L, 1.90, now), 0.0001)
        // Automatic off: your own number, always.
        assertEquals(1.90, FuelPrices.effectivePrice(false, 2.05, now, 1.90, now), 0.0001)
        // Nothing fetched and nothing typed in: costs stay hidden.
        assertEquals(0.0, FuelPrices.effectivePrice(true, 0.0, 0, 0.0, now), 0.0001)
    }

    @Test fun fuelCodesAreTheOnesTheApiUses() {
        assertEquals(FuelPrices.Fuel.EURO95, FuelPrices.Fuel.of("EURO95"))
        assertEquals(FuelPrices.Fuel.DIESEL, FuelPrices.Fuel.of("DIESEL"))
        assertEquals("LPG is called AUTOGAS there", FuelPrices.Fuel.LPG, FuelPrices.Fuel.of("AUTOGAS"))
        assertEquals("unknown code falls back to petrol", FuelPrices.Fuel.EURO95, FuelPrices.Fuel.of("HYDROGEN"))
    }
}
