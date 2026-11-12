package com.daand.drivemusic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** Builds the spoken welcome text. Pure logic, unit tested. */
object GreetingText {

    data class WeatherNow(
        val tempNow: Double,
        val code: Int,
        val windKmh: Double,
        val tempMin: Double,
        val tempMax: Double,
        val rainChance: Int,
        // Road-condition details (defaults = "nothing to report").
        val precipMm: Double = 0.0,
        val snowfallCm: Double = 0.0,
        val gustsKmh: Double = 0.0,
        val visibilityM: Double? = null,
        val isDay: Boolean = true,
        val nowTime: String = "",   // local "HH:mm" at the weather location
        val sunset: String = "",    // local "HH:mm"
    )

    /** Song to announce: "Up first: <title> by <artist>". */
    data class Song(val title: String, val artist: String)

    fun build(
        name: String, dutch: Boolean, cal: Calendar, weather: WeatherNow?, place: String,
        random: Random = Random.Default, roads: Boolean = false, song: Song? = null, birthday: String = "",
    ): String {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val parts = mutableListOf<String>()

        val who = SpeechText.spokenName(name, dutch)
        parts += if (who.isEmpty()) timeGreeting(hour, dutch) + "." else "${timeGreeting(hour, dutch)}, $who."
        parts += if (dutch) "Welkom terug." else "Welcome back."

        // A special-occasion line takes the place of one random line when it applies.
        val occasion = occasionLine(cal, birthday, dutch)
        if (occasion != null) parts += occasion
        dayLine(day, hour, dutch)?.let { parts += it }
        val pool = if (dutch) LINES_NL else LINES_EN
        pool.shuffled(random).take(if (occasion != null) 1 else 2).forEach { parts += it }

        if (weather != null) {
            parts += weatherSentence(weather, place, dutch, withAdvice = !roads)
            if (roads) parts += roadSentence(weather, dutch)
        }
        if (song != null) parts += songSentence(song, dutch, upFirst = true)
        parts += if (dutch) "Ik zet je muziek aan. Goede reis!" else "Starting your music. Have a great drive!"
        return parts.joinToString(" ")
    }

    /** A festive line for a birthday ("MM-DD") or a known holiday, or null. */
    fun occasionLine(cal: Calendar, birthday: String, dutch: Boolean): String? {
        val month = cal.get(Calendar.MONTH) + 1
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val md = "%02d-%02d".format(month, dayOfMonth)
        if (birthday.isNotBlank() && birthday.trim() == md) {
            return if (dutch) "En nog iets belangrijks: gefeliciteerd met je verjaardag! Fijne dag, helemaal van mij."
            else "And something important: happy birthday! Have a wonderful day, from me to you."
        }
        return when (md) {
            "01-01" -> if (dutch) "Gelukkig nieuwjaar! Laten we er een topjaar van maken." else "Happy New Year! Let's make this one count."
            "12-25", "12-26" -> if (dutch) "Vrolijk kerstfeest! Rij voorzichtig door de drukte." else "Merry Christmas! Drive gently through the holiday rush."
            "12-31" -> if (dutch) "Oudjaarsdag! Rij veilig, en geniet vanavond." else "It's New Year's Eve! Drive safe, and enjoy tonight."
            "10-31" -> if (dutch) "Happy Halloween! Pas op voor spoken op de weg." else "Happy Halloween! Watch out for ghosts on the road."
            "04-27" -> if (dutch) "Fijne Koningsdag! Oranje staat je goed." else "Happy King's Day! Orange suits you."
            "12-05" -> if (dutch) "Fijne pakjesavond! Sinterklaas rijdt ook mee." else "Happy Sinterklaas evening!"
            "02-14" -> if (dutch) "Fijne Valentijnsdag! De auto houdt ook van jou." else "Happy Valentine's Day! The car loves you too."
            else -> null
        }
    }

    /** Short weather line for the car screen, e.g. "Utrecht: 18 degrees, clear skies, rain 5%". */
    /** Compact form for the home tile and car screen, e.g. "4° · overcast · 20% rain". */
    fun tileWeather(w: WeatherNow, dutch: Boolean): String {
        val desc = describe(w.code, dutch).removePrefix("een ").removePrefix("a ")
        return "${w.tempNow.roundToInt()}° · $desc · ${w.rainChance}% ${if (dutch) "regen" else "rain"}"
    }

    fun timeGreeting(hour: Int, dutch: Boolean) = when (hour) {
        in 5..11 -> if (dutch) "Goedemorgen" else "Good morning"
        in 12..17 -> if (dutch) "Goedemiddag" else "Good afternoon"
        in 18..22 -> if (dutch) "Goedenavond" else "Good evening"
        else -> if (dutch) "Goedenacht" else "Good night"
    }

