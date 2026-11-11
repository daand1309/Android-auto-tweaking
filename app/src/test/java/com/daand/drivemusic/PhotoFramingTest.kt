package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFramingTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A tall dark image with a checkered "car" in rows [from, to). */
    private fun image(w: Int, h: Int, from: Int, to: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (y in from until to && (x + y) % 2 == 0) white else black
    }

    @Test fun framesTheDetailedPartOfAPortraitPhoto() {
        val top = PhotoFraming.bestWindowTop(image(20, 100, 60, 80), 20, 100, 30)
        // The 30-row frame must contain the whole "car" (rows 60..79).
        assertTrue("top=$top", top <= 60 && top + 30 >= 80)
    }

    @Test fun plainImageIsRoughlyCentred() {
        // Nothing to focus on: every frame is equally good, so it's a (slightly raised) centre crop.
        val top = PhotoFraming.bestWindowTop(IntArray(20 * 50) { black }, 20, 50, 20)
        assertTrue("top=$top", top in 10..15)
    }

    @Test fun frameTallerThanImageStartsAtTheTop() {
        assertEquals(0, PhotoFraming.bestWindowTop(image(10, 10, 2, 5), 10, 10, 20))
    }
}
