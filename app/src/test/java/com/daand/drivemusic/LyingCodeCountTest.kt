package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Red team: the count in PID 01 is the one thing that makes a code trustworthy straight away (the list
 * agrees with a separate answer from the car), so a garbled or hostile answer that lies about the count
 * would carry any garbage list straight into the voice. The byte is seven bits, so up to 127; a real car
 * stores one to four codes. Only a believable count may back a code.
 */
class LyingCodeCountTest {
    private fun alertCodes(a: CarAlerts, s: CarSnapshot): List<String> =
        a.check(s).filter { it.kind == CarAlerts.Kind.CHECK_ENGINE }.flatMap { it.codes }

    /** Random plausible-looking codes, the same way a garbled CAN answer produces them. */
    private fun garbage(rnd: Random, n: Int) = List(n) {
        "PCBU"[rnd.nextInt(4)] + "%01X".format(rnd.nextInt(4)) + "%03X".format(rnd.nextInt(0x1000))
    }

    @Test fun aCountOfAHundredAndTwentySevenBacksNothing() {
        // The worst case the protocol allows: count = 127 holds up to 127 codes, so every list "agrees".
        val rnd = Random(5)
        repeat(20) { seed ->
            val a = CarAlerts(readySeconds = 0)
            val codes = garbage(Random(seed), 6)
            assertTrue(
                "seed $seed: a lying count let $codes through",
                alertCodes(a, CarSnapshot(0, rpm = 900, checkEngine = true, codes = codes, codeCount = 127, storedReadAt = 1)).isEmpty()
            )
        }
        // Same list, believable count (the car really has 6 codes stored): told at once.
        val a = CarAlerts(readySeconds = 0)
        val six = listOf("P0171", "P0300", "P0420", "P0133", "P0442", "P0562")
        assertEquals(six, alertCodes(a, CarSnapshot(0, rpm = 900, checkEngine = true, codes = six, codeCount = 6, storedReadAt = 1)))
    }

    @Test fun aRealCodeBehindALyingCountStillReachesYouOnTheThirdRead() {
        // A genuinely broken car whose count byte is garbled on every answer: the code keeps coming back,
        // so the tracker confirms it - the count just isn't what backs it.
        val a = CarAlerts(readySeconds = 0)
        val told = mutableListOf<String>()
        for (i in 0 until 3) {
            told += alertCodes(a, CarSnapshot(i * 60_000L, rpm = 900, checkEngine = true,
                codes = listOf("P0420") + garbage(Random(100 + i), 4), codeCount = 127, storedReadAt = i * 60_000L + 1))
        }
        assertEquals("told once, on the third sighting", listOf("P0420"), told)
    }

    @Test fun theBoundaryIsEightCodes() {
        val eight = (1..8).map { "P0%03X".format(it) }
        val a = CarAlerts(readySeconds = 0)
        assertEquals(eight, alertCodes(a, CarSnapshot(0, rpm = 900, checkEngine = true, codes = eight, codeCount = 8, storedReadAt = 1)))
        val b = CarAlerts(readySeconds = 0)
        val nine = (1..9).map { "P0%03X".format(it) }
        assertTrue("nine codes is not a believable single answer",
            alertCodes(b, CarSnapshot(0, rpm = 900, checkEngine = true, codes = nine, codeCount = 9, storedReadAt = 1)).isEmpty())
    }

    @Test fun theLightWithoutCodesIsStillToldEvenWithAGarbledCount() {
        // The MIL path doesn't depend on the count at all: a light on with nothing believable still warns.
        val a = CarAlerts(readySeconds = 0)
        val alerts = a.check(CarSnapshot(0, rpm = 900, checkEngine = true, codes = garbage(Random(9), 5), codeCount = 127, storedReadAt = 1))
        assertTrue(alerts.none { it.kind == CarAlerts.Kind.CHECK_ENGINE && it.codes.isNotEmpty() })
    }
}
