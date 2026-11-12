package com.daand.drivemusic.obd

/**
 * Every standard OBD-II value (SAE J1979, mode 01) the app knows how to read and show. The car says
 * which ones it supports; only those are asked for. Pure decoding, unit tested.
 */
object Pids {
    enum class Group(val en: String, val nl: String) {
        ENGINE("Engine", "Motor"), AIR_FUEL("Air & fuel", "Lucht & brandstof"),
        ELECTRICAL("Electrical", "Elektrisch"), ENVIRONMENT("Environment", "Omgeving"),
        DISTANCE("Distance & time", "Afstand & tijd"),
    }

    class Info(
        val pid: Int, val en: String, val nl: String, val unit: String, val decimals: Int, val group: Group,
        /** Read every few seconds (needed live, e.g. for fuel consumption) instead of every 30 s. */
        val fast: Boolean = false,
        /** Values outside this are a garbled answer (cheap adapters send them) and are thrown away. */
        val plausible: ClosedFloatingPointRange<Double> = -1e9..1e9,
        val decode: (List<Int>) -> Double?,
    ) {
        /** Decoded and sane, or null. */
        fun read(d: List<Int>): Double? = decode(d)?.takeIf { it.isFinite() && it in plausible }
    }

    private fun a(d: List<Int>) = d.getOrNull(0)?.toDouble()
    private fun ab(d: List<Int>) = if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null
    private fun pct(d: List<Int>) = a(d)?.let { it * 100 / 255 }
    private fun trim(d: List<Int>) = a(d)?.let { (it - 128) * 100 / 128 }

    val ALL: List<Info> = listOf(
        Info(0x04, "Engine load", "Motorbelasting", "%", 0, Group.ENGINE, fast = true, plausible = 0.0..100.0) { pct(it) },
        Info(0x05, "Coolant temperature", "Koelvloeistof", "°C", 0, Group.ENGINE, fast = true, plausible = -40.0..150.0) { a(it)?.minus(40) },
        Info(0x0C, "RPM", "Toerental", "rpm", 0, Group.ENGINE, fast = true, plausible = 0.0..9000.0) { ab(it)?.div(4) },
        Info(0x0D, "Speed", "Snelheid", "km/h", 0, Group.ENGINE, fast = true, plausible = 0.0..250.0) { a(it) },
        Info(0x0E, "Ignition timing", "Ontstekingsvervroeging", "°", 1, Group.ENGINE, plausible = -64.0..64.0) { a(it)?.let { v -> v / 2 - 64 } },
        Info(0x11, "Throttle position", "Gasklepstand", "%", 0, Group.ENGINE, fast = true, plausible = 0.0..100.0) { pct(it) },
        Info(0x1F, "Running since start", "Draait sinds start", "s", 0, Group.ENGINE) { ab(it) },
        Info(0x43, "Absolute load", "Absolute belasting", "%", 0, Group.ENGINE, plausible = 0.0..400.0) { ab(it)?.let { v -> v * 100 / 255 } },
        Info(0x45, "Relative throttle", "Relatieve gasklep", "%", 0, Group.ENGINE, plausible = 0.0..100.0) { pct(it) },
        Info(0x49, "Accelerator pedal", "Gaspedaal", "%", 0, Group.ENGINE, plausible = 0.0..100.0) { pct(it) },
        Info(0x5C, "Oil temperature", "Olietemperatuur", "°C", 0, Group.ENGINE, plausible = -40.0..170.0) { a(it)?.minus(40) },

        Info(0x06, "Short-term fuel trim", "Korte brandstofcorrectie", "%", 1, Group.AIR_FUEL, plausible = -100.0..100.0) { trim(it) },
        Info(0x07, "Long-term fuel trim", "Lange brandstofcorrectie", "%", 1, Group.AIR_FUEL, plausible = -100.0..100.0) { trim(it) },
        Info(0x0A, "Fuel pressure", "Brandstofdruk", "kPa", 0, Group.AIR_FUEL) { a(it)?.times(3) },
        Info(0x0B, "Intake pressure (MAP)", "Inlaatdruk (MAP)", "kPa", 0, Group.AIR_FUEL, fast = true, plausible = 10.0..255.0) { a(it) },
        Info(0x0F, "Intake air temperature", "Inlaatluchttemperatuur", "°C", 0, Group.AIR_FUEL, fast = true, plausible = -40.0..120.0) { a(it)?.minus(40) },
        Info(0x10, "Air flow (MAF)", "Luchtmassa (MAF)", "g/s", 1, Group.AIR_FUEL, fast = true, plausible = 0.0..400.0) { ab(it)?.div(100) },
        Info(0x14, "O2 sensor 1 (front)", "Lambdasonde 1 (voor)", "V", 2, Group.AIR_FUEL, plausible = 0.0..1.3) { a(it)?.div(200) },
        Info(0x15, "O2 sensor 2 (rear)", "Lambdasonde 2 (achter)", "V", 2, Group.AIR_FUEL, plausible = 0.0..1.3) { a(it)?.div(200) },
        Info(0x2C, "EGR commanded", "EGR aangestuurd", "%", 0, Group.AIR_FUEL) { pct(it) },
        Info(0x2E, "Evap purge", "Dampspoeling", "%", 0, Group.AIR_FUEL) { pct(it) },
        Info(0x2F, "Fuel level", "Brandstofniveau", "%", 0, Group.AIR_FUEL, plausible = 0.0..100.0) { pct(it) },
        Info(0x3C, "Catalyst temperature", "Katalysatortemperatuur", "°C", 0, Group.AIR_FUEL, plausible = -40.0..1200.0) { ab(it)?.let { v -> v / 10 - 40 } },
        Info(0x44, "Air-fuel ratio (lambda)", "Lucht-brandstofverhouding (lambda)", "λ", 2, Group.AIR_FUEL, plausible = 0.0..2.0) { ab(it)?.div(32768) },
        Info(0x5E, "Fuel rate", "Brandstofverbruik", "L/h", 1, Group.AIR_FUEL, fast = true, plausible = 0.0..60.0) { ab(it)?.div(20) },

        Info(0x42, "System voltage", "Boordspanning", "V", 1, Group.ELECTRICAL, plausible = 5.0..20.0) { ab(it)?.div(1000) },

        Info(0x33, "Air pressure", "Luchtdruk", "kPa", 0, Group.ENVIRONMENT, plausible = 50.0..110.0) { a(it) },
        Info(0x46, "Outside temperature", "Buitentemperatuur", "°C", 0, Group.ENVIRONMENT, plausible = -40.0..60.0) { a(it)?.minus(40) },

        Info(0x21, "Distance with engine light on", "Afstand met storingslampje aan", "km", 0, Group.DISTANCE) { ab(it) },
        Info(0x31, "Distance since codes cleared", "Afstand sinds codes gewist", "km", 0, Group.DISTANCE) { ab(it) },
        Info(0x30, "Warm-ups since codes cleared", "Opwarmingen sinds codes gewist", "", 0, Group.DISTANCE) { a(it) },
        Info(0x4D, "Time with engine light on", "Tijd met storingslampje aan", "min", 0, Group.DISTANCE) { ab(it) },
        Info(0x4E, "Time since codes cleared", "Tijd sinds codes gewist", "min", 0, Group.DISTANCE) { ab(it) },
        Info(0xA6, "Odometer", "Kilometerstand", "km", 0, Group.DISTANCE) { d ->
            if (d.size >= 4) ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0 else null
        },
    )

