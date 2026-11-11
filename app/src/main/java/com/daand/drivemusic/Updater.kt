package com.daand.drivemusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Built-in updater. Checks this app's GitHub Releases, downloads the APK and hands it to Android's
 * installer (Android always shows its own "Update" confirmation).
 *
 * Safety checks before anything is installed:
 *  1. Only HTTPS downloads from github.com / githubusercontent.com.
 *  2. The APK's SHA-256 must match the checksum published with the release.
 *  3. The APK must be this app (same package) and a newer version.
 *  4. The APK must be signed with the SAME key as the installed app (Android enforces this too).
 */
object Updater {
    const val REPO = "daand1309/Android-auto-tweaking"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_APK_BYTES = 60L * 1024 * 1024
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 42

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256Url: String?,
        val notes: String,
        val pageUrl: String,
    )

    class UpdateException(message: String) : Exception(message)

    // ------------------------------------------------------------------ pure helpers (unit tested)

    /** "v1.23" -> 23. Returns null for tags we don't understand. */
    fun parseVersionCode(tag: String): Int? = Regex("""^v?\d+\.(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isAllowedHost(url: String): Boolean = try {
        val u = URL(url)
        val host = u.host.lowercase()
        u.protocol == "https" && (host == "github.com" || host.endsWith(".github.com") ||
            host == "githubusercontent.com" || host.endsWith(".githubusercontent.com"))
    } catch (e: Exception) {
        false
    }

    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        val code = parseVersionCode(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            when {
                name.endsWith(".apk") -> apk = url
                name.endsWith(".sha256") -> sha = url
            }
        }
        if (apk == null) return null
        return Release(code, tag.removePrefix("v"), apk, sha, o.optString("body"), o.optString("html_url"))
    }

    fun parseSha256File(text: String): String? =
        Regex("""\b([0-9a-fA-F]{64})\b""").find(text)?.groupValues?.get(1)?.lowercase()

    // ------------------------------------------------------------------ network (call off the main thread)

    fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Returns the latest release if it is newer than what's installed, else null. */
    fun checkForUpdate(ctx: Context): Release? {
        val json = httpGetText(LATEST_URL)
        Prefs(ctx).lastUpdateCheck = System.currentTimeMillis()
        if (json == null) return null  // 404 = no releases published yet
        val release = parseRelease(json) ?: return null
        return release.takeIf { it.versionCode > currentVersionCode(ctx) }
    }

    /** Downloads and verifies the update. Throws UpdateException with a readable message on any problem. */
    fun downloadAndVerify(ctx: Context, r: Release, onProgress: (Int) -> Unit = {}): File {
        if (!isAllowedHost(r.apkUrl)) throw UpdateException("Refusing to download from ${r.apkUrl}")
        val shaUrl = r.sha256Url ?: throw UpdateException("Release has no checksum file - not installing")
        val expectedSha = parseSha256File(httpGetText(shaUrl) ?: "")
            ?: throw UpdateException("Could not read the release checksum")

        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update-${r.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        open(r.apkUrl).let { conn ->
            try {
                if (conn.responseCode != 200) throw UpdateException("Download failed (HTTP ${conn.responseCode})")
                if (!isAllowedHost(conn.url.toString())) throw UpdateException("Unexpected download host ${conn.url.host}")
                val total = conn.contentLengthLong
                if (total > MAX_APK_BYTES) throw UpdateException("Update file is unexpectedly large")
                var done = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_APK_BYTES) throw UpdateException("Update file is unexpectedly large")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) {
            file.delete()
            throw UpdateException("Checksum mismatch - download corrupted or tampered with. Not installing.")
        }
        verifyApk(ctx, file, r)
        return file
    }

    private fun verifyApk(ctx: Context, file: File, r: Release) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("Downloaded file is not a valid app")
        if (archive.packageName != ctx.packageName) {
            file.delete()
            throw UpdateException("Downloaded app is not Drive Music (${archive.packageName})")
        }
        if (archive.longVersionCode <= currentVersionCode(ctx)) {
            file.delete()
            throw UpdateException("Downloaded version is not newer")
        }
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val mine = signerHashes(installed)
        val theirs = signerHashes(archive)
        // If either can't be read, let Android's installer enforce the match (it always does).
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) {
            file.delete()
            throw UpdateException(
                "This update is signed with a different key than the app you have installed. " +
                    "Uninstall Drive Music once and install version ${r.versionName} manually from GitHub; " +
                    "after that, updates will install automatically."
            )
        }
    }

    private fun signerHashes(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    // ------------------------------------------------------------------ install

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Hands the APK to Android. Android then shows its own confirmation screen. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateInstallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE  // installer must add its result extras
            )
            session.commit(pi.intentSender)
        }
        DriveLog.log("UPDATE: handed version to Android installer")
    }

    // ------------------------------------------------------------------ background check + notification

    /** Called by the background service. Checks at most once a day and never while driving. */
    fun maybeCheckInBackground(ctx: Context, driving: Boolean) {
        val prefs = Prefs(ctx)
        if (!prefs.autoUpdateCheck || driving) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < 24 * 60 * 60 * 1000L) return
        Thread {
            try {
                val r = checkForUpdate(ctx)
                if (r == null) {
                    DriveLog.log("UPDATE: checked - app is up to date")
                } else if (prefs.notifiedUpdate != r.versionName) {
                    DriveLog.log("UPDATE: version ${r.versionName} available")
                    prefs.notifiedUpdate = r.versionName
                    notifyUpdate(ctx, r)
                }
            } catch (e: Exception) {
                DriveLog.log("UPDATE: check failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }.start()
    }

    private fun notifyUpdate(ctx: Context, r: Release) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Drive Music update available")
            .setContentText("Version ${r.versionName} - tap to update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    // ------------------------------------------------------------------ http

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DriveMusic-Updater")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    private fun httpGetText(url: String): String? {
        if (!isAllowedHost(url)) throw UpdateException("Refusing to contact $url")
        val conn = open(url)
        try {
            return when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
                404 -> null
                else -> throw UpdateException("GitHub returned HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
