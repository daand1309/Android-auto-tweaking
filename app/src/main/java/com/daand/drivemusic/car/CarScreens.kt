package com.daand.drivemusic.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Template
import com.daand.drivemusic.DriveLog
import com.daand.drivemusic.DriveMonitorService
import com.daand.drivemusic.FuelPrices
import com.daand.drivemusic.FuelStops
import com.daand.drivemusic.Here
import com.daand.drivemusic.Prefs
import com.daand.drivemusic.ResumeToggle
import com.daand.drivemusic.Shortcuts
import com.daand.drivemusic.car.CarTemplate.Item

/*
 * The car screen's sub-screens: everything you'd otherwise pick up the phone for. Each tap either
 * acts at once or hands over to Android Auto (Waze, the car's phone screen) - never to the phone.
 */

private fun CarContext.toast(text: String) = runCatching { CarToast.makeText(this, text, CarToast.LENGTH_LONG).show() }

/** Nearest good price / cheapest around you (same rules as Drive Mode): one tap starts Waze on the car screen. */
class FuelScreen(c: CarContext) : Screen(c) {
    private var items: List<Item>? = null

    init {
        val fuel = FuelPrices.Fuel.of(Prefs(c).fuelType)
        Thread({
            val here = Here.position(c)
            val result: List<Item> = when {
                here == null -> listOf(Item("Position unknown", "Allow location for Drive Music on the phone"))
                else -> try {
                    val choice = FuelStops.choose(FuelPrices.stationsAround(here.first, here.second), here.first, here.second, fuel)
                    if (choice == null) listOf(Item("No ${fuel.en} station within 15 km"))
                    else listOfNotNull(
                        row(if (choice.nearestGood === choice.cheapest) "Best: nearest and cheapest" else "Nearest good price", choice.nearestGood),
                        if (choice.nearestGood === choice.cheapest) null else row("Cheapest", choice.cheapest),
                    )
                } catch (e: Exception) {
                    DriveLog.log("FUEL: station lookup failed on the car screen (${e.javaClass.simpleName}: ${e.message})")
                    listOf(Item("Couldn't get the prices", "No signal? Try again in a moment"))
                }
            }
            Handler(Looper.getMainLooper()).post { items = result; invalidate() }
        }, "car-fuel").start()
    }

    private fun row(why: String, s: FuelStops.Stop) = Item(why,
        "%s · €%.3f · %.1f km".format(java.util.Locale.ROOT, s.station.name, s.price, s.km)) {
        if (CarLink.navigate(s.station.lat, s.station.lon, carContext)) {
            DriveLog.log("FUEL: navigating to ${s.station.name} on the car screen (car screen, $why, €%.3f, %.1f km)".format(java.util.Locale.ROOT, s.price, s.km))
        } else carContext.toast("Couldn't start navigation")
    }

    override fun onGetTemplate(): Template = CarTemplate.list("Find fuel", items.orEmpty(), loading = items == null)
}

/** Home and Work (set on the phone, Car screen page): one tap starts Waze on the car screen. */
class PlacesScreen(c: CarContext) : Screen(c) {
    override fun onGetTemplate(): Template {
        val p = Prefs(carContext)
        val items = listOf("Home" to p.placeHome, "Work" to p.placeWork).map { (label, enc) ->
            val place = Shortcuts.decodePlace(enc)
            if (place == null) Item("$label - not set", "Set it in Drive Music on the phone: Car screen page")
            else Item(if (label == "Home") "🏠  Home" else "💼  Work", place.name.takeIf { it != label } ?: "") {
                if (CarLink.navigate(place.lat, place.lon, carContext)) DriveLog.log("NAVIGATE: to $label on the car screen")
                else carContext.toast("Couldn't start navigation")
            }
        }
        return CarTemplate.list("Navigate", items)
    }
}

/** Up to three favourite contacts (picked on the phone): one tap calls through the car. */
class CallScreen(c: CarContext) : Screen(c) {
    override fun onGetTemplate(): Template {
        val favs = Shortcuts.decodeFavourites(Prefs(carContext).favourites)
        val items = if (favs.isEmpty()) listOf(Item("No favourites yet", "Pick up to 3 in Drive Music on the phone: Car screen page"))
        else favs.mapIndexed { i, f ->
            Item("📞  ${f.name}", "") {
                // The name and number are personal: the log only says which slot.
                if (CarLink.call(f.number, carContext)) DriveLog.log("CALL: favourite ${i + 1} from the car screen")
                else carContext.toast("Couldn't start the call")
            }
        }
        return CarTemplate.list("Call", items)
    }
}

/** What's used less often: previous, like / dislike, auto-resume, and the weather or status. */
class MoreScreen(c: CarContext, private val info: () -> Pair<String, String>) : Screen(c) {
    override fun onGetTemplate(): Template {
        val svc = DriveMonitorService.instance
        val (infoTitle, infoText) = info()
        val resumeOn = ResumeToggle.isOnNow(carContext)
        fun rate(up: Boolean) {
            val ok = svc?.rate(up, "Car screen") == true
            carContext.toast(when {
                !ok -> "YouTube Music didn't accept it"
                up -> "Liked"
                else -> "Disliked - skipping"
            })
        }
        return CarTemplate.list("More", listOf(
            Item("⏮  Previous") { svc?.skipPrevious() },
            Item("👍  Like this song") { rate(true) },
            Item("👎  Dislike and skip") { rate(false) },
            Item(if (resumeOn) "↻  Auto-resume: On" else "↻  Auto-resume: Off", "Tap to switch (this drive only)") {
                ResumeToggle.tapNow(carContext, "Car screen"); invalidate()
            },
            Item(infoTitle, infoText),
        ))
    }
}
