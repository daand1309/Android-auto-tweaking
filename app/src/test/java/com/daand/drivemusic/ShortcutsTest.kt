package com.daand.drivemusic

import com.daand.drivemusic.Shortcuts.Favourite
import com.daand.drivemusic.Shortcuts.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    @Test fun placesSurviveSaving() {
        val p = Place("Kerkstraat 1|Noordwijk", 52.24, 4.44)
        val back = Shortcuts.decodePlace(p.encode())!!
        assertEquals("Kerkstraat 1/Noordwijk", back.name)
        assertEquals(52.24, back.lat, 1e-5)
        assertNull(Shortcuts.decodePlace(""))
        assertNull(Shortcuts.decodePlace("Home|abc|4.4"))
        assertNull("0,0 is 'no fix', not a place", Shortcuts.decodePlace("Home|0.0|0.0"))
        assertNull(Shortcuts.decodePlace("Home|95|4"))
    }

    @Test fun favouritesKeepAtMostThreeAndNoDuplicates() {
        var l = emptyList<Favourite>()
        for (n in listOf("06 1111 1111", "06 2222 2222", "+31 6 3333 3333", "06 4444 4444")) l = Shortcuts.addFavourite(l, Favourite("x$n", n))
        assertEquals(3, l.size)
        assertEquals("the oldest dropped off", "06 2222 2222", l.first().number)
        l = Shortcuts.addFavourite(l, Favourite("Mum", "0622222222"))
        assertEquals("same number, other spacing: replaced", 3, l.size)
        assertEquals("Mum", l.last().name)
        assertEquals(l, Shortcuts.decodeFavourites(Shortcuts.encodeFavourites(l)))
    }

    @Test fun onlyRealNumbersAreKept() {
        assertTrue(Shortcuts.validNumber("+31 (6) 1234-5678"))
        assertFalse(Shortcuts.validNumber("12"))
        assertFalse(Shortcuts.validNumber("*#06#"))
        assertEquals(listOf(Favourite("Anna", "0612345678")), Shortcuts.decodeFavourites("Anna|0612345678;Bad|abc;|"))
        assertEquals("tel:+31612345678", Shortcuts.telUri("+31 (6) 1234-5678"))
    }
}