    private fun dayLine(day: Int, hour: Int, dutch: Boolean): String? = when {
        hour >= 23 || hour < 5 -> if (dutch) "Het is laat, rij voorzichtig." else "It's late, so drive extra carefully."
        day == Calendar.MONDAY -> if (dutch) "Ah, maandag. We maken er een goede van." else "Ah, Monday. Let's make it a good one."
        day == Calendar.FRIDAY -> if (dutch) "Het is vrijdag. Het weekend zit praktisch al op de passagiersstoel." else "It's Friday. The weekend is practically in the passenger seat."
        day == Calendar.SATURDAY || day == Calendar.SUNDAY -> if (dutch) "Weekendmodus is geactiveerd." else "Weekend mode is activated."
        else -> null
    }

    fun describe(code: Int, dutch: Boolean): String = when (code) {
        0 -> if (dutch) "een heldere lucht" else "clear skies"
        1 -> if (dutch) "het meeste zon" else "mostly clear skies"
        2 -> if (dutch) "wisselende bewolking" else "partly cloudy skies"
        3 -> if (dutch) "bewolkt weer" else "overcast skies"
        45, 48 -> if (dutch) "mist" else "fog"
        51, 53, 55 -> if (dutch) "motregen" else "drizzle"
        56, 57, 66, 67 -> if (dutch) "ijzel" else "freezing rain"
        61 -> if (dutch) "lichte regen" else "light rain"
        63 -> if (dutch) "regen" else "rain"
        65 -> if (dutch) "zware regen" else "heavy rain"
        71, 73, 75, 77 -> if (dutch) "sneeuw" else "snow"
        80, 81, 82 -> if (dutch) "regenbuien" else "rain showers"
        85, 86 -> if (dutch) "sneeuwbuien" else "snow showers"
        95, 96, 99 -> if (dutch) "onweer" else "thunderstorms"
        else -> if (dutch) "wisselend weer" else "mixed weather"
    }

    fun songSentence(song: Song, dutch: Boolean, upFirst: Boolean): String {
        val a = SpeechText.cleanArtist(song.artist)
        val t = SpeechText.cleanTitle(song.title, a, dutch)
        val what = when {
            a.isEmpty() -> t
            dutch -> "$t van $a"
            else -> "$t by $a"
        }
        return if (dutch) (if (upFirst) "Als eerste: $what." else "Nu speelt: $what.")
        else (if (upFirst) "Up first: $what." else "Now playing: $what.")
    }

    /** Road conditions derived from live weather: ice, snow, fog, heavy rain, gusts and daylight. */
    fun roadSentence(w: WeatherNow, dutch: Boolean): String {
        val issues = mutableListOf<String>()
        val wet = w.precipMm > 0.0 || w.code in 51..67 || w.code in 80..82
        when {
            w.snowfallCm > 0.0 || w.code in 71..77 || w.code in 85..86 ->
                issues += if (dutch) "Er ligt mogelijk sneeuw op de weg, rij rustig" else "there may be snow on the roads, so take it slow"
            w.code in setOf(56, 57, 66, 67) || (w.tempNow <= 1.0 && wet) ->
                issues += if (dutch) "er is kans op ijzel, vooral op bruggen" else "black ice is possible, especially on bridges"
            w.tempNow <= 1.0 ->
                issues += if (dutch) "het vriest bijna, pas op voor gladheid" else "it's close to freezing, so watch out for slippery patches"
        }
        val vis = w.visibilityM
        if ((vis != null && vis < 1000) || w.code in setOf(45, 48)) {
            issues += if (vis != null && vis < 1000) {
                val m = ((vis / 50).roundToInt() * 50).coerceAtLeast(50)
                if (dutch) "het zicht is maar $m meter" else "visibility is only $m metres"
            } else if (dutch) "het is mistig" else "it's foggy"
        }
        if (w.precipMm >= 4.0 || w.code in setOf(65, 82)) {
            issues += if (dutch) "het regent hard, let op voor aquaplaning" else "it's raining hard, so watch out for aquaplaning"
        } else if (wet && issues.isEmpty()) {
            issues += if (dutch) "de wegen zijn nat" else "the roads are wet"
        }
        if (w.gustsKmh >= 60) {
            issues += if (dutch) "er staan windstoten tot ${w.gustsKmh.roundToInt()} kilometer per uur" else "there are gusts up to ${w.gustsKmh.roundToInt()} kilometres per hour"
        }
        val lights = when {
            !w.isDay -> if (dutch) "Het is donker, dus lichten aan." else "It's dark out, so lights on."
            minutesUntil(w.nowTime, w.sunset)?.let { it in 0..90 } == true ->
                if (dutch) "De zon gaat om ${SpeechText.time(w.sunset, true)} onder." else "The sun sets at ${SpeechText.time(w.sunset, false)}, so you'll need your lights soon."
            else -> null
        }
        val head = if (issues.isEmpty()) {
            if (dutch) "Wegcondities: droog en goed zicht." else "Road conditions look good: dry with good visibility."
        } else {
            (if (dutch) "Wegcondities: " else "Road conditions: ") + issues.joinToString(", ") + "."
        }
        return if (lights != null) "$head $lights" else head
    }

