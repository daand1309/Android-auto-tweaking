package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroImageTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test fun detectsLightLogoOnDarkSquare() {
        // 3x3: dark corners, white centre
        val px = intArrayOf(black, black, black, black, white, black, black, black, black)
        assertTrue(IntroImage.hasDarkBackground(px, 3, 3))
    }

    @Test fun leavesNormalPhotosAlone() {
        val px = intArrayOf(red, black, white, black, white, black, grey, black, red)
        assertFalse(IntroImage.hasDarkBackground(px, 3, 3))
    }

    @Test fun blackBecomesTransparentAndWhiteStaysSolid() {
        val px = intArrayOf(black, white, grey)
        IntroImage.lightOnTransparent(px)
        assertEquals(0, px[0])                   // black -> fully transparent
        assertEquals(white, px[1])               // white -> solid white
        assertEquals(0x80, px[2] ushr 24)        // grey -> half see-through (smooth edges)
        assertEquals(0xFFFFFF, px[2] and 0xFFFFFF)
    }

    @Test fun keepsLogoColours() {
        val px = intArrayOf(red, 0xFF800000.toInt())
        IntroImage.lightOnTransparent(px)
        assertEquals(red, px[0])                        // bright red stays solid red
        assertEquals(0x80, px[1] ushr 24)               // dark red -> red, half see-through
        assertEquals(0xFF0000, px[1] and 0xFFFFFF)
    }

    @Test fun carPhotoIsSizedByWidthAndNeverUpscaled() {
        // Tall 1080x2340 phone photo: kept at full width (was shrunk to 540 wide before).
        assertEquals(1080 to 2340, IntroImage.carPhotoSize(1080, 2340))
        // Big 4000x3000 camera photo: 1600 wide, aspect kept.
        assertEquals(1600 to 1200, IntroImage.carPhotoSize(4000, 3000))
        // Small image is not blown up.
        assertEquals(400 to 300, IntroImage.carPhotoSize(400, 300))
        // Extreme panorama/screenshot strip: height capped so it can't run out of memory.
        assertEquals(64 to 4000, IntroImage.carPhotoSize(1600, 100_000))
    }
}
