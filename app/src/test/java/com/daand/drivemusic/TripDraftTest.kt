package com.daand.drivemusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.daand.drivemusic.obd.TripHistory
import com.daand.drivemusic.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The trip-in-progress only exists in memory. If the process is killed, the copy written while
 * driving has to come back as a trip - exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class TripDraftTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val t0 = 1_700_000_000_000L

    @Before fun setUp() { TripHistory.clear(ctx) }

    private fun trip(at: Long = t0, km: Double = 12.4) =
        TripRecord(at, 18, km, 0.8, 74, 91, listOf("P0420"), null, price = 2.09)

    @Test fun aKilledDriveComesBackFromTheDraft() {
        TripHistory.saveDraft(ctx, trip(), now = t0 + 60_000)
        val back = TripHistory.recoverDraft(ctx, now = t0 + 60_000 + 5 * 60_000)
        assertNotNull("the interrupted drive is kept", back)
        assertEquals(12.4, back!!.km, 0.001)
        assertEquals(2.09, back.price, 0.001)
        assertEquals("its fault codes too", listOf("P0420"), back.codes)
        assertEquals("and it is in the history", 1, TripHistory.load(ctx).size)
        assertNull("recovering twice must not add it twice", TripHistory.recoverDraft(ctx, now = t0 + 3_600_000))
        assertEquals(1, TripHistory.load(ctx).size)
    }

    @Test fun aDriveThatIsStillRunningIsLeftAlone() {
        TripHistory.saveDraft(ctx, trip(), now = t0)
        assertNull("written 20 seconds ago, so still driving",
            TripHistory.recoverDraft(ctx, now = t0 + 20_000))
        assertTrue("the draft is still there for the next write", TripHistory.load(ctx).isEmpty())
        assertNotNull("and it is recovered once it goes stale", TripHistory.recoverDraft(ctx, now = t0 + 5 * 60_000))
    }

    @Test fun aDriveThatWasSavedAnywayIsNotCountedTwice() {
        // The drive ended normally but the process died before the draft was cleared.
        TripHistory.add(ctx, trip().copy(at = t0 + 30 * 60_000))   // saved, dated when it ended
        TripHistory.saveDraft(ctx, trip(), now = t0)
        assertNull("the trip is already in the history", TripHistory.recoverDraft(ctx, now = t0 + 3_600_000))
        assertEquals(1, TripHistory.load(ctx).size)
    }

    @Test fun aBarelyMovedDriveAndADamagedDraftAreDropped() {
        TripHistory.saveDraft(ctx, trip(km = 0.1), now = t0)
        assertNull(TripHistory.recoverDraft(ctx, now = t0 + 3_600_000))
        assertTrue(TripHistory.load(ctx).isEmpty())

        // Half-written JSON (the process died mid-write) must not crash and must not come back.
        java.io.File(ctx.filesDir, "trip-draft.json").writeText("""{"at":1700000000000,"km":1""")
        assertNull(TripHistory.recoverDraft(ctx, now = t0 + 3_600_000))
        assertTrue(TripHistory.load(ctx).isEmpty())
    }

    @Test fun savingADriveClearsTheDraft() {
        TripHistory.saveDraft(ctx, trip(), now = t0)
        TripHistory.add(ctx, trip(at = t0 + 20 * 60_000))
        TripHistory.clearDraft(ctx)
        assertNull(TripHistory.recoverDraft(ctx, now = t0 + 24 * 3_600_000))
        assertEquals(1, TripHistory.load(ctx).size)
    }

    /** Optimisation: the history is kept in memory while the file is unchanged - but a new drive shows at once. */
    @Test fun theCachedHistorySeesANewDriveRightAway() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.daand.drivemusic.obd.TripHistory.clear(ctx)
        val a = com.daand.drivemusic.obd.TripRecord(1_000L, 10, 5.0, 0.4, 90, 85)
        com.daand.drivemusic.obd.TripHistory.add(ctx, a)
        val first = com.daand.drivemusic.obd.TripHistory.load(ctx)
        org.junit.Assert.assertSame("unchanged file: the same list, not parsed again", first, com.daand.drivemusic.obd.TripHistory.load(ctx))
        com.daand.drivemusic.obd.TripHistory.add(ctx, a.copy(at = 2_000L))
        org.junit.Assert.assertEquals(2, com.daand.drivemusic.obd.TripHistory.load(ctx).size)
        com.daand.drivemusic.obd.TripHistory.clear(ctx)
        org.junit.Assert.assertEquals(0, com.daand.drivemusic.obd.TripHistory.load(ctx).size)
    }
}
