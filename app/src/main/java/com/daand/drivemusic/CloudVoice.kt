package com.daand.drivemusic

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Optional premium voice: Microsoft Azure neural text-to-speech (a properly posh British butler).
 *
 * Kept free, three ways:
 * 1. You create the Azure resource on the **Free (F0)** tier: Azure can't bill it; past the monthly
 *    allowance it just refuses.
 * 2. The app counts every character it sends and stops at [MONTHLY_CAP] (90% of the 500,000 free
 *    characters a month), then uses the phone's voice until the next month.
 * 3. Only standard neural voices are offered (the "HD" ones aren't part of the free allowance).
 *
 * No signal, slow answer or any error: the phone's own voice speaks instead - never silence.
 * The key stays on the phone (not in backups, never in the log).
 */
object CloudVoice {
    /** 90% of Azure's 500,000 free neural characters per month. */
    const val MONTHLY_CAP = 450_000
    const val DEFAULT_REGION = "westeurope"

    /** Standard (free-tier) British male voices, poshest first; the Dutch voice for Dutch mode. */
    val BRITISH_VOICES = linkedMapOf(
        "en-GB-ThomasNeural" to "Thomas",
        "en-GB-RyanNeural" to "Ryan",
        "en-GB-OliverNeural" to "Oliver",
        "en-GB-ElliotNeural" to "Elliot",
        "en-GB-AlfieNeural" to "Alfie",
        "en-GB-NoahNeural" to "Noah",
        "en-GB-EthanNeural" to "Ethan",
    )
    const val DUTCH_VOICE = "nl-NL-MaartenNeural"

    /** "202611" - the month the free allowance counts in. */
    fun monthKey(cal: Calendar = Calendar.getInstance()) = "%04d%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

    /** Characters already used this month (a new month starts at 0). */
    fun usedThisMonth(p: Prefs, month: String = monthKey()) = if (p.cloudVoiceMonth == month) p.cloudVoiceChars else 0

    /** Whether [chars] more still fit in this month's free allowance. Pure. */
    fun fits(used: Int, chars: Int) = chars > 0 && used.toLong() + chars <= MONTHLY_CAP

    /** Why the cloud voice won't be used for this line (null = use it). Pure apart from reading prefs. */
    fun skipReason(p: Prefs, ssmlChars: Int): String? = when {
        !p.cloudVoiceOn -> "off"
        p.azureKey.isBlank() -> "no key set"
        !fits(usedThisMonth(p), ssmlChars) -> "free allowance for this month used up - phone voice until next month"
        else -> null
    }

    fun voiceFor(p: Prefs, dutch: Boolean) = if (dutch) DUTCH_VOICE else p.azureVoice.takeIf { it in BRITISH_VOICES } ?: BRITISH_VOICES.keys.first()

    /** The request text: the line, read a touch slower and lower - butler gravitas. Pure. */
    fun ssml(text: String, voice: String, dutch: Boolean): String {
        val lang = if (dutch) "nl-NL" else "en-GB"
        return "<speak version='1.0' xml:lang='$lang'><voice name='$voice'><prosody rate='-6%' pitch='-3%'>" +
            escape(text) + "</prosody></voice></speak>"
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** Region as typed ("West Europe", "westeurope ") -> "westeurope"; null if it can't be a region. Pure. */
    fun region(input: String): String? = input.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.length in 4..30 }

    /**
     * Speech as MP3 bytes (network: background thread). Counts the characters against the month
     * BEFORE sending, so even a request that fails half-way is counted (never under-counts).
     */
    fun synthesize(p: Prefs, text: String, dutch: Boolean): ByteArray {
        val voice = voiceFor(p, dutch)
        val body = ssml(text, voice, dutch)
        skipReason(p, body.length)?.let { throw IOException(it) }
        val region = region(p.azureRegion) ?: throw IOException("region not set")
        val month = monthKey()
        p.cloudVoiceChars = usedThisMonth(p, month) + body.length
        p.cloudVoiceMonth = month
        val c = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 2_500; c.readTimeout = 4_000
            c.setRequestProperty("Ocp-Apim-Subscription-Key", p.azureKey)
            c.setRequestProperty("Content-Type", "application/ssml+xml")
            c.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            c.setRequestProperty("User-Agent", "DriveMusic")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) throw IOException(when (code) {
                401 -> "the key isn't accepted (check key and region)"
                403 -> "Azure refused - free allowance used up or key/region wrong"
                429 -> "too many requests right now"
                else -> "Azure answered $code"
            })
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size < 500) throw IOException("empty audio")
            return bytes
        } finally { c.disconnect() }
    }
}
