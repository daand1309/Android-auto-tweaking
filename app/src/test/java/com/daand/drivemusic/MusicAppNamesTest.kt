package com.daand.drivemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detection must survive Morphe/ReVanced renames without grabbing unrelated music apps. */
class MusicAppNamesTest {
    private fun yes(pkg: String, label: String) = assertTrue("$pkg / $label", MusicApps.looksLikeYtm(pkg, label))
    private fun no(pkg: String, label: String) = assertFalse("$pkg / $label", MusicApps.looksLikeYtm(pkg, label))

    @Test fun currentNames() {
        yes("com.google.android.apps.youtube.music", "YouTube Music")
        yes("app.morphe.android.apps.youtube.music", "YouTube Music")
        yes("app.revanced.android.apps.youtube.music", "YouTube Music")
    }

    @Test fun plausibleFutureRenames() {
        yes("app.morphe.music", "Morphe Music")               // rebrand of both name and package
        yes("app.morphe.android.ytmusic", "Music")            // shortened package
        yes("com.example.player", "YT Music")
        yes("com.example.player", "YouTube-Music")            // punctuation
        yes("app.rvx.music", "RVX Music")
    }

    @Test fun ignoresOtherMusicApps() {
        no("com.spotify.music", "Spotify")
        no("org.videolan.vlc", "VLC")
        no("com.google.android.youtube", "YouTube")         // the video app, not Music
        no("app.morphe.android.youtube", "YouTube")         // patched video app
        no("app.revanced.android.gms", "MicroG Services")
    }
}
