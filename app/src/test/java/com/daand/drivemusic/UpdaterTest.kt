package com.daand.drivemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)  // for the real org.json implementation
class UpdaterTest {

    @Test fun parsesVersionFromTag() {
        assertEquals(23, Updater.parseVersionCode("v1.23"))
        assertEquals(7, Updater.parseVersionCode("1.7"))
        assertNull(Updater.parseVersionCode("latest"))
        assertNull(Updater.parseVersionCode("v1.2.3"))
    }

    @Test fun onlyAllowsHttpsGithubHosts() {
        assertTrue(Updater.isAllowedHost("https://api.github.com/repos/x/y/releases/latest"))
        assertTrue(Updater.isAllowedHost("https://github.com/x/y/releases/download/v1.2/DriveMusic.apk"))
        assertTrue(Updater.isAllowedHost("https://objects.githubusercontent.com/abc"))
        assertFalse(Updater.isAllowedHost("http://github.com/x"))               // not https
        assertFalse(Updater.isAllowedHost("https://github.com.evil.example/x"))   // look-alike
        assertFalse(Updater.isAllowedHost("https://evilgithub.com/x"))
        assertFalse(Updater.isAllowedHost("not a url"))
    }

    private fun releaseJson(tag: String, assets: String, extra: String = "") = """
        {"tag_name":"$tag","html_url":"https://github.com/r/releases/$tag","body":"Fixes","draft":false,"prerelease":false$extra,
         "assets":[$assets]}
    """.trimIndent()

    private val apkAsset = """{"name":"DriveMusic.apk","browser_download_url":"https://github.com/r/releases/download/v1.9/DriveMusic.apk"}"""
    private val shaAsset = """{"name":"DriveMusic.apk.sha256","browser_download_url":"https://github.com/r/releases/download/v1.9/DriveMusic.apk.sha256"}"""

    @Test fun parsesRelease() {
        val r = Updater.parseRelease(releaseJson("v1.9", "$apkAsset,$shaAsset"))!!
        assertEquals(9, r.versionCode)
        assertEquals("1.9", r.versionName)
        assertTrue(r.apkUrl.endsWith("DriveMusic.apk"))
        assertTrue(r.sha256Url!!.endsWith(".sha256"))
        assertEquals("Fixes", r.notes)
    }

    @Test fun ignoresReleaseWithoutApk() {
        assertNull(Updater.parseRelease(releaseJson("v1.9", shaAsset)))
    }

    @Test fun ignoresDraftsAndPrereleases() {
        assertNull(Updater.parseRelease(releaseJson("v1.9", apkAsset).replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Updater.parseRelease(releaseJson("v1.9", apkAsset).replace("\"draft\":false", "\"draft\":true")))
    }

    @Test fun readsChecksumFile() {
        val h = "a".repeat(64)
        assertEquals(h, Updater.parseSha256File("$h  DriveMusic.apk\n"))
        assertEquals(h, Updater.parseSha256File(h.uppercase()))
        assertNull(Updater.parseSha256File("garbage"))
    }
}
