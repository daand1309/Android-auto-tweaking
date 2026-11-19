package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Test

class NetTest {
    /** Red team: an endless answer is cut off while reading, not after it (no running out of memory). */
    @Test fun anEndlessAnswerStopsAtTheCap() {
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); served += len; return len }
        }
        assertEquals(100_000, endless.readCapped(100_000).length)
        assertEquals("read barely past the cap (one buffer)", true, served < 200_000)
        assertEquals("short", "short".byteInputStream().readCapped(100))
    }
}
