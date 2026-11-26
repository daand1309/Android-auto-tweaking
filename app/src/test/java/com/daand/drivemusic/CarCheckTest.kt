package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)   // android.net.Uri
class CarCheckTest {
    @Test fun asksWhereCarConnectionAsks() {
        // Must match androidx.car.app's CarConnectionTypeLiveData, or Android Auto won't answer.
        assertEquals("content://androidx.car.app.connection", CarCheck.URI.toString())
        assertEquals("CarConnectionState", CarCheck.COLUMN)
    }

    @Test fun connectedMeansAndroidAutoOrAutomotive() {
        assertTrue(CarCheck.isConnected(androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION))
        assertTrue(CarCheck.isConnected(androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NATIVE))
        assertFalse(CarCheck.isConnected(androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED))
    }

    @Test fun keepsAskingForHalfAnHourAfterADrop() {
        // 26 Nov 12:26: dropped at the engine start, back within a minute or two but never announced.
        assertTrue(CarCheck.keepAsking(90_000))
        assertTrue(CarCheck.keepAsking(29 * 60_000L))
        assertFalse(CarCheck.keepAsking(CarCheck.FOR_MS))
    }
}
