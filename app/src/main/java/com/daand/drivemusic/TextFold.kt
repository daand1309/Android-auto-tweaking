package com.daand.drivemusic

/**
 * Long explanations on the settings pages fold to their first sentence + "More ›", so a page shows its
 * settings, not paragraphs. Warnings and the setup checklist never go through this. Pure, unit tested.
 */
object TextFold {
    const val LIMIT = 140
    private const val FIRST = 120

    /** The folded version of [s], or null when it is short enough to show whole. */
    fun short(s: String): String? {
        if (s.length <= LIMIT) return null
        // The first sentence end - but "e.g." / "i.e." are not one.
        val end = Regex("""[.!?](\s|$)|\n""").findAll(s).map { it.range.first }
            .firstOrNull { i -> !s.substring(maxOf(0, i - 3), i + 1).let { it.endsWith("e.g.") || it.endsWith("i.e.") } }
        if (end != null && end in 1 until FIRST) return s.substring(0, end + if (s[end] == '\n') 0 else 1).trim()
        val cut = s.lastIndexOf(' ', FIRST).takeIf { it > 0 } ?: FIRST
        return s.substring(0, cut).trimEnd(',', ';', ' ', '-') + "…"
    }
}
