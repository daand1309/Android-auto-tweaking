package com.daand.drivemusic

import com.daand.drivemusic.obd.Trip
import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scores made with the old (soft) rules must never be mixed with today's. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class ScoreVersionTest {
    private fun rec(score: Int, sv: Int = Trip.SCORE_VERSION) = TripRecord(1L, 20, 15.0, 1.2, score, 90, scoreVersion = sv)

    @Test fun theVersionSurvivesSaving() {
        val back = TripHistory.fromJson(TripHistory.toJson(listOf(rec(88), rec(100, sv = 1))))
        assertEquals(listOf(Trip.SCORE_VERSION, 1), back.map { it.scoreVersion })
    }

    @Test fun tripsSavedBeforeVersionsExistAreVersion1() {
        val old = """[{"at":1,"min":20,"km":15.0,"l":1.2,"score":100,"maxC":90,"codes":"","p":2.0}]"""
        assertEquals(1, TripHistory.fromJson(old).single().scoreVersion)
    }

    @Test fun theAverageOnlyUsesTodaysRuler() {
        assertEquals(85, TripHistory.averageScore(listOf(rec(100, sv = 1), rec(100, sv = 1), rec(80), rec(90))))
        assertNull(TripHistory.averageScore(listOf(rec(100, sv = 1))))
    }
}
