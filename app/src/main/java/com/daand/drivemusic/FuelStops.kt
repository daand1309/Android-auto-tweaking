package com.daand.drivemusic

import com.daand.drivemusic.FuelPrices.Fuel
import com.daand.drivemusic.FuelPrices.Station

/**
 * Drive Mode's "Find fuel": from the stations around you (the ANWB list the fuel price already uses),
 * the cheapest one in range and the nearest one with a good price - within [GOOD_CENTS] a litre of
 * that cheapest. Motorway stations are left out ([onMotorway]). Distances are straight-line from your position, worked out on the phone only.
 * Pure, unit tested.
 */
object FuelStops {
    const val RADIUS_KM = 15.0
    /** "A good price": at most this much above the cheapest around you (€ per litre). */
    const val GOOD_CENTS = 0.05

    data class Stop(val station: Station, val price: Double, val km: Double)

    /**
     * A motorway station: often 20+ cents a litre dearer, and only reachable from that motorway in that
     * direction. The ANWB list has no flag for it, but the A-road is in the address ("Rijksweg A27
     * Oostzijde") and the name ("(A2 wz)"). Provincial N-roads are not motorways.
     */
    private val MOTORWAY = Regex("""(?<![\p{L}\p{N}])A\s?\d{1,3}(?![\p{L}\p{N}])""")
    fun onMotorway(name: String, street: String) = MOTORWAY.containsMatchIn(street) || MOTORWAY.containsMatchIn(name)
    data class Choice(val nearestGood: Stop, val cheapest: Stop)

    fun choose(stations: List<Station>, lat: Double, lon: Double, fuel: Fuel, radiusKm: Double = RADIUS_KM): Choice? {
        val stops = stations.filterNot { onMotorway(it.name, it.street) }.mapNotNull { s ->
            val p = s.prices[fuel] ?: return@mapNotNull null
            val km = FuelPrices.distanceKm(lat, lon, s.lat, s.lon)
            if (km > radiusKm) null else Stop(s, p, Math.round(km * 10) / 10.0)
        }
        if (stops.isEmpty()) return null
        val cheapest = stops.minWithOrNull(compareBy<Stop> { it.price }.thenBy { it.km })!!
        val nearestGood = stops.filter { it.price <= cheapest.price + GOOD_CENTS + 1e-9 }.minByOrNull { it.km }!!
        // Equal prices: the cheapest is already the nearer of them - one choice, not two.
        return if (nearestGood.price <= cheapest.price) Choice(cheapest, cheapest) else Choice(nearestGood, cheapest)
    }

    /** For Android Auto itself (CarContext.ACTION_NAVIGATE): it opens your chosen navigation app on the car screen. */
    fun carNavUri(lat: Double, lon: Double) = "geo:$lat,$lon"

    /** Waze turn-by-turn to a position (it shows on the Android Auto screen too). */
    fun wazeUri(lat: Double, lon: Double) = "waze://?ll=$lat,$lon&navigate=yes"

    /** Google Maps (and so Android Auto) turn-by-turn to a position - when Waze isn't installed. */
    fun navUri(lat: Double, lon: Double) = "google.navigation:q=$lat,$lon"
}
