package com.daand.drivemusic

import android.graphics.Bitmap
import android.media.MediaMetadata
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
}
