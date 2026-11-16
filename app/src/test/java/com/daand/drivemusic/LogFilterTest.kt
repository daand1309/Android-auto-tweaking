package com.daand.drivemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real lines from a drive log: the "Problems only" view has to keep what went wrong and drop the
 * noise, and it has to work for the words the app actually writes.
 */
class LogFilterTest {

    @Test fun keepsWhatActuallyWentWrong() {
        listOf(
            "OBD: adapter reminder NOT shown - Android didn't keep it",
            "GREETING: timed out - continuing",
            "GREETING: nothing to hear after 12 s - continuing (network voice without signal?)",
            "OBD: connection failed (IOException: adapter didn't answer 'ATH0')",
            "OBD: gave up for this drive",
            "OBD: couldn't save the trip (IOException)",
            "FUEL: lookup failed (UnknownHostException: no address)",
            "FUEL: no position yet - keeping the manual price",
            "YouTube Music: PAUSED -> ERROR error='Upgrade naar Music Premium om deze functie te gebruiken'",
            "YouTube Music session destroyed (app closed or killed by the system?)",
            "!!! PAUSE #1 while driving. Clues: no clues (pause button / steering wheel / Hey Google?)",
            "OBD: alert WEAK_CHARGING (12.377)",
            "OBD: early warning CHARGE_VOLTS (now 12.9, usually 13.8)",
            "CRASH in obd: java.lang.InterruptedException",
            "GREETING: weather unavailable (weather service returned HTTP 503)",
            "YouTube Music: STOPPED -> ERROR error='Upgrade naar Music Premium om deze functie te gebruiken'",
        ).forEach { assertTrue("should be shown as a problem: $it", LogFilter.isProblem(it)) }
    }

    @Test fun leavesNormalDrivingOut() {
        listOf(
            "YouTube Music: PAUSED -> PLAYING",
            "YouTube Music: ERROR -> PAUSED",
            "START: success - YouTube Music is playing",
            "GREETING: spoken",
            "BRIEFING: shown on the car screen",
            "OBD: health this drive - charging 13.7 V",
            "OBD: alert READY_TO_DRIVE (25.0)",
            "OBD: alert ENGINE_WARM (75.0)",
            "OBD: trip - 44.0 km · 8.6 L/100km",
            "PARKING: spot saved (±9 m, fix from when the car stopped)",
            "UPDATE: download verified (checksum + signature OK)",
            "Drive Music 1.177 started",
        ).forEach { assertFalse("should not be flagged: $it", LogFilter.isProblem(it)) }
    }

    @Test fun problemsOnlyStillShowsTheDriveBoundaries() {
        val driveEnd = "=== CAR: drive ended (ignition is off) after 47 min. Pauses: 2, auto-resumes: 3 ==="
        assertTrue("context is needed when reading a problem", LogFilter.keep(driveEnd, problemsOnly = true))
        assertFalse(LogFilter.keep("GREETING: spoken", problemsOnly = true))
        assertTrue("everything when the filter is off", LogFilter.keep("GREETING: spoken", problemsOnly = false))
    }
}
