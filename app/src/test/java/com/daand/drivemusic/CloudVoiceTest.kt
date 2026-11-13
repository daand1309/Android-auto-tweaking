package com.daand.drivemusic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudVoiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun neverPastTheFreeAllowance() {
        assertTrue(CloudVoice.fits(0, 400))
        assertTrue(CloudVoice.fits(CloudVoice.MONTHLY_CAP - 400, 400))
        assertFalse(CloudVoice.fits(CloudVoice.MONTHLY_CAP - 399, 400))
        assertFalse(CloudVoice.fits(Int.MAX_VALUE, 1))           // no overflow tricks
        assertTrue("cap is below Azure's free 500,000", CloudVoice.MONTHLY_CAP < 500_000)
        val p = Prefs(ctx).apply { cloudVoiceOn = true; azureKey = "0123456789abcdef0123456789abcdef" }
        p.cloudVoiceMonth = CloudVoice.monthKey(); p.cloudVoiceChars = CloudVoice.MONTHLY_CAP - 10
        assertTrue(CloudVoice.skipReason(p, 400)!!.contains("used up"))
        // A new month starts from zero.
        p.cloudVoiceMonth = "199901"
        assertEquals(0, CloudVoice.usedThisMonth(p)); assertNull(CloudVoice.skipReason(p, 400))
        p.cloudVoiceOn = false
        assertEquals("off", CloudVoice.skipReason(p, 400))
    }

    @Test fun theKeyNeverLeavesThePhone() {
        val p = Prefs(ctx).apply { azureKey = "SECRETKEY0123456789SECRETKEY"; azureVoice = "en-GB-RyanNeural"; cloudVoiceChars = 1234 }
        val backup = p.exportJson()
        assertFalse("key in backup", backup.contains("SECRETKEY"))
        assertFalse("usage counter in backup", backup.contains("cloud_voice_chars"))
        assertTrue("the voice choice is kept", backup.contains("en-GB-RyanNeural"))
    }

    @Test fun requestTextIsSafeAndSoundsLikeAButler() {
        val s = CloudVoice.ssml("Fish & chips <now>, \"sir\" O'Brien", "en-GB-ThomasNeural", dutch = false)
        assertTrue(s.contains("Fish &amp; chips &lt;now&gt;, &quot;sir&quot; O&apos;Brien"))
        assertTrue(s.contains("rate='-6%'") && s.contains("en-GB-ThomasNeural") && s.contains("xml:lang='en-GB'"))
        assertTrue(CloudVoice.ssml("Hallo", CloudVoice.DUTCH_VOICE, true).contains("xml:lang='nl-NL'"))
        // Only free-tier (standard) voices; Dutch mode uses the Dutch voice; unknown choice -> Thomas.
        assertTrue(CloudVoice.BRITISH_VOICES.keys.none { it.contains("HD") || it.contains(":") })
        val p = Prefs(ctx).apply { azureVoice = "something-else" }
        assertEquals("en-GB-ThomasNeural", CloudVoice.voiceFor(p, false))
        assertEquals(CloudVoice.DUTCH_VOICE, CloudVoice.voiceFor(p, true))
    }

    @Test fun regionAsTyped() {
        assertEquals("westeurope", CloudVoice.region(" West Europe "))
        assertEquals("northeurope", CloudVoice.region("northeurope"))
        assertNull(CloudVoice.region("x"))
        // Only letters and digits survive, so typing a web address can't point the voice elsewhere.
        assertEquals("evilcomx", CloudVoice.region("evil.com/x"))
    }
}
