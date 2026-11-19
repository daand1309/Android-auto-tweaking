package com.daand.drivemusic

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * APK (MOT) expiry and open recalls for your number plate, from the RDW's open data (the Dutch vehicle
 * authority, free, no key): the vehicle register (m9d7-ebf2), recall status per plate (t49b-isb7,
 * "O" = open) and what each recall is about (af5r-44mf). Only the plate is sent; it's never logged.
 * Looked up at most once a week, in the background. Parsing is pure and unit tested.
 */
object Rdw {
    data class Recall(val ref: String, val what: String)

    /** "ab-123-c" -> "AB123C"; null when it can't be a Dutch plate (6 letters/digits). */
    fun plate(input: String): String? = input.uppercase().filter { it.isLetterOrDigit() }.takeIf { it.length == 6 }

    /** "20290113" -> 13 Jan 2029. */
    fun date(yyyymmdd: String?): LocalDate? = yyyymmdd?.trim()?.takeIf { it.length == 8 && it.all(Char::isDigit) }?.let {
        runCatching { LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt()) }.getOrNull()
    }

    fun parseApk(json: String): String? = runCatching {
        JSONArray(json).optJSONObject(0)?.optString("vervaldatum_apk")?.takeIf { date(it) != null }
    }.getOrNull()

    /** Recall references with status "O" (open; "P" = the maker reported it fixed). */
    fun parseOpen(json: String): List<String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("code_status") == "O" }.map { it.optString("referentiecode_rdw") }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())

    /** The defect in one sentence (the RDW's own Dutch description, shortened). */
    fun parseRecall(json: String, ref: String): Recall = runCatching {
        val d = JSONArray(json).optJSONObject(0)?.optString("omschrijving_defect").orEmpty()
        Recall(ref, GreetingText.cleanMeta(d).substringBefore(". ").take(140).ifBlank { "zie rdw.nl" })
    }.getOrDefault(Recall(ref, "zie rdw.nl"))

    fun encode(r: List<Recall>) = r.joinToString(";") { "${it.ref}|${it.what.replace(';', ',').replace('|', '/')}" }
    fun decode(s: String) = s.split(';').mapNotNull { e -> e.split('|', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Recall(it[0], it[1]) } }

    private fun get(dataset: String, query: String): String {
        val c = URL("https://opendata.rdw.nl/resource/$dataset.json?$query").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "DriveMusic/1.0")
        try { if (c.responseCode != 200) throw IllegalStateException("RDW answered ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText().take(200_000) } }
        finally { c.disconnect() }
    }

    /** Off the main thread. True when the RDW answered (the result is saved in [Prefs]). */
    fun check(ctx: Context): Boolean {
        val p = Prefs(ctx)
        val plate = plate(p.plate) ?: return false
        return try {
            val q = "kenteken=" + URLEncoder.encode(plate, "UTF-8")
            val apk = parseApk(get("m9d7-ebf2", q))
            val open = parseOpen(get("t49b-isb7", "$q&code_status=O"))
            val recalls = open.take(5).map { ref -> runCatching { parseRecall(get("af5r-44mf", "referentiecode_rdw=" + URLEncoder.encode(ref, "UTF-8")), ref) }.getOrDefault(Recall(ref, "zie rdw.nl")) }
            p.apkDate = apk.orEmpty(); p.recalls = encode(recalls); p.rdwCheckedAt = System.currentTimeMillis()
            DriveLog.log("RDW: checked - APK " + (date(apk)?.toString() ?: "unknown") + ", ${recalls.size} open recall(s)")   // never the plate
            true
        } catch (e: Exception) {
            DriveLog.log("RDW: couldn't check (${e.javaClass.simpleName}) - next time"); false
        }
    }

    /** Weekly, in the background (drive end / opening the Service page). */
    fun checkIfDue(ctx: Context, force: Boolean = false, done: () -> Unit = {}) {
        val p = Prefs(ctx)
        if (plate(p.plate) == null) return
        if (!force && System.currentTimeMillis() - p.rdwCheckedAt < 7 * 86_400_000L) return
        val app = ctx.applicationContext
        Thread({ if (check(app)) android.os.Handler(android.os.Looper.getMainLooper()).post(done) }, "rdw").start()
    }

    /**
     * What to say in the greeting (once a week at most): the APK when it runs out within 30 days (or
     * has), an open recall. Null when there's nothing to say.
     */
    fun reminder(apk: LocalDate?, recalls: List<Recall>, today: LocalDate, dutch: Boolean, butler: Boolean): String? {
        val parts = mutableListOf<String>()
        apk?.let { d ->
            val days = ChronoUnit.DAYS.between(today, d)
            val when_ = "%d %s".format(d.dayOfMonth, d.month.getDisplayName(java.time.format.TextStyle.FULL, if (dutch) java.util.Locale("nl") else java.util.Locale.ENGLISH))
            when {
                days < 0 -> parts += if (dutch) "Je APK is verlopen sinds $when_. Laat hem zo snel mogelijk keuren." else "Your APK expired on $when_. Do get it inspected soon."
                days <= 30 -> parts += if (dutch) "Je APK verloopt op $when_, over $days dagen." else "Your APK runs out on $when_, in $days days."
            }
        }
        if (recalls.isNotEmpty()) parts += if (dutch) "Er staat een terugroepactie open voor de auto: ${recalls.first().what}. Bel de dealer." + (if (recalls.size > 1) " Er zijn er ${recalls.size}." else "")
            else "There's an open recall for the car" + (if (recalls.size > 1) " (${recalls.size} in all)" else "") + " - the dealer will fix it free of charge."
        if (parts.isEmpty()) return null
        return (if (butler) (if (dutch) "Een mededeling, meneer. " else "A word, sir. ") else "") + parts.joinToString(" ")
    }
}
