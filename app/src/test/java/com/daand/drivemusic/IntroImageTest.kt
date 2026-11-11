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
}
