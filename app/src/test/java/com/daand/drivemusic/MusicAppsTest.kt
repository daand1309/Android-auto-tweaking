package com.daand.drivemusic

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MusicAppsTest {
    private lateinit var ctx: Context
    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun install(pkg: String, label: String, launchable: Boolean = true) {
        val pm = shadowOf(ctx.packageManager)
        val appInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = appInfo })
        if (launchable) {
            pm.addResolveInfoForIntent(launcher, ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = appInfo }
            })
        }
    }

    @Test fun fallsBackToOfficialWhenNothingInstalled() {
        assertEquals(MusicApps.OFFICIAL_YTM, MusicApps.autoDetect(ctx))
    }

    @Test fun prefersPatchedBuildOverOfficial() {
        install(MusicApps.OFFICIAL_YTM, "YouTube Music")
        install("app.morphe.android.apps.youtube.music", "YouTube Music")
        assertEquals("app.morphe.android.apps.youtube.music", MusicApps.autoDetect(ctx))
    }

    @Test fun detectsPatchedBuildByLabelEvenWithUnknownPackage() {
        install("com.example.something", "YT Music")
        install("com.example.notes", "Notes")
        assertEquals("com.example.something", MusicApps.autoDetect(ctx))
    }

    @Test fun ignoresUnrelatedApps() {
        install("com.spotify.music", "Spotify")
        assertTrue(MusicApps.youtubeMusicCandidates(ctx).isEmpty())
    }

    @Test fun findsMicroG() {
        assertNull(MusicApps.findMicroG(ctx))
        install("app.revanced.android.gms", "MicroG Services", launchable = false)
        assertEquals("app.revanced.android.gms", MusicApps.findMicroG(ctx))
    }

    @Test fun doesNotMistakeRealGooglePlayServicesForMicroG() {
        install("com.google.android.gms", "Google Play services")
        assertNull(MusicApps.findMicroG(ctx))
    }

    @Test fun prefsFallBackWhenChosenAppIsUninstalled() {
        install("app.morphe.android.apps.youtube.music", "YouTube Music")
        val prefs = Prefs(ctx)
        prefs.musicPackage = "com.removed.app"
        assertEquals("app.morphe.android.apps.youtube.music", prefs.musicPackage)
    }

    @Test fun prefsKeepUserChoice() {
        install("app.morphe.android.apps.youtube.music", "YouTube Music")
        install(MusicApps.OFFICIAL_YTM, "YouTube Music")
        val prefs = Prefs(ctx)
        prefs.musicPackage = MusicApps.OFFICIAL_YTM
        assertEquals(MusicApps.OFFICIAL_YTM, prefs.musicPackage)
    }
}
