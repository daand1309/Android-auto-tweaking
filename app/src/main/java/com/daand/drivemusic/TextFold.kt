package com.daand.drivemusic

/**
 * Long explanations on the settings pages fold to their first sentence + "More ›", so a page shows its
 * settings, not paragraphs. Warnings, the setup checklist and the Help page never go through this.
 * Pure, unit tested.
 */
object TextFold {
    const val LIMIT = 140
    private const val FIRST = 120

    /** The folded version of [s], or null when it is short enough to show whole. */
    fun short(s: String): String? {
        if (s.length <= LIMIT) return null
        val ends = sentenceEnds(s)
        var end = ends.firstOrNull()
        // "Music too quiet?" alone hides the answer: a question keeps the sentence after it.
        if (end != null && s[end] == '?') end = ends.drop(1).firstOrNull()?.takeIf { it < FIRST + 60 }
        if (end != null && end in 1 until FIRST + 60) return s.substring(0, end + if (s[end] == '\n') 0 else 1).trim()
        val cut = s.lastIndexOf(' ', FIRST).takeIf { it > 0 } ?: FIRST
        return s.substring(0, cut).trimEnd(',', ';', ' ', '-') + "…"
    }

    /** Where sentences end - but "e.g." / "i.e." are not one. */
    private fun sentenceEnds(s: String): List<Int> = Regex("""[.!?](\s|$)|\n""").findAll(s).map { it.range.first }
        .filter { i -> !s.substring(maxOf(0, i - 6), i + 1).let { t -> ABBREVIATIONS.any { t.endsWith(it) } } }.toList()

    private val ABBREVIATIONS = listOf("e.g.", "i.e.", "incl.", "approx.")
}
