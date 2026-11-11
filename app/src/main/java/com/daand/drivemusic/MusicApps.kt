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

    private fun looksLikeYtm(a: App): Boolean = looksLikeYtm(a.pkg, a.label)

    /**
     * Matches the official app and patched builds, including future renames: Morphe/ReVanced have
     * used package names like "app.morphe.android.apps.youtube.music" and labels like "YouTube Music",
     * but a rebrand could change either - so several spellings are accepted.
     */
    fun looksLikeYtm(pkg: String, label: String): Boolean {
        val p = pkg.lowercase()
        val l = label.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        val patcher = listOf("morphe", "revanced", "rvx", "vanced").any { p.contains(it) || l.contains(it) }
        return p.contains("youtube.music") || p.contains("youtubemusic") || p.contains("ytmusic") ||
            l.contains("youtube music") || l.contains("yt music") || l.contains("ytmusic") ||
            (patcher && (p.contains("music") || l.contains("music")))
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

    fun versionOf(context: Context, pkg: String): String = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "not installed"
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
