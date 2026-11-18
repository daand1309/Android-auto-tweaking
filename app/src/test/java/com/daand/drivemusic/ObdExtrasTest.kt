package com.daand.drivemusic

import com.daand.drivemusic.obd.Elm327
import com.daand.drivemusic.obd.OnBoardTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdExtrasTest {
    // ---- 01-51: fuel type (the ASX answers "01" = petrol)
    @Test fun fuelTypeFromTheCar() {
        assertEquals(FuelPrices.Fuel.EURO95, Elm327.fuelType(listOf("415101")))
        assertEquals(FuelPrices.Fuel.DIESEL, Elm327.fuelType(listOf("41 51 04")))
        assertEquals(FuelPrices.Fuel.LPG, Elm327.fuelType(listOf("415105")))
        assertNull("electric: no pump fuel", Elm327.fuelType(listOf("415108")))
        assertNull(Elm327.fuelType(listOf("NO DATA")))
    }

    // ---- mode 02: freeze frame
    @Test fun freezeFrameCodeAndValues() {
        assertEquals("P0420", Elm327.freezeCode(listOf("42 02 00 04 20")))
        assertNull("no freeze frame stored", Elm327.freezeCode(listOf("42020000 00")))
        assertNull(Elm327.freezeCode(listOf("NO DATA")))
        assertEquals(listOf(0x1A, 0xF8), Elm327.freezeData(listOf("420C001AF8"), 0x0C))
        assertNull("another pid", Elm327.freezeData(listOf("420D0040"), 0x0C))
    }

    // ---- mode 06: on-board test results (OBDMID 21 = catalyst bank 1)
    @Test fun catalystTestSingleLine() {
        // 46 | 21 TID=80 UASID=0D value=0x0040 min=0x0000 max=0x0080: 64 of 128 = 50% of the limit, passes.
        val r = OnBoardTests.parse(listOf("4621800D004000000080"))
        assertEquals(1, r.size)
        assertEquals(0x21, r[0].mid); assertEquals(0x40, r[0].value); assertEquals(0x80, r[0].max)
        val c = OnBoardTests.catalyst(r)!!
        assertEquals(0.5, c.closeness, 0.001)
        assertTrue(c.passes)
    }

    @Test fun theAsxAtStartSaysNotRunYetThenTheResultComesMidDrive() {
        // Real answer from the ASX right after starting (17 Nov): value 0, limits 0..0x19 = not run yet.
        val atStart = mapOf("0621" to "00A\r0:462183200000\r1:00000019315742\r\r")
        val link = FakeElm(FakeElm.ASX + atStart)
        val s = com.daand.drivemusic.obd.ObdSession(link)
        s.start()
        assertNull(s.catalyst)
        assertNull(s.recheckCatalyst())
        // Later, warm and cruising: the test has run - 7 of a limit of 25.
        val done = FakeElm(FakeElm.ASX + mapOf("0621" to "00A\r0:462183200007\r1:00000019315742\r\r"))
        val s2 = com.daand.drivemusic.obd.ObdSession(done)
        val c = s2.recheckCatalyst()!!
        assertEquals(7 / 25.0, c.closeness, 0.001)
        assertTrue(c.passes)
    }

    @Test fun catalystTestMultiFrameCan() {
        // Two test records over ISO-TP frames, like the ELM327 prints them ("012" = byte count line).
        val lines = listOf("013", "0:4621800D0070", "1:00000080218124", "2:00110000FFFF00")
        val r = OnBoardTests.parse(lines)
        assertEquals(2, r.size)
        val c = OnBoardTests.catalyst(r)!!
        assertEquals("the worst test counts: 0x70 of 0x80", 0x70 / 128.0, c.closeness, 0.001)
        assertTrue(c.passes)
    }

    @Test fun aFailingOrMissingTest() {
        val fail = OnBoardTests.catalyst(OnBoardTests.parse(listOf("4621800D009000000080")))!!
        assertFalse(fail.passes)
        assertTrue(fail.closeness > 1.0)
        assertNull("no catalyst test in the answer", OnBoardTests.catalyst(OnBoardTests.parse(listOf("4601800D004000000080"))))
        assertTrue(OnBoardTests.parse(listOf("NO DATA")).isEmpty())
        assertTrue("garbage", OnBoardTests.parse(listOf("46 21 80")).isEmpty())
    }

    @Test fun aTestThatHasNotRunYetIsNoResult() {
        // 17 Nov 18:13 drive: "0% of its limit". A value of exactly 0 (with no lower limit) is the car
        // saying the test hasn't completed since its memory was cleared - not a perfect catalyst.
        assertNull(OnBoardTests.catalyst(OnBoardTests.parse(listOf("4621800D000000000080"))))
        // A real (small) value still counts.
        assertEquals(0.0078, OnBoardTests.catalyst(OnBoardTests.parse(listOf("4621800D000100000080")))!!.closeness, 0.001)
    }

    @Test fun anOldZeroInTheSavedHistoryIsSkipped() {
        // Saved on the phone by 1.247 before the fix above.
        val log = com.daand.drivemusic.obd.CatalystLog.decode("1700000000000:0.000;1700100000000:0.640")
        assertEquals(listOf(1700100000000L to 0.64), log)
    }

    @Test fun aStuckAdapterIsResetAndSetUpAgain() {
        val link = FakeElm(FakeElm.ASX)
        val s = com.daand.drivemusic.obd.ObdSession(link)
        s.start()
        link.sent.clear()
        assertTrue(s.resetAdapter())
        assertEquals("reset first, then the same set-up as at the start", "ATZ", link.sent.first())
        assertTrue(link.sent.containsAll(com.daand.drivemusic.obd.Elm327.INIT))
        assertTrue("and the car is asked again", link.sent.last() == "0100")
        // Every command it sends is one the read-only guard allows.
        assertTrue(link.sent.all { com.daand.drivemusic.obd.ReadOnly.allowed(it) })
        assertFalse(com.daand.drivemusic.obd.ObdSession(FakeElm(emptyMap())).resetAdapter())
    }
}
