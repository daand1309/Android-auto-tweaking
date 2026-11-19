package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Shapes as the RDW's open data answered on 19 Nov 2026. */
@RunWith(RobolectricTestRunner::class)
class RdwTest {
    @Test fun plates() {
        assertEquals("AB123C", Rdw.plate("ab-123-c"))
        assertEquals("GZR90H", Rdw.plate(" gz r9 0h "))
        assertNull(Rdw.plate("AB-12"))
    }

    @Test fun apkDateFromTheRegister() {
        assertEquals("20290113", Rdw.parseApk("""[{"kenteken":"GZR90H","merk":"MITSUBISHI","handelsbenaming":"ASX","vervaldatum_apk":"20290113"}]"""))
        assertNull("plate not found", Rdw.parseApk("[]"))
        assertEquals(LocalDate.of(2029, 1, 13), Rdw.date("20290113"))
    }

    @Test fun onlyOpenRecallsCount() {
        val json = """[{"kenteken":"00BBL8","referentiecode_rdw":"MGP130086","code_status":"P","status":"Producent heeft herstel gemeld"},
                       {"kenteken":"00BBL8","referentiecode_rdw":"MGP140311","code_status":"O","status":"Open"}]"""
        assertEquals(listOf("MGP140311"), Rdw.parseOpen(json))
        val r = Rdw.parseRecall("""[{"referentiecode_rdw":"MGP070060","omschrijving_defect":"De mogelijkheid bestaat dat de bouten van de stuurkoppeling op de stuuras niet goed zijn vast gedraaid. Meer tekst."}]""", "MGP070060")
        assertEquals("De mogelijkheid bestaat dat de bouten van de stuurkoppeling op de stuuras niet goed zijn vast gedraaid", r.what)
        assertEquals(listOf(r), Rdw.decode(Rdw.encode(listOf(r))))
    }

    @Test fun greetingSaysItAMonthAhead() {
        val today = LocalDate.of(2026, 11, 19)
        assertNull("far away, nothing open", Rdw.reminder(LocalDate.of(2027, 6, 1), emptyList(), today, false, false))
        assertEquals("Your APK runs out on 13 December, in 24 days.", Rdw.reminder(LocalDate.of(2026, 12, 13), emptyList(), today, false, false))
        assertTrue(Rdw.reminder(LocalDate.of(2026, 11, 1), emptyList(), today, true, false)!!.startsWith("Je APK is verlopen sinds 1 november"))
        assertTrue(Rdw.reminder(null, listOf(Rdw.Recall("X", "y")), today, false, true)!!.contains("open recall"))
    }
}
