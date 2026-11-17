package com.daand.drivemusic

import com.daand.drivemusic.FuelPrices.Fuel
import com.daand.drivemusic.FuelPrices.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
