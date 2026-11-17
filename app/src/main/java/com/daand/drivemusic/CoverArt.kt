package com.daand.drivemusic

import android.graphics.Bitmap
import android.media.MediaMetadata

/**
 * The song's cover, as the music app already hands it to Android with the title and artist - so
 * nothing is downloaded and nothing about you goes anywhere. Album art first, then the general
 * artwork, then the player's icon for the item. Pure, unit tested.
 */
object CoverArt {
    private val KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    fun from(md: MediaMetadata?): Bitmap? {
        md ?: return null
        return KEYS.firstNotNullOfOrNull { k -> md.getBitmap(k)?.takeIf { !it.isRecycled && it.width > 1 && it.height > 1 } }
    }

    /**
     * One cover per song. Android hands over a fresh copy of the picture on every request, so without
     * this the screens saw a "new" cover every few seconds (Drive Mode faded it in again and again, the
     * home card redrew) and copied up to ~1 MB from the music app each time. A song whose cover hasn't
     * been published yet is asked again next time.
     */
    class Cache {
        private var key: String? = null
        private var art: Bitmap? = null

        @Synchronized fun get(songKey: String?, fetch: () -> Bitmap?): Bitmap? {
            if (songKey == null) { key = null; art = null; return null }
            if (songKey == key && art != null) return art
            key = songKey
            art = fetch()
            return art
        }
    }
}
