package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeaturesTest {
    @Test fun gridOrderIsTheDesigned() {
        val g = HomeFeatures.byGroup()
        assertEquals(listOf("music", "greeting", "voice", "drive_mode"), g[FeatureGroup.MUSIC]!!.map { it.id })
        assertEquals(listOf("car_data", "trips", "service", "car_screen"), g[FeatureGroup.CAR]!!.map { it.id })
        assertEquals(listOf("themes", "setup", "updates", "backup", "logs", "help"), g[FeatureGroup.APP]!!.map { it.id })
    }

    @Test fun idsAreUnique() {
        assertEquals(HomeFeatures.all.size, HomeFeatures.all.map { it.id }.toSet().size)
    }

    @Test fun searchFindsByOtherWords() {
        assertEquals("voice", HomeFeatures.search("pitch").first().id)
        assertEquals("voice", HomeFeatures.search("PiTcH").first().id)
        assertEquals("car_data", HomeFeatures.search("brandstof").first().id)
        assertEquals("car_data", HomeFeatures.search("fuel price").first().id)
        assertEquals("themes", HomeFeatures.search("thema").first().id)
    }

    @Test fun searchFindsByTitle() {
        assertEquals("trips", HomeFeatures.search("trips").first().id)
    }

    @Test fun emptyOrJunkQuery() {
        assertTrue(HomeFeatures.search("").isEmpty())
        assertTrue(HomeFeatures.search("   ").isEmpty())
        assertTrue(HomeFeatures.search("zzz").isEmpty())
    }
}
