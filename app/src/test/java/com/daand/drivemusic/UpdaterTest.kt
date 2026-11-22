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

    // Luna's rule applied to a changelog: it is built from the real shape of the API response, with the
    // subjects and bodies of actual commits, not from a convenient little fixture.
    private val compare = """
        {"status":"ahead","commits":[
          {"sha":"a1","commit":{"message":"Radio: the ignition going off stops the radio too (Luna #22)\n\nignitionOff() paused YT Music and then ended the drive, but never stopped the radio."}},
          {"sha":"a2","commit":{"message":"Greeting: a cancelled greeting never starts\n\n#19 - the greeting prepares its text on a background thread."}},
          {"sha":"a3","commit":{"message":"\n\n   Chore: the subject arrives after blank lines"}},
          {"sha":"a4","commit":{"message":"Radio: the ignition going off stops the radio too (Luna #22)"}}
        ]}
    """.trimIndent()

    @Test fun theChangelogIsOneLinePerCommitSubject() {
        assertEquals(
            listOf(
                "Radio: the ignition going off stops the radio too (Luna #22)", // body dropped
                "Greeting: a cancelled greeting never starts",                   // body dropped
                "Chore: the subject arrives after blank lines",                  // first non-blank line, trimmed
                // a4 is the same subject again: shown once
            ),
            Updater.parseChangelog(compare)
        )
    }

    /** A comparison that GitHub could not answer (404, rate limit, garbage) must not break an update. */
    @Test fun anUnreadableChangelogIsEmptyNotAnError() {
        assertEquals(emptyList<String>(), Updater.parseChangelog("{\"message\":\"Not Found\"}"))
        assertEquals(emptyList<String>(), Updater.parseChangelog("not json at all"))
        assertEquals(emptyList<String>(), Updater.parseChangelog(""))
    }

    @Test fun theChangelogIsCapped() {
        val many = (1..50).joinToString(",") { "{\"commit\":{\"message\":\"change $it\"}}" }
        assertEquals(Updater.MAX_CHANGELOG_LINES, Updater.parseChangelog("{\"commits\":[$many]}").size)
        assertEquals(3, Updater.parseChangelog("{\"commits\":[$many]}", max = 3).size)
    }

    @Test fun theCompareTagsComeFromTheVersionNumbers() {
        assertEquals("v1.226", Updater.tagFor("1.226"))
        assertEquals("v1.226", Updater.tagFor(" 1.226 "))
        assertNull(Updater.tagFor("1.2.3"))
        assertNull(Updater.tagFor("latest"))
        assertNull(Updater.tagFor(""))
    }

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
