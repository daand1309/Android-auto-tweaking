package com.daand.drivemusic

import com.daand.drivemusic.FuelPrices.Fuel
import com.daand.drivemusic.FuelPrices.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelStopsTest {
    // Around Amersfoort: 0.01° latitude ≈ 1.1 km.
    private val lat = 52.155; private val lon = 5.387
    private fun st(name: String, dLat: Double, price: Double?, fuel: Fuel = Fuel.EURO95) =
        Station(name, "Town", lat + dLat, lon, if (price == null) emptyMap() else mapOf(fuel to price))

    @Test fun cheapestAndNearestGoodAreDifferentStations() {
        val stations = listOf(
            st("Corner (pricey)", 0.005, 2.199),     // ~0.6 km, 30 cents above the cheapest
            st("Near and decent", 0.02, 1.929),       // ~2.2 km, 3 cents above the cheapest
            st("Cheapest", 0.06, 1.899),              // ~6.7 km
            st("Too far", 0.2, 1.699),                // ~22 km: outside 15 km
        )
        val c = FuelStops.choose(stations, lat, lon, Fuel.EURO95)!!
        assertEquals("Cheapest", c.cheapest.station.name)
        assertEquals("Near and decent", c.nearestGood.station.name)
        assertEquals(1.899, c.cheapest.price, 0.0001)
        assertEquals(6.7, c.cheapest.km, 0.2)
    }

    @Test fun whenTheNearestIsAlsoTheCheapestThereIsOneChoice() {
        val c = FuelStops.choose(listOf(st("Here", 0.01, 1.85), st("There", 0.05, 1.95)), lat, lon, Fuel.EURO95)!!
        assertEquals(c.cheapest, c.nearestGood)
    }

    @Test fun onlyStationsSellingYourFuelCount() {
        val stations = listOf(st("Diesel only", 0.01, 1.70, Fuel.DIESEL), st("Petrol", 0.03, 1.95))
        assertEquals("Petrol", FuelStops.choose(stations, lat, lon, Fuel.EURO95)!!.cheapest.station.name)
    }

    @Test fun nothingInRangeIsNull() {
        assertNull(FuelStops.choose(listOf(st("Far", 0.3, 1.80)), lat, lon, Fuel.EURO95))
        assertNull(FuelStops.choose(emptyList(), lat, lon, Fuel.EURO95))
    }

    @Test fun navigationGoesToTheStationsPosition() {
        // Waze first (the owner's navigation), then Google Maps.
        assertEquals("waze://?ll=52.175,5.387&navigate=yes", FuelStops.wazeUri(52.175, 5.387))
        assertEquals("google.navigation:q=52.175,5.387", FuelStops.navUri(52.175, 5.387))
    }

    @Test fun motorwayStationsAreRecognised() {
        // Real ANWB entries (around Utrecht): the motorway ones carry the A-road in title and address.
        assertTrue(FuelStops.onMotorway("AVIA de Knoest (A27 oz)", "Rijksweg A27 Oostzijde"))
        assertTrue(FuelStops.onMotorway("Shell Haarrijn (A2 wz)", "Rijksweg A2 Westzijde"))
        assertTrue(FuelStops.onMotorway("Some station", "Rijksweg A12"))
        // Provincial roads and town addresses are not motorways.
        assertFalse(FuelStops.onMotorway("Shell (N210 zz)", "Weg der Verenigde Naties 10 (N210 zz)"))
        assertFalse(FuelStops.onMotorway("Haan Kanaleneiland", "Beneluxlaan 1001"))
        assertFalse(FuelStops.onMotorway("Pin&GO!", "Ambachtstraat 8a"))
        assertFalse("a house number is not a road", FuelStops.onMotorway("Esso", "Atoomweg 40"))
    }

    @Test fun motorwayStationsAreNeverChosen() {
        // The motorway one is the nearest and (unusually) the cheapest here - still not offered.
        val stations = listOf(
            Station("Shell (A27 wz)", "Town", lat + 0.005, lon, mapOf(Fuel.EURO95 to 1.799), street = "Rijksweg A27 Westzijde"),
            st("In town", 0.03, 1.899),
        )
        val c = FuelStops.choose(stations, lat, lon, Fuel.EURO95)!!
        assertEquals("In town", c.cheapest.station.name)
        assertEquals("In town", c.nearestGood.station.name)
    }

    @Test fun androidAutoGetsThePlaceToNavigateTo() {
        // CarContext.ACTION_NAVIGATE: Android Auto opens your navigation app (Waze) on the car screen.
        assertEquals("geo:52.175,5.387", FuelStops.carNavUri(52.175, 5.387))
    }
}
