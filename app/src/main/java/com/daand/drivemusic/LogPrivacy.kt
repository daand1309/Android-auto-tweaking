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

    /** A name shorter than this would match inside ordinary words and wreck the log. */
    private const val MIN_NAME = 3

    /**
     * [line] with each of [names] replaced by "(name)" and any VIN replaced by "(hidden)".
     * Blank/short entries are ignored, and the longest name is replaced first so "Dick-hoff" is not
     * half-eaten by "Dick".
     */
    fun redact(line: String, names: List<String>): String {
        var out = VIN_TEXT.replace(line, VIN)
        for (n in names.map { it.trim() }.filter { it.length >= MIN_NAME }.distinct().sortedByDescending { it.length }) {
            out = out.replace(n, NAME, ignoreCase = true)
        }
        return out
    }
}
