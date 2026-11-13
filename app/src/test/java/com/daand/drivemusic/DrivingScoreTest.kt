package com.daand.drivemusic

import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.Trip
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driving score on real-life drives in the ASX 1.6 (5-speed: ~3,600 rpm at 130 km/h). It should
 * reward smooth driving and only mark down what the driver chooses - not traffic, the motorway, a
 * warm-up the app itself asks for, a glitchy reading, or whether the car tab happened to be open.
 */
class DrivingScoreTest {
    /** Plays a drive as a list of (seconds, speed km/h, rpm), sampled every [every] seconds. */
    private class Drive(private val every: Double = 5.0) {
        val trip = Trip()
        private var t = 0.0
        private var speed = 0.0
        private var coolant = 20
        fun hold(seconds: Int, kmh: Double, rpm: Int) = ramp(seconds, kmh, rpm)
        /** Change speed steadily to [to] over [seconds]. */
        fun ramp(seconds: Int, to: Double, rpm: Int): Drive {
            val from = speed
            var e = 0.0
            while (e < seconds) {
                e += every; t += every
                speed = from + (to - from) * minOf(1.0, e / seconds)
                if (coolant < 88) coolant++
                trip.add(CarSnapshot((t * 1000).toLong(), speedKmh = Math.round(speed).toInt(), rpm = if (speed < 1) 800 else rpm, coolantC = coolant))
            }
            return this
        }
        fun glitch(kmh: Int, rpm: Int) { t += every; trip.add(CarSnapshot((t * 1000).toLong(), speedKmh = kmh, rpm = rpm, coolantC = coolant)) }
        fun score() = trip.score()!!
    }

    /** A town stretch: away from the lights, cruise, normal stop (≈2.2 m/s² both ways), wait at the light. */
    private fun Drive.townBlock() = ramp(8, 50.0, 2500).hold(40, 50.0, 1900).ramp(7, 0.0, 1200).hold(35, 0.0, 800)

    @Test fun motorwayCruiseAt130IsSmooth() {
        val d = Drive().ramp(20, 100.0, 3000).hold(30 * 60, 130.0, 3650).ramp(30, 0.0, 1500)
        assertTrue("motorway ${d.score()}", d.score() >= 90)
    }

    @Test fun cityWithRedLightsIsSmooth() {
        val d = Drive()
        repeat(15) { d.townBlock() }
        assertTrue("city ${d.score()}", d.score() >= 85)
    }

    @Test fun theWarmUpTheAppAsksForIsNotMarkedDown() {
        val d = Drive().hold(60, 0.0, 800)   // 60 s idle after a cold start, as the butler suggests
        repeat(6) { d.townBlock() }
        assertTrue("warm-up ${d.score()}", d.score() >= 85)
    }

    @Test fun oneGarbledSpeedReadingIsNotAHardStop() {
        val d = Drive().ramp(15, 70.0, 2200).hold(120, 70.0, 2200)
        d.glitch(0, 2200)           // the adapter garbles one speed answer
        d.hold(5 * 60, 70.0, 2200).ramp(10, 0.0, 1200)
        assertTrue("glitch ${d.score()}", d.score() >= 90)
    }

    @Test fun theScoreDoesntDependOnTheCarTabBeingOpen() {
        fun drive(every: Double) = Drive(every).also { d -> repeat(10) { d.townBlock() }; d.ramp(6, 80.0, 4500).hold(60, 80.0, 2600).ramp(5, 0.0, 1500).hold(30, 0.0, 800) }.score()
        val closed = drive(5.0)   // normal polling
        val open = drive(1.5)     // Drive Mode car tab on screen: faster readings
        assertTrue("closed $closed vs open $open", Math.abs(closed - open) <= 6)
    }

    @Test fun aShortTripWithOneFirmStopIsStillGood() {
        val d = Drive().ramp(8, 50.0, 2500).hold(3 * 60, 50.0, 1900).ramp(4, 0.0, 1200).hold(20, 0.0, 800)
        assertTrue("short ${d.score()}", d.score() >= 65)
    }

    @Test fun reallyAggressiveDrivingScoresLow() {
        val d = Drive(2.0)
        repeat(12) {
            d.ramp(4, 60.0, 5800)            // 0-60 in 4 s at the limiter: ~4.2 m/s²
            d.hold(20, 60.0, 3000)
            d.ramp(3, 0.0, 1500)             // stamp on the brakes: ~5.6 m/s²
            d.hold(10, 0.0, 800)
        }
        assertTrue("aggressive ${d.score()}", d.score() <= 55)
    }

    @Test fun waitingWithTheEngineRunningCostsALittle() {
        val d = Drive()
        repeat(5) { d.townBlock() }
        d.hold(12 * 60, 0.0, 800)           // 12 minutes idling while parked
        repeat(5) { d.townBlock() }
        val s = d.score()
        assertTrue("idle-wait $s", s in 60..90)
    }
}
