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
}
