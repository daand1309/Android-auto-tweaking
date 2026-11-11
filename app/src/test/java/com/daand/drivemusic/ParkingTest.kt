package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkingTest {
    @Test fun spotRoundTripsExactly() {
        val s = Parking.Spot(52.0907345, 5.1214201, 8.5f, 1_762_870_000_000L)
        assertEquals(s, Parking.Spot.decode(s.encode()))
    }

    @Test fun badOrEmptySpotIsIgnored() {
        assertNull(Parking.Spot.decode(""))
        assertNull(Parking.Spot.decode("1,2,3"))
        assertNull(Parking.Spot.decode("a,b,c,d"))
    }
}
