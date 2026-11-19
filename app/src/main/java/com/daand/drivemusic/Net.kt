package com.daand.drivemusic

/**
 * Reads at most [max] characters and stops downloading there. `readText().take(n)` first reads the
 * whole answer into memory - an endless or huge answer (a broken or hijacked server) would crash the
 * app before the cut. Used for every answer from the internet.
 */
fun java.io.InputStream.readCapped(max: Int): String = bufferedReader().use { r ->
    val sb = StringBuilder(minOf(max, 64 * 1024))
    val buf = CharArray(16 * 1024)
    while (sb.length < max) {
        val n = r.read(buf, 0, minOf(buf.size, max - sb.length))
        if (n < 0) break
        sb.append(buf, 0, n)
    }
    sb.toString()
}
