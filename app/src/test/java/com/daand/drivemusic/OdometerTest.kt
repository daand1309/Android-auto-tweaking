package com.daand.drivemusic

import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerTest {
    private fun trip(at: Long, km: Double) = TripRecord(at, 20, km, 1.0, 90, 90)

    @Test fun theDashboardReadingPlusTheDrivesSince() {
        // 18 Nov 2026: ODO 168815 on the dashboard; two drives after it, one before (already in the reading).
        val synced = 1_000_000L
        val trips = listOf(trip(synced - 10, 30.0), trip(synced + 10, 14.8), trip(synced + 20, 13.4))
        assertEquals(168_843, TripHistory.odometer(trips, 168_815, synced))
        assertNull("never typed in", TripHistory.odometer(trips, 0, 0))
    }
}