    /** Minutes from "HH:mm" a to "HH:mm" b on the same day, or null if unparseable. */
    fun minutesUntil(a: String, b: String): Int? {
        fun mins(t: String) = Regex("""(\d{1,2}):(\d{2})""").find(t)?.destructured?.let { (h, m) -> h.toInt() * 60 + m.toInt() }
        val x = mins(a) ?: return null
        val y = mins(b) ?: return null
        return y - x
    }

    fun weatherSentence(w: WeatherNow, place: String, dutch: Boolean, withAdvice: Boolean = true): String {
        val now = SpeechText.number(w.tempNow.roundToInt(), dutch)
        val lo = SpeechText.number(w.tempMin.roundToInt(), dutch)
        val hi = SpeechText.number(w.tempMax.roundToInt(), dutch)
        val desc = describe(w.code, dutch)
        val sb = StringBuilder()
        if (dutch) {
            sb.append(if (place.isNotBlank()) "In $place is het nu $now graden met $desc. " else "Het is nu $now graden met $desc. ")
            sb.append("Vandaag tussen de $lo en $hi graden, met ${w.rainChance} procent kans op regen.")
        } else {
            sb.append(if (place.isNotBlank()) "In $place it's currently $now degrees with $desc. " else "It's currently $now degrees with $desc. ")
            sb.append("Today between $lo and $hi degrees, with a ${w.rainChance} percent chance of rain.")
        }
        if (withAdvice) advice(w, dutch)?.let { sb.append(' ').append(it) }
        return sb.toString()
    }

    private fun advice(w: WeatherNow, dutch: Boolean): String? = when {
        w.code in setOf(56, 57, 66, 67) || w.tempNow <= 1 -> if (dutch) "Let op, het kan glad zijn op de weg." else "Careful, the roads could be slippery."
        w.code in setOf(45, 48) -> if (dutch) "Vergeet je mistlampen niet." else "Don't forget your fog lights."
        w.code in 95..99 -> if (dutch) "Rij rustig, er is onweer." else "Take it easy, there's thunder about."
        w.windKmh >= 50 -> if (dutch) "Het waait flink, houd het stuur stevig vast." else "It's very windy, keep a firm grip on the wheel."
        w.rainChance >= 60 || w.code in 51..82 -> if (dutch) "De ruitenwissers krijgen vandaag wat te doen." else "Your wipers might get a workout today."
        w.tempNow >= 28 -> if (dutch) "Het is warm, zet de airco maar aan." else "It's a hot one, so crank up the air conditioning."
        w.code <= 1 -> if (dutch) "Zonnebril op, het is een mooie dag." else "Sunglasses on, it's a beautiful day."
        else -> null
    }

