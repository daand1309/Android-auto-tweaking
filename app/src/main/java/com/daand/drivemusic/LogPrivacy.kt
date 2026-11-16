package com.daand.drivemusic

/**
 * What may never end up in the drive log.
 *
 * The log is meant to be pasted into a chat when something goes wrong, and it already carries
 * "GREETING: \"Good morning, Master, Dick-hoff...\"" - the name you configured, in the phonetic
 * spelling the voice uses. That is a personal detail in a file that gets shared, so it is taken out
 * here, once, for every line rather than at each call site that happens to include speech.
 *
 * Pure: [redact] is unit tested with lines from a real log.
 */
object LogPrivacy {
    const val NAME = "(name)"
    const val VIN = "VIN: (hidden)"

    /** A VIN is 17 characters, so it is recognisable without matching every 17-character token. */
    private val VIN_TEXT = Regex("""VIN:\s*[A-Za-z0-9]{17}""")

    /** Shorter than this and a name starts matching inside ordinary words. */
    private const val MIN_NAME = 3

    /**
     * Every spelling that should be hidden: the name as configured, the phonetic one, and the parts of
     * a hyphenated or double-barrelled name ("Dick-hoff" also hides a bare "Dick" in a later line),
     * longest first. Whole words only, so a name is never found inside another word.
     */
    fun names(vararg names: String): List<String> =
        names.flatMap { it.split(Regex("""[^\p{L}\p{N}]+""")) + it }
            .map { it.trim() }
            .filter { it.length >= MIN_NAME }
            .distinct()
            .sortedByDescending { it.length }

    /**
     * [line] with each of [names] replaced by "(name)" and any VIN replaced by "(hidden)".
     *
     * Whole-word matching, not substring: the first version replaced bare text, so a name like "Max"
     * would have mangled "maximum" in an unrelated log line.
     */
    fun redact(line: String, names: List<String>): String {
        var out = VIN_TEXT.replace(line, VIN)
        for (n in names.map { it.trim() }.filter { it.length >= MIN_NAME }.distinct().sortedByDescending { it.length }) {
            val whole = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            out = whole.replace(out, NAME)
        }
        return out
    }
}
