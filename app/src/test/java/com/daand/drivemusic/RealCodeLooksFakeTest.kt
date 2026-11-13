package com.daand.drivemusic

import com.daand.drivemusic.obd.CarAlerts
import com.daand.drivemusic.obd.CarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Red team, the other way round: REAL codes that look like garbage. Each must still be told.
 * [Garage] plays drives the way ObdManager does (a fresh CarAlerts per drive, memory in between).
 */
class RealCodeLooksFakeTest {
    private class Garage {
        val urgent = mutableListOf<String>()
        val early = mutableListOf<String>()
        private var clock = 0L
        private var memory = DriveMemory()

        /** One drive: each read = (stored codes, the car's own count, pending codes). */
        fun drive(reads: List<Triple<List<String>, Int?, List<String>>>) {
            val alerts = memory.newAlerts()
            for ((stored, count, pending) in reads) {
                clock += 600_000
                val s = CarSnapshot(clock, rpm = 900, checkEngine = stored.isNotEmpty(), codes = stored, codeCount = count,
                    storedReadAt = clock, pendingCodes = pending, codesReadAt = clock)
                for (a in alerts.check(s)) when (a.kind) {
                    CarAlerts.Kind.CHECK_ENGINE -> urgent += a.codes
                    CarAlerts.Kind.EARLY_CODE -> early += a.codes
                    else -> {}
                }
                memory.afterRead(alerts, clock)
            }
            clock += 86_400_000L  // next day
        }
    }

    private fun read(stored: List<String> = emptyList(), count: Int? = stored.size, pending: List<String> = emptyList()) =
        Triple(stored, count, pending)

    @Test fun aRealCodeThatDropsOutOfSomeReadsIsStillTold() {
        // Bad connection: the real pending code is missing from ~40% of the answers.
        val rnd = Random(7)
        val g = Garage()
        g.drive(List(10) { read(pending = if (rnd.nextInt(10) < 4) emptyList() else listOf("P0301")) })
        assertEquals(listOf("P0301"), g.early)
    }

    @Test fun shortDrivesWithOneCodeReadEachStillAddUp() {
        // A 10-minute commute: one code read per drive.
        val g = Garage()
        repeat(4) { g.drive(listOf(read(pending = listOf("P0171")))) }
        assertEquals(listOf("P0171"), g.early)
    }

    @Test fun aSecondControlUnitMakesTheListLongerThanTheCount() {
        // The engine says 1 code, but the CVT unit answers too: 2 real codes, count 1.
        val g = Garage()
        g.drive(List(4) { read(stored = listOf("P0420", "P0841"), count = 1) })
        assertEquals(setOf("P0420", "P0841"), g.urgent.toSet())
        assertEquals(2, g.urgent.size)
    }

    @Test fun aRealCodeWithOccasionalCorruptedCopies() {
        // Sometimes a bit flips: P0301 arrives as P0381 / P0311 now and then.
        val g = Garage()
        g.drive(listOf(read(pending = listOf("P0301")), read(pending = listOf("P0381")), read(pending = listOf("P0301")),
            read(pending = listOf("P0311")), read(pending = listOf("P0301")), read(pending = listOf("P0301"))))
        assertEquals(listOf("P0301"), g.early)
    }

    @Test fun aRealCodeTheAppDoesntKnowIsStillTold() {
        val g = Garage()
        g.drive(List(4) { read(stored = listOf("P1A9C", "U1102"), count = 1) })
        assertEquals(setOf("P1A9C", "U1102"), g.urgent.toSet())
    }

    @Test fun aMonthOfAFaultyAdapterNeverInventsACode() {
        // 30 drives x 8 reads x 20 random codes (~5000 garbage codes over a month) with memory between drives.
        val rnd = Random(99)
        fun junk() = List(10) { "PCBU"[rnd.nextInt(4)] + "%04X".format(rnd.nextInt(0x4000)) }
        val g = Garage()
        repeat(30) { g.drive(List(8) { read(stored = listOf("P0420") + junk(), count = 1, pending = junk()) }) }
        assertEquals(listOf("P0420"), g.urgent)
        assertEquals(emptyList<String>(), g.early)
    }
}

/** What ObdManager keeps between drives: codes already told, and the code trackers (saved as text). */
private class DriveMemory {
    private val told = HashSet<String>()
    private var stored = ""
    private var pending = ""
    fun newAlerts() = CarAlerts(0, told.toSet(), com.daand.drivemusic.obd.CodeTracker(stored), com.daand.drivemusic.obd.CodeTracker(pending))
    fun afterRead(a: CarAlerts, @Suppress("UNUSED_PARAMETER") now: Long) {
        told += a.told(); stored = a.storedTracker.save(); pending = a.pendingTracker.save()
    }
}