    private val byPid = ALL.associateBy { it.pid }
    fun info(pid: Int) = byPid[pid]

    /** "92 °C", "14.1 V", "-3.1 %" - for the screen. */
    fun format(pid: Int, value: Double): String {
        val i = byPid[pid] ?: return "%.1f".format(java.util.Locale.ROOT, value)
        val num = if (i.decimals == 0) Math.round(value).toString() else "%.${i.decimals}f".format(java.util.Locale.ROOT, value)
        return if (i.unit.isEmpty()) num else "$num ${i.unit}"
    }

    /** Supported-PID blocks to ask for (each lists the next 32). */
    val SUPPORT_BLOCKS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)
}

/**
 * Derived values: fuel consumption from what the car reports. Prefers the car's own fuel rate (PID
 * 5E), else air flow (MAF) at the petrol air-fuel ratio, else an estimate from intake pressure (MAP),
 * intake temperature, RPM and engine size. Pure, unit tested.
 */
object FuelMath {
    // Dutch Euro95 is E10 (10% ethanol): a little less air per kg of fuel, a little denser than pure petrol.
    private const val PETROL_AFR = 14.1          // kg air per kg fuel (E10)
    private const val PETROL_DENSITY = 750.0     // g per litre (E10)
    private const val VOLUMETRIC_EFFICIENCY = 0.85

    /** Litres per hour right now, or null if the car doesn't give enough to work it out. */
    fun litresPerHour(extra: Map<Int, Double>, engineLitres: Double): Double? {
        extra[0x5E]?.let { return it }
        val maf = extra[0x10] ?: run {
            val map = extra[0x0B] ?: return null
            val iat = extra[0x0F] ?: return null
            val rpm = extra[0x0C] ?: return null
            // Speed-density: air mass per second = rpm/120 * P*V/(R*T) * VE.
            rpm / 120.0 * (map * 1000 * engineLitres / 1000) / (287.05 * (iat + 273.15)) * VOLUMETRIC_EFFICIENCY * 1000
        }
        return maf * 3600 / (PETROL_AFR * PETROL_DENSITY)
    }

    /** L/100 km when moving; null standing still (then L/h is the useful number). */
    fun per100km(litresPerHour: Double, speedKmh: Double?): Double? =
        speedKmh?.takeIf { it >= 5 }?.let { litresPerHour / it * 100 }
}