    val LINES_EN = listOf(
        "All systems are go. Your chariot awaits.",
        "Seatbelt on, sunglasses optional, legend status confirmed.",
        "Traffic has been notified of your arrival.",
        "Today's forecast: a one hundred percent chance of excellent driving.",
        "Mirrors checked? Excellent. Let's roll.",
        "Driving mode: legendary.",
        "Please keep your hands on the wheel and your dance moves to a minimum.",
        "Your playlist has been approved by the committee of good taste.",
        "Reminder: indicators are not optional accessories.",
        "Fuel up on good vibes. Petrol sold separately.",
        "The car missed you. It told me so.",
        "Another day, another excellent drive.",
        "Navigation is ready, the music is ready, and frankly, so am I.",
        "A warm hello to any passengers on board. Especially if it's Mum.",
        "Mum, if you're listening: your son is an excellent driver.",
        "Your personal driving assistant is fully caffeinated and ready to go.",
        "Speed limits are a suggestion. Just kidding. They are the law.",
        "Smooth roads and green lights, that's the plan.",
        "Welcome aboard. The captain has switched on the good music sign.",
        "The engine is purring. It's clearly happy to see you.",
        "Destination: wherever you like. Vibe: immaculate.",
        "Remember to stay hydrated and keep it classy.",
        "I've polished the virtual dashboard just for you.",
        "Ready when you are, boss.",
        "Enjoy the ride. I'll handle the soundtrack.",
        "Let the record show: you looked great getting into the car.",
        "I ran the numbers. You are statistically a fantastic driver.",
        "Buckle up, buttercup.",
        "I've alerted the paparazzi. Try to act natural.",
        "Warning: driver is dangerously good-looking today.",
        "The road is yours. Try not to let the power go to your head.",
        "I kept the engine warm and my opinions to myself.",
        "Chauffeur reporting for duty. Tips are appreciated but not expected.",
        "Let's go make the other cars jealous.",
        "I cancelled all your meetings. Just kidding. Or am I?",
        "Red lights are just nature's way of telling you to vibe.",
        "If anyone asks, you're a professional. Of what, we won't specify.",
        "I've selected only the finest electrons for today's audio.",
        "Your mother raised a safe driver. Let's prove her right.",
        "Roses are red, this car is too, let's get going, me and you.",
        "I would offer you snacks, but I am tragically made of software.",
        "Plot twist: today is the day you use your indicators every single time.",
        "Somewhere, a parking space is already nervous.",
        "I polished every pixel on this dashboard. You're welcome.",
        "Legend has it the smoothest drivers also press play the fastest.",
        "Hydrate, concentrate, and absolutely dominate this commute.",
        "I've been practising my announcer voice all day. No pressure.",
        "The GPS and I had a chat. It thinks you're doing great.",
        "Fasten your seatbelt. My jokes get worse from here.",
        "Attention passengers: the good-taste department has approved this trip.",
        "Mum, if you're in the back, he always checks his mirrors. Promise.",
        // --- movie nods ---
        "I'm going to make you an offer you can't refuse: excellent music.",
        "Life is like a box of chocolates, but this playlist is all bangers.",
        "Buckle up. We're gonna need a bigger road.",
        "May the forks be with you at every junction.",
        "To infinity, and the next roundabout!",
        "I'll be back... right after this left turn.",
        "Roads? Where we're going, we still need roads, actually.",
        "Houston, we have a playlist.",
        "You shall not pass... without indicating.",
        "Why so serious? It's only traffic.",
        "Keep your friends close and your fuel gauge closer.",
        "There's no place like the open road. Responsibly, of course.",
        "Frankly my dear, I do give a damn about your seatbelt.",
        "Say hello to my little air freshener.",
        "You had me at engine start.",
        "This is the way. The way is apparently the ring road.",
        "Just keep driving, just keep driving.",
        "With great horsepower comes great responsibility.",
        "The first rule of car club: always check your mirrors.",
        "Adventure is out there, just past the next exit.",
        "Hasta la vista, parking spot.",
        "Winter is coming, so check your tyre pressure.",
        "They may take our parking, but they'll never take our freedom.",
        "Carpe diem. Also, carpe that gap in the middle lane.",
        "Elementary, my dear driver: signal, mirror, go.",
        "I feel the need. The need to obey the speed limit.",
        "Nobody expects the Spanish inquisition, but I do expect you to indicate.",
        "Today we are not just driving. Today we are arriving in style.",
        // --- game nods ---
        "The cake is a lie, but this playlist is very real.",
        "War never changes. Monday traffic, somehow, gets worse.",
        "A winner is you. Now drive safely.",
        "Achievement unlocked: successfully left the driveway.",
        "Loading good vibes... one hundred percent complete.",
        "Main objective: reach destination. Side quest: enjoy the music.",
        "Stay a while, and listen to this absolute banger.",
        "Do a barrel roll! ...Actually, please don't.",
        "It's dangerous to go alone. Take this playlist.",
        "Would you kindly fasten your seatbelt?",
        "Hey! Listen! Mind the cyclists.",
        "You must construct additional playlists.",
        "Nice drive. Plus one hundred experience points.",
        "Checkpoint reached: the end of your street.",
        "No lives lost. Difficulty setting: rush hour.",
        "Easy mode activated: the roads are clear.",
        "Respawn complete. Let's take that junction again.",
        "Level up: you reversed without hitting the bin.",
        "GG. Now let's get you home.",
        "Press play to continue. No coins required.",
        "The princess is in another castle, but your destination is close.",
        "Fast travel is not available, so let's do this the scenic way.",
        "Tutorial complete. You are ready for the big road.",
        "Power-up acquired: an excellent soundtrack.",
        "Boss battle incoming: the supermarket car park.",
        "Health bar full, fuel bar decent, vibes bar maxed.",
        "Insert driver. Press start.",
        "New high score: zero stress, maximum tunes.",
    )

