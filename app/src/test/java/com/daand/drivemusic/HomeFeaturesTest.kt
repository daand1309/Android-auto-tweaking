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

    private fun f(id: String) = HomeFeatures.all.first { it.id == id }

    @Test fun searchOpensTheSection() {
        assertEquals("Your car", HomeFeatures.sectionFor("fuel price", f("car_data")))
        assertEquals("Battery", HomeFeatures.sectionFor("accu", f("car_data")))
        assertEquals("Adapter", HomeFeatures.sectionFor("Bluetooth", f("car_data")))
        assertEquals("Service has its own page now", null, HomeFeatures.sectionFor("onderhoud", f("service")))
        assertEquals("Delivery", HomeFeatures.sectionFor("pitch", f("voice")))
        assertEquals(null, HomeFeatures.sectionFor("trips", f("trips")))
        assertEquals(null, HomeFeatures.sectionFor("", f("car_data")))
        assertEquals("a single letter isn't a section", null, HomeFeatures.sectionFor("a", f("car_data")))
    }
}
