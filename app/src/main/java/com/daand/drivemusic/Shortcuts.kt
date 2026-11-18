package com.daand.drivemusic

/**
 * What the car screen offers so you don't have to touch the phone while driving: saved places to
 * navigate to (Home, Work) and up to three favourite contacts to call. Kept on the phone only, never
 * logged (numbers and places are personal). Pure encode/decode, unit tested.
 */
object Shortcuts {
    const val MAX_FAVOURITES = 3

    data class Place(val name: String, val lat: Double, val lon: Double) {
        fun encode() = "%s|%.5f|%.5f".format(java.util.Locale.ROOT, clean(name), lat, lon)
    }

    data class Favourite(val name: String, val number: String) {
        fun encode() = "${clean(name)}|${clean(number)}"
    }

    private fun clean(s: String) = s.replace('|', '/').replace(';', ',').trim()

    fun decodePlace(s: String): Place? {
        val p = s.split('|')
        if (p.size != 3) return null
        val lat = p[1].toDoubleOrNull() ?: return null
        val lon = p[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return Place(p[0].ifBlank { "Place" }, lat, lon)
    }

    /** Only digits, + and a few separators: anything else is not a phone number. */
    fun validNumber(n: String) = n.count { it.isDigit() } >= 3 && n.all { it.isDigit() || it in "+ -()/" }

    fun decodeFavourites(s: String): List<Favourite> = s.split(';').mapNotNull { e ->
        val p = e.split('|')
        if (p.size != 2 || !validNumber(p[1])) null else Favourite(p[0].ifBlank { p[1] }, p[1].trim())
    }.take(MAX_FAVOURITES)

    fun encodeFavourites(list: List<Favourite>) = list.take(MAX_FAVOURITES).joinToString(";") { it.encode() }

    /** Adding a contact already there replaces it (same number); the oldest drops off past the limit. */
    fun addFavourite(list: List<Favourite>, f: Favourite): List<Favourite> {
        val digits = { n: String -> n.filter { it.isDigit() } }
        return (list.filterNot { digits(it.number) == digits(f.number) } + f).takeLast(MAX_FAVOURITES)
    }

    /** "tel:" for the car's dialer (spaces and brackets out). */
    fun telUri(number: String) = "tel:" + number.filter { it.isDigit() || it == '+' }
}