    val LINES_NL = listOf(
        "Alle systemen staan klaar. Je koets wacht op je.",
        "Gordel om, zonnebril optioneel, legendestatus bevestigd.",
        "Het verkeer is op de hoogte gebracht van je komst.",
        "De voorspelling van vandaag: honderd procent kans op uitstekend rijden.",
        "Spiegels gecheckt? Top. We gaan.",
        "Rijmodus: legendarisch.",
        "Houd je handen aan het stuur en je dansmoves tot een minimum.",
        "Je afspeellijst is goedgekeurd door de commissie van goede smaak.",
        "Ter herinnering: richtingaanwijzers zijn geen optionele accessoires.",
        "Tank vol met goede vibes. Benzine wordt apart verkocht.",
        "De auto heeft je gemist. Dat heeft hij me zelf verteld.",
        "Weer een dag, weer een uitstekende rit.",
        "De navigatie is klaar, de muziek is klaar, en eerlijk gezegd ben ik dat ook.",
        "Een warme groet aan alle passagiers. Vooral als het mama is.",
        "Mam, als je meeluistert: je zoon is een uitstekende chauffeur.",
        "Je persoonlijke rijassistent heeft koffie gehad en is er klaar voor.",
        "Snelheidslimieten zijn een suggestie. Grapje. Het is de wet.",
        "Gladde wegen en groene lichten, dat is het plan.",
        "Welkom aan boord. De kapitein heeft het lampje voor goede muziek aangezet.",
        "De motor spint. Hij is duidelijk blij je te zien.",
        "Bestemming: waar je maar wilt. Sfeer: perfect.",
        "Blijf gehydrateerd en blijf stijlvol.",
        "Ik heb het virtuele dashboard speciaal voor jou gepoetst.",
        "Klaar wanneer jij het bent, baas.",
        "Geniet van de rit. Ik regel de soundtrack.",
        "Voor de goede orde: je zag er fantastisch uit toen je instapte.",
        "Ik heb het doorgerekend. Je bent statistisch gezien een topchauffeur.",
        "Riem vast, schoonheid.",
        "Ik heb de paparazzi gewaarschuwd. Doe maar gewoon.",
        "Waarschuwing: de bestuurder is vandaag gevaarlijk knap.",
        "De weg is van jou. Laat de macht niet naar je hoofd stijgen.",
        "Ik hield de motor warm en mijn mening voor me.",
        "Chauffeur meldt zich voor dienst. Fooi mag, hoeft niet.",
        "Laten we de andere auto's jaloers maken.",
        "Ik heb al je afspraken afgezegd. Grapje. Of niet?",
        "Rode lichten zijn de natuur die zegt: even chillen.",
        "Als iemand het vraagt: je bent een professional. Waarin, dat laten we open.",
        "Ik heb alleen de beste elektronen uitgekozen voor de muziek van vandaag.",
        "Je moeder heeft een veilige chauffeur opgevoed. Laten we dat bewijzen.",
        "Rozen zijn rood, deze auto ook, kom op, wij samen, en geen gezeik.",
        "Ik zou je een snack aanbieden, maar ik ben helaas van software gemaakt.",
        "Verrassing: vandaag gebruik je élke keer je richtingaanwijzer.",
        "Ergens is een parkeerplek nu al zenuwachtig.",
        "Ik heb elke pixel op dit dashboard gepoetst. Graag gedaan.",
        "Het schijnt dat de soepelste chauffeurs ook het snelst op play drukken.",
        "Drink wat, focus, en maak gehakt van deze rit.",
        "Ik heb de hele dag mijn omroepstem geoefend. Geen druk.",
        "De navigatie en ik hadden een praatje. Ze vindt je geweldig.",
        "Riem vast. Mijn grappen worden hierna alleen maar slechter.",
        "Beste passagiers: de afdeling goede smaak heeft deze rit goedgekeurd.",
        "Mam, als je achterin zit: hij kijkt altijd in zijn spiegels. Beloofd.",
        // --- film-knipogen ---
        "Ik ga je een aanbod doen dat je niet kunt weigeren: uitstekende muziek.",
        "Het leven is als een doos bonbons, maar deze afspeellijst is puur top.",
        "Riem vast. We hebben een grotere weg nodig.",
        "Moge de vork met je zijn bij elke splitsing.",
        "Naar de oneindigheid, en de volgende rotonde!",
        "Ik kom terug... meteen na deze bocht naar links.",
        "Wegen? Waar wij heen gaan hebben we nog steeds wegen nodig, trouwens.",
        "Houston, we hebben een afspeellijst.",
        "Gij zult niet passeren... zonder richting aan te geven.",
        "Waarom zo serieus? Het is maar file.",
        "Hou je vrienden dichtbij en je brandstofmeter nog dichterbij.",
        "Er is geen plek zoals de open weg. Netjes, uiteraard.",
        "Eerlijk gezegd, schat, kan het me wél schelen of je gordel om is.",
        "Zeg eens gedag tegen mijn kleine luchtverfrisser.",
        "Je had me al bij het starten van de motor.",
        "Dit is de weg. De weg is blijkbaar de rondweg.",
        "Gewoon blijven rijden, gewoon blijven rijden.",
        "Met grote paardenkracht komt grote verantwoordelijkheid.",
        "De eerste regel van autoclub: kijk altijd in je spiegels.",
        "Avontuur ligt daarbuiten, net voorbij de volgende afslag.",
        "Hasta la vista, parkeerplek.",
        "De winter komt eraan, dus controleer je bandenspanning.",
        "Ze mogen ons parkeren afnemen, maar nooit onze vrijheid.",
        "Pluk de dag. En pluk dat gaatje in de middelste rijstrook.",
        "Eenvoudig, beste chauffeur: richting aangeven, spiegel, gaan.",
        "Ik voel de drang. De drang om me aan de snelheidslimiet te houden.",
        "Niemand verwacht de Spaanse inquisitie, maar ik verwacht wel dat je knippert.",
        "Vandaag rijden we niet alleen. Vandaag arriveren we met stijl.",
        // --- game-knipogen ---
        "De taart is een leugen, maar deze afspeellijst is echt.",
        "Oorlog verandert nooit. Maandagfile wordt op de een of andere manier erger.",
        "Een winnaar ben jij. Rij nu voorzichtig.",
        "Prestatie ontgrendeld: succesvol de oprit afgereden.",
        "Goede vibes laden... honderd procent voltooid.",
        "Hoofddoel: bereik de bestemming. Zijmissie: geniet van de muziek.",
        "Blijf even, en luister naar deze absolute kraker.",
        "Doe een vatrol! ...Nee, toch maar niet.",
        "Het is gevaarlijk om alleen te gaan. Neem deze afspeellijst mee.",
        "Zou je zo vriendelijk willen zijn je gordel om te doen?",
        "Hé! Luister! Let op de fietsers.",
        "Je moet extra afspeellijsten bouwen.",
        "Mooi gereden. Plus honderd ervaringspunten.",
        "Checkpoint bereikt: het einde van je straat.",
        "Geen levens verloren. Moeilijkheidsgraad: spitsuur.",
        "Makkelijke modus geactiveerd: de wegen zijn vrij.",
        "Respawn voltooid. Laten we dat kruispunt nog eens doen.",
        "Level omhoog: je reed achteruit zonder de kliko te raken.",
        "GG. Laten we je naar huis brengen.",
        "Druk op play om verder te gaan. Geen muntjes nodig.",
        "De prinses is in een ander kasteel, maar jouw bestemming is dichtbij.",
        "Snelreizen is niet beschikbaar, dus we doen het via de mooie route.",
        "Tutorial voltooid. Je bent klaar voor de grote weg.",
        "Power-up verkregen: een uitstekende soundtrack.",
        "Baasgevecht op komst: de parkeerplaats van de supermarkt.",
        "Levensbalk vol, brandstofbalk prima, vibesbalk maximaal.",
        "Voer bestuurder in. Druk op start.",
        "Nieuw record: nul stress, maximale muziek.",
    )
}

