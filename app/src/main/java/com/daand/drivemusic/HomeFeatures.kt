package com.daand.drivemusic

/** The three headings of the home grid, in order. */
enum class FeatureGroup(val en: String) { MUSIC("Music"), CAR("Car"), APP("App") }

/**
 * One thing you can open from the home screen. [words] are the other names people use for what's on its
 * page (English and Dutch), so search finds "pitch" under Voice and "brandstof" under Car data.
 */
data class Feature(val id: String, val group: FeatureGroup, val title: String, val icon: String, val words: List<String>)

/**
 * Everything on the home grid, in one list. The grid, its dots and search are all built from it, so a
 * new feature is one new entry here (plus what it opens in MainActivity.openFeature). Pure, unit tested.
 */
object HomeFeatures {
    val all: List<Feature> = listOf(
        Feature("music", FeatureGroup.MUSIC, "Music & driving", "♪", listOf(
            "music app", "youtube", "auto-start", "start", "auto-resume", "resume", "pause", "volume", "equalizer",
            "sound", "muziek", "hervatten", "volume", "geluid")),
        Feature("greeting", FeatureGroup.MUSIC, "Greeting", "🎙", listOf(
            "welcome", "name", "birthday", "weather", "news", "updates", "rain", "follow", "welkom", "naam",
            "verjaardag", "weer", "nieuws", "begroeting")),
        Feature("voice", FeatureGroup.MUSIC, "Voice", "🎩", listOf(
            "butler", "sassy", "pitch", "speed", "accent", "premium", "azure", "pronunciation", "stem",
            "toonhoogte", "snelheid", "uitspraak")),
        Feature("drive_mode", FeatureGroup.MUSIC, "Drive Mode", "▣", listOf(
            "big buttons", "holder", "full-screen", "full screen", "opens by itself", "grote knoppen", "houder")),
        Feature("car_data", FeatureGroup.CAR, "Car data", "📊", listOf(
            "obd", "adapter", "bluetooth", "battery", "accu", "fuel price", "brandstof", "brandstofprijs", "tank",
            "engine size", "fault", "code", "early warnings", "storing", "motor", "temperature", "vgate")),
        Feature("trips", FeatureGroup.CAR, "Trips & costs", "⛽", listOf(
            "trips", "costs", "driving score", "score", "fuel use", "ritten", "kosten", "verbruik")),
        Feature("service", FeatureGroup.CAR, "Service", "🔧", listOf(
            "service", "maintenance", "reminder", "dashboard", "onderhoud", "beurt", "herinnering")),
        Feature("car_screen", FeatureGroup.CAR, "Car screen", "🖥", listOf(
            "android auto", "head unit", "display", "unknown sources", "autoscherm")),
        Feature("themes", FeatureGroup.APP, "Themes", "🎨", listOf(
            "theme", "colour", "color", "animations", "intro", "look", "thema", "kleur", "animaties")),
        Feature("setup", FeatureGroup.APP, "Setup", "✓", listOf(
            "permissions", "notification access", "battery unrestricted", "rechten", "instellen")),
        Feature("updates", FeatureGroup.APP, "Updates", "⬇", listOf("update", "version", "install", "versie", "bijwerken")),
        Feature("backup", FeatureGroup.APP, "Backup", "💾", listOf("backup", "restore", "export", "import", "herstellen")),
        Feature("logs", FeatureGroup.APP, "Logs", "☰", listOf("log", "drive log", "problems", "logboek")),
        Feature("help", FeatureGroup.APP, "Help", "?", listOf("help", "privacy", "tips", "hulp")),
    )

    fun byGroup(): Map<FeatureGroup, List<Feature>> = FeatureGroup.values().associateWith { g -> all.filter { it.group == g } }

    /** Title matches first, then matches on the other words; empty for a blank query. */
    fun search(query: String): List<Feature> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val byTitle = all.filter { it.title.lowercase().contains(q) }
        val byWord = all.filter { f -> f !in byTitle && f.words.any { it.lowercase().contains(q) } }
        return byTitle + byWord
    }

    /** Which heading on a feature's page a search word belongs to (the page opens scrolled to it). */
    private val sections: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "car_data" to listOf(
            "Battery" to listOf("battery", "accu"),
            "Your car" to listOf("fuel price", "brandstof", "tank", "engine size"),
            "Adapter" to listOf("adapter", "obd", "bluetooth", "vgate"),
            "Early warnings" to listOf("early warnings"),
        ),
        "voice" to listOf("Delivery" to listOf("pitch", "speed", "toonhoogte", "snelheid", "pronunciation", "uitspraak")),
    )

    /** The heading to scroll to for [query] on [feature]'s page, or null for the top. */
    fun sectionFor(query: String, feature: Feature): String? {
        if (feature.id == "service") return "Service"   // service lives on the Car data page
        val q = query.trim().lowercase()
        if (q.length < 3) return null   // "a" would match "battery": too little to jump anywhere
        return sections[feature.id]?.firstOrNull { (_, words) -> words.any { it.contains(q) || q.contains(it) } }?.first
    }
}
