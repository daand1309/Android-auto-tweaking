package com.daand.drivemusic

import android.graphics.Bitmap
import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverArtTest {
    private fun bmp(size: Int = 64) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    @Test fun albumArtComesFirst() {
        val album = bmp(); val art = bmp()
        val md = MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, album).build()
        assertSame(album, CoverArt.from(md))
    }

    @Test fun otherwiseTheGeneralArtOrTheIcon() {
        val art = bmp()
        assertSame(art, CoverArt.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, art).build()))
        val icon = bmp()
        assertSame(icon, CoverArt.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon).build()))
    }

    @Test fun noPictureOrAPlaceholderPixelIsNoCover() {
        assertNull(CoverArt.from(null))
        assertNull(CoverArt.from(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "x").build()))
        assertNull(CoverArt.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp(1)).build()))
    }

    @Test fun theSameSongKeepsTheSameCover() {
        // Android hands over a fresh copy of the picture on every request: without a cache, the screens
        // saw a "new" cover every 3 s (Drive Mode faded it in again and again).
        val cache = CoverArt.Cache()
        var fetches = 0
        val first = cache.get("Mr. Brightside|The Killers") { fetches++; bmp() }
        val again = cache.get("Mr. Brightside|The Killers") { fetches++; bmp() }
        assertSame(first, again)
        assertEquals(1, fetches)
    }

    @Test fun aNewSongOrAMissingCoverIsFetchedAgain() {
        val cache = CoverArt.Cache()
        assertNull(cache.get("A|x") { null })              // cover not published yet
        val late = bmp()
        assertSame("it arrives a moment after the title", late, cache.get("A|x") { late })
        val other = bmp()
        assertSame(other, cache.get("B|y") { other })
        assertNull("no song, no cover", cache.get(null) { bmp() })
    }
}