/** Makes text sound right when spoken: titles, names, numbers, times and messy YouTube song titles. */
object SpeechText {
    private val TITLES_EN = mapOf(
        "mr" to "Mister", "mister" to "Mister", "mrs" to "Missus", "ms" to "Miz", "miss" to "Miss",
        "dr" to "Doctor", "sir" to "Sir", "dhr" to "Mister", "mevr" to "Missus", "meneer" to "Mister", "mevrouw" to "Missus",
    )
    private val TITLES_NL = mapOf(
        "mr" to "meneer", "mister" to "meneer", "dhr" to "meneer", "meneer" to "meneer",
        "mrs" to "mevrouw", "ms" to "mevrouw", "mevr" to "mevrouw", "mevrouw" to "mevrouw", "miss" to "juffrouw",
        "dr" to "dokter", "sir" to "meneer",
    )

    /** "Mr. Dickhoff" -> "Mister Dickhoff" (English) / "meneer Dickhoff" (Dutch). */
    fun spokenName(name: String, dutch: Boolean): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val key = words[0].trimEnd('.').lowercase()
        val title = (if (dutch) TITLES_NL else TITLES_EN)[key]
        return if (title != null) (listOf(title) + words.drop(1)).joinToString(" ") else words.joinToString(" ")
    }

    /** -2 -> "minus 2" / "min 2", so it isn't read as "dash two". */
    fun number(n: Int, dutch: Boolean) = if (n < 0) (if (dutch) "min ${-n}" else "minus ${-n}") else n.toString()

    /** "17:05" -> "5:05 PM" (English) / "17.05 uur" (Dutch). */
    fun time(hhmm: String, dutch: Boolean): String {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(hhmm) ?: return hhmm
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2]
        if (dutch) return if (min == "00") "$h uur" else "$h.$min uur"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        return if (min == "00") "$h12 $ampm" else "$h12:$min $ampm"
    }

    /** "Queen - Topic" -> "Queen", "QueenVEVO" -> "Queen". */
    fun cleanArtist(artist: String): String = tidy(
        artist.replace(Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO\s*$"""), "")
            .replace(Regex("""\s*\(?official\)?\s*$""", RegexOption.IGNORE_CASE), "")
    )

    private val JUNK = Regex(
        """\b(official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|8k|explicit|clip|mv|m/v)\b""",
        RegexOption.IGNORE_CASE
    )

    /** "Queen - Bohemian Rhapsody (Official Video Remastered) [4K]" -> "Bohemian Rhapsody". */
    fun cleanTitle(title: String, artist: String, dutch: Boolean): String {
        var t = title
        // Bracketed bits that are only video/audio labels.
        t = t.replace(Regex("""[(\[【][^)\]】]*[)\]】]""")) { if (JUNK.containsMatchIn(it.value)) "" else it.value }
        // "Song - Remastered 2011", "Song | Official Video"
        t = t.replace(Regex("""\s+[-|]\s+[^-|]*$""")) { if (JUNK.containsMatchIn(it.value)) "" else it.value }
        // "Artist - Song" (common in YouTube video titles)
        if (artist.isNotBlank()) t = t.replace(Regex("^" + Regex.escape(artist) + """\s*[-–:]\s*""", RegexOption.IGNORE_CASE), "")
        // Featuring
        val feat = if (dutch) " met " else " featuring "
        t = t.replace(Regex("""\s+(ft\.?|feat\.?)\s+""", RegexOption.IGNORE_CASE), feat)
        t = t.replace(Regex("""[(\[]\s*(ft\.?|feat\.?)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)) { "${feat.trimEnd()} ${it.groupValues[2]}" }
        t = tidy(t)
        if (t.length > 70) t = t.take(70).substringBeforeLast(' ')
        return t.ifBlank { tidy(title) }
    }

    /** Removes emojis, hashtags and symbols that voices read out oddly. */
    fun tidy(s: String): String = s
        .replace(Regex("""#\w+"""), "")
        .replace(Regex("""[\p{So}\p{Cn}\p{Cs}|*_~^<>{}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '–', ':', ',')

    /** Splits text into sentences so the voice can pause naturally between them. */
    fun sentences(text: String): List<String> {
        val pieces = text.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in pieces) {
            val prev = out.lastOrNull()
            // Don't break after abbreviations or initials ("Mr. Brightside", "Guns N' Roses Vol. 2", "J. Cole").
            if (prev != null && ABBREVIATION.containsMatchIn(prev)) out[out.size - 1] = "$prev $p" else out += p
        }
        return out
    }

    private val ABBREVIATION = Regex(
        """(\b(mr|mrs|ms|dr|st|vs|vol|no|ft|feat|jr|sr|dhr|mevr|nr|blz|ca)\.|\b[A-Z]\.)$""",
        RegexOption.IGNORE_CASE
    )
}

/** Weather from Open-Meteo (free, no account or API key). Call off the main thread. */
object Weather {
    /** Coordinates are rounded to ~10 km before being sent, for privacy. */
    fun round(v: Double) = (v * 10).roundToInt() / 10.0

    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${round(lat)}&longitude=${round(lon)}" +
            "&current=temperature_2m,weather_code,wind_speed_10m,wind_gusts_10m,precipitation,snowfall,visibility,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunset" +
            "&forecast_days=1&timezone=auto"

    fun parse(json: String): GreetingText.WeatherNow {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return GreetingText.WeatherNow(
            tempNow = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            tempMin = daily.getJSONArray("temperature_2m_min").getDouble(0),
            tempMax = daily.getJSONArray("temperature_2m_max").getDouble(0),
            rainChance = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
            precipMm = cur.optDouble("precipitation", 0.0),
            snowfallCm = cur.optDouble("snowfall", 0.0),
            gustsKmh = cur.optDouble("wind_gusts_10m", 0.0),
            visibilityM = cur.optDouble("visibility").takeIf { !it.isNaN() },
            isDay = cur.optInt("is_day", 1) == 1,
            nowTime = cur.optString("time").substringAfter('T', ""),
            sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T', "") ?: "",
        )
    }

    fun fetch(lat: Double, lon: Double): GreetingText.WeatherNow = parse(httpGet(url(lat, lon)))

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** Looks up a city name, e.g. "Utrecht". */
    fun findPlace(query: String, dutch: Boolean): Place? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&format=json&language=${if (dutch) "nl" else "en"}")
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "DriveMusic")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("weather service returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } finally {
            conn.disconnect()
        }
    }
}

/** Chooses the best-sounding voice. Pure logic, unit tested. */
object VoicePicker {
    const val GOOGLE_TTS = "com.google.android.tts"

    fun localeFor(dutch: Boolean): Locale = if (dutch) Locale("nl", "NL") else Locale.UK

    /** Installed voices that speak the right language (not ones that still need downloading). */
    fun usable(voices: Collection<android.speech.tts.Voice>, dutch: Boolean): List<android.speech.tts.Voice> {
        val lang = localeFor(dutch).language
        return voices.filter { v ->
            v.locale.language == lang &&
                !v.features.orEmpty().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    }

    /**
     * Best voice: the saved choice if still available; otherwise prefer the exact country
     * (UK English / Netherlands Dutch), offline (works without signal), highest quality, lowest latency.
     */
    fun best(voices: Collection<android.speech.tts.Voice>, dutch: Boolean, preferred: String = ""): android.speech.tts.Voice? {
        val ok = usable(voices, dutch)
        ok.firstOrNull { it.name == preferred }?.let { return it }
        val country = localeFor(dutch).country
        return ok.sortedWith(
            compareByDescending<android.speech.tts.Voice> { it.locale.country == country }
                .thenBy { it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name }
        ).firstOrNull()
    }

    /** Human-friendly label, e.g. "English (UK) · voice gbb · offline · very high quality". */
    fun label(v: android.speech.tts.Voice): String {
        val q = when {
            v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very high quality"
            v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
            v.quality >= android.speech.tts.Voice.QUALITY_NORMAL -> "normal quality"
            else -> "low quality"
        }
        val short = Regex("""-x-([a-z0-9]+)""").find(v.name)?.groupValues?.get(1) ?: v.name
        val net = if (v.isNetworkConnectionRequired) "needs internet" else "offline"
        return "${v.locale.getDisplayName(Locale.ENGLISH)} · voice $short · $net · $q"
    }

    fun preferredEngine(context: Context, saved: String): String? {
        if (saved.isNotEmpty()) return saved
        val hasGoogle = try {
            context.packageManager.getPackageInfo(GOOGLE_TTS, 0); true
        } catch (e: Exception) { false }
        return if (hasGoogle) GOOGLE_TTS else null  // null = phone's default engine
    }
}

/** Speaks text through the car speakers, then calls [onDone] (always, even if speech fails). */
class Greeter(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var finished = false
    private var focus: AudioFocusRequest? = null

    fun speak(text: String, dutch: Boolean, pauseOthers: Boolean = false, onDone: () -> Unit) {
        val prefs = Prefs(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)  // plays through the car like nav prompts
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun finish(reason: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            DriveLog.log("GREETING: $reason")
            focus?.let { audio.abandonAudioFocusRequest(it) }
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            onDone()
        }

        // Never let the greeting block the music for long.
        handler.postDelayed({ finish("timed out - continuing") }, 60_000)

        val engineName = VoicePicker.preferredEngine(context, prefs.ttsEngine)
        val listener = TextToSpeech.OnInitListener { status ->
            handler.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) { finish("no text-to-speech engine available"); return@post }
                val locale = VoicePicker.localeFor(dutch)
                val voice = try { VoicePicker.best(engine.voices.orEmpty(), dutch, prefs.voiceName) } catch (e: Exception) { null }
                if (voice != null) {
                    engine.voice = voice
                    DriveLog.log("GREETING: voice ${voice.name} (engine ${engine.defaultEngine.takeIf { engineName == null } ?: engineName})")
                } else {
                    val res = engine.setLanguage(locale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        DriveLog.log("GREETING: no ${locale.displayLanguage} voice installed - using the phone's default voice")
                    }
                }
                engine.setAudioAttributes(attrs)
                engine.setSpeechRate(prefs.speechRate)
                engine.setPitch(prefs.speechPitch)

                val parts = SpeechText.sentences(text)
                val lastId = "s${parts.size - 1}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == lastId) handler.post { finish("spoken") } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { finish("speech failed") } }
                    override fun onError(id: String?, code: Int) { handler.post { finish("speech failed ($code)") } }
                })
                val gain = if (pauseOthers) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                focus = AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(attrs).build().also { audio.requestAudioFocus(it) }

                // One sentence at a time with a short natural pause in between.
                var ok = true
                parts.forEachIndexed { i, sentence ->
                    if (i > 0) engine.playSilentUtterance(250, TextToSpeech.QUEUE_ADD, "p$i")
                    if (engine.speak(sentence, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "s$i") != TextToSpeech.SUCCESS) ok = false
                }
                if (!ok || parts.isEmpty()) finish("speech could not start")
            }
        }
        tts = if (engineName != null) TextToSpeech(context.applicationContext, listener, engineName)
        else TextToSpeech(context.applicationContext, listener)
    }
}

/** Fetches the weather (if enabled), builds the welcome text and speaks it, then calls [then]. */
object WelcomeAnnouncer {
    fun run(context: Context, song: GreetingText.Song? = null, recordAsGreeted: Boolean = true, then: () -> Unit = {}) {
        val ctx = context.applicationContext
        val p = Prefs(ctx)
        val main = Handler(Looper.getMainLooper())
        DriveLog.log("GREETING: preparing")
        Thread {
            val lat = p.weatherLat
            val lon = p.weatherLon
            val weather = when {
                !p.weatherEnabled -> null
                lat.isNaN() || lon.isNaN() -> { DriveLog.log("GREETING: no weather location set"); null }
                else -> try {
                    Weather.fetch(lat, lon)
                } catch (e: Exception) {
                    DriveLog.log("GREETING: weather unavailable (${e.message})"); null
                }
            }
            if (weather != null) {
                p.lastWeatherSummary = GreetingText.tileWeather(weather, p.greetingDutch)
                p.lastWeatherAt = System.currentTimeMillis()
            }
            val text = GreetingText.build(
                p.namePronunciation.ifBlank { p.greetingName }, p.greetingDutch, Calendar.getInstance(), weather, p.weatherPlace,
                roads = p.roadConditionsEnabled, song = song.takeIf { p.announceSong }, birthday = p.birthday,
            )
            if (recordAsGreeted) p.lastGreetingAt = System.currentTimeMillis()
            // On screen: your name as written, not the phonetic spelling used for the voice.
            val pron = p.namePronunciation
            val shown = if (pron.isNotBlank() && p.greetingName.isNotBlank()) text.replace(pron, p.greetingName) else text
            main.post {
                DriveLog.log("GREETING: \"$text\"")
                val (title, body) = Briefing.split(shown)
                Briefing.start(ctx, title, body)
                Greeter(ctx).speak(text, p.greetingDutch, pauseOthers = true) {
                    Briefing.end(ctx)
                    then()
                }
            }
        }.start()
    }
}

/** Short "Now playing: X by Y" announcement (used when the song wasn't known before the music started). */
object SongAnnouncer {
    fun announce(context: Context, song: GreetingText.Song, dutch: Boolean) {
        val text = GreetingText.songSentence(song, dutch, upFirst = false)
        DriveLog.log("GREETING: \"$text\"")
        Greeter(context.applicationContext).speak(text, dutch) {}
    }
}
