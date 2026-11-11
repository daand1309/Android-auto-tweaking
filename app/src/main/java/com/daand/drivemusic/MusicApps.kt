package com.daand.drivemusic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Finds the YouTube Music app to control. Patched builds (Morphe, ReVanced, ...) are installed
 * under a different package name than the official app, so we detect them instead of hardcoding.
 */
object MusicApps {
    const val OFFICIAL_YTM = "com.google.android.apps.youtube.music"
    private const val GOOGLE_GMS = "com.google.android.gms"

    /** Known MicroG / GmsCore package names used by patched Google apps. */
    private val KNOWN_MICROG = listOf(
        "app.revanced.android.gms",
        "app.morphe.android.gms",
        "com.mgoogle.android.gms",
    )

    data class App(val pkg: String, val label: String)

    fun launchableApps(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Installed apps that look like YouTube Music; patched (non-Google) builds first. */
    fun youtubeMusicCandidates(context: Context): List<App> =
        launchableApps(context)
            .filter { looksLikeYtm(it) }
            .sortedBy { if (it.pkg == OFFICIAL_YTM) 1 else 0 }

    private fun looksLikeYtm(a: App): Boolean {
        val label = a.label.lowercase()
        return a.pkg.contains("youtube.music") || label.contains("youtube music") || label.contains("yt music")
    }

    fun autoDetect(context: Context): String =
        youtubeMusicCandidates(context).firstOrNull()?.pkg ?: OFFICIAL_YTM

    /** The MicroG/GmsCore app patched apps use for Google sign-in, if installed. */
    fun findMicroG(context: Context): String? {
        val pm = context.packageManager
        KNOWN_MICROG.firstOrNull { isInstalled(pm, it) }?.let { return it }
        return launchableApps(context).firstOrNull {
            it.pkg != GOOGLE_GMS && (it.pkg.endsWith(".android.gms") || it.label.lowercase().contains("microg"))
        }?.pkg
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "not installed"
    }

    fun isInstalled(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
