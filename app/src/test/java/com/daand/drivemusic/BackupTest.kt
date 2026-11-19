package com.daand.drivemusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun backupRestoresEverySettingWithItsType() {
        val p = Prefs(ctx)
        p.greetingName = "Mr. Smith"
        p.greetingDutch = true
        p.resumeDelaySec = 14
        p.speechRate = 1.15f
        p.weatherLat = 52.1
        p.lastDrive = "should not be backed up"
        val json = p.exportJson()
        assertFalse("run-time state must not be in the backup", json.contains("should not be backed up"))

        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("", Prefs(ctx).greetingName)

        val restored = Prefs(ctx).importJson(json)
        assertTrue(restored >= 5)
        val r = Prefs(ctx)
        assertEquals("Mr. Smith", r.greetingName)
        assertEquals(true, r.greetingDutch)
        assertEquals(14, r.resumeDelaySec)
        assertEquals(1.15f, r.speechRate, 0.0001f)
        assertEquals(52.1, r.weatherLat, 0.001)
        assertEquals("", r.lastDrive)
    }

    /** The fuel settings added in v1.145 were exported but never restored. */
    @Test fun fuelChoicesSurviveABackup() {
        val p = Prefs(ctx)
        p.fuelPriceAuto = false
        p.fuelType = FuelPrices.Fuel.DIESEL.code
        p.fuelPrice = 2.05
        val json = p.exportJson()

        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(ctx).importJson(json)
        val r = Prefs(ctx)
        assertFalse("automatic lookup was switched off, so it must stay off", r.fuelPriceAuto)
        assertEquals(FuelPrices.Fuel.DIESEL.code, r.fuelType)
        assertEquals(2.05, r.fuelPrice, 0.001)
    }

    /** A price quoted for the old phone's area is not a fact about the new one. */
    @Test fun aCachedFuelPriceIsNotRestored() {
        val p = Prefs(ctx)
        p.fuelPriceQuoted = 9.99
        p.fuelPriceQuoteAt = 1_000L
        p.adapterPromptPending = true
        val json = p.exportJson()

        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(ctx).importJson(json)
        val r = Prefs(ctx)
        assertEquals(0.0, r.fuelPriceQuoted, 0.0)
        assertEquals(0L, r.fuelPriceQuoteAt)
        assertFalse(r.adapterPromptPending)
    }

    @Test fun damagedBackupCannotCrashTheApp() {
        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        val json = """{"app":"drive-music","version":1,"settings":{
            "auto_start":{"t":"string","v":"oops"},
            "start_delay":{"t":"int","v":99999},
            "speech_rate":{"t":"float","v":50},
            "greeting":{"t":"bool","v":"not a bool"},
            "last_drive":{"t":"string","v":"runtime"},
            "something_new":{"t":"bool","v":true},
            "car_name":{"t":"string","v":"ASX"}
        }}"""
        Prefs(ctx).importJson(json)
        val r = Prefs(ctx)
        assertEquals(true, r.autoStart)           // wrong type ignored -> still the default, no crash
        assertEquals(30, r.startDelaySec)         // absurd number clamped to the maximum
        assertEquals(1.4f, r.speechRate, 0.0001f) // clamped
        assertEquals("", r.lastDrive)             // run-time state never restored
        assertEquals("ASX", r.carName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesThatAreNotABackup() {
        Prefs(ctx).importJson("""{"something":"else"}""")
    }

    /** Red team, 19 Nov: plate, home/work and favourites' numbers went into backup files (and weren't even restored). */
    @Test fun personalDataStaysOutOfBackupsAndRadioStationsComeBack() {
        val p = Prefs(ctx)
        p.plate = "GZR90H"
        p.placeHome = Shortcuts.Place("Home", 52.24, 4.44).encode()
        p.favourites = Shortcuts.encodeFavourites(listOf(Shortcuts.Favourite("Mum", "0612345678")))
        p.fuelLog = "1|20|90|44.1|2.070"
        val stations = (1..20).map { Radio.Station("Station number $it", "https://stream.example.org/live/station-$it/high-quality.mp3") }
        p.radioStations = Radio.encode(stations)
        val json = p.exportJson()
        for (secret in listOf("GZR90H", "52.24", "0612345678", "44.1")) assertFalse("'$secret' must not be in a backup", json.contains(secret))

        ctx.getSharedPreferences("drive_music", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(ctx).importJson(json)
        assertEquals("all 20 stations back (a long string)", stations, Radio.decode(Prefs(ctx).radioStations))
    }
}
