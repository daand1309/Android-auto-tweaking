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
        val pid: Int,
        /** Data bytes in the answer (needed to split a combined answer for several values). */
        val len: Int,
        val en: String, val nl: String, val unit: String, val decimals: Int, val group: Group,
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
        Info(0x04, len = 1, "Engine load", "Motorbelasting", "%", 0, Group.ENGINE, fast = true, plausible = 0.0..100.0) { pct(it) },
        Info(0x05, len = 1, "Coolant temperature", "Koelvloeistof", "°C", 0, Group.ENGINE, fast = true, plausible = -40.0..150.0) { a(it)?.minus(40) },
        Info(0x0C, len = 2, "RPM", "Toerental", "rpm", 0, Group.ENGINE, fast = true, plausible = 0.0..9000.0) { ab(it)?.div(4) },
        Info(0x0D, len = 1, "Speed", "Snelheid", "km/h", 0, Group.ENGINE, fast = true, plausible = 0.0..250.0) { a(it) },
        Info(0x0E, len = 1, "Ignition timing", "Ontstekingsvervroeging", "°", 1, Group.ENGINE, plausible = -64.0..64.0) { a(it)?.let { v -> v / 2 - 64 } },
        Info(0x11, len = 1, "Throttle position", "Gasklepstand", "%", 0, Group.ENGINE, fast = true, plausible = 0.0..100.0) { pct(it) },
        Info(0x1F, len = 2, "Running since start", "Draait sinds start", "s", 0, Group.ENGINE) { ab(it) },
        Info(0x43, len = 2, "Absolute load", "Absolute belasting", "%", 0, Group.ENGINE, plausible = 0.0..400.0) { ab(it)?.let { v -> v * 100 / 255 } },
        Info(0x45, len = 1, "Relative throttle", "Relatieve gasklep", "%", 0, Group.ENGINE, plausible = 0.0..100.0) { pct(it) },
        Info(0x49, len = 1, "Accelerator pedal", "Gaspedaal", "%", 0, Group.ENGINE, plausible = 0.0..100.0) { pct(it) },
        Info(0x5C, len = 1, "Oil temperature", "Olietemperatuur", "°C", 0, Group.ENGINE, plausible = -40.0..170.0) { a(it)?.minus(40) },

        Info(0x06, len = 1, "Short-term fuel trim", "Korte brandstofcorrectie", "%", 1, Group.AIR_FUEL, plausible = -100.0..100.0) { trim(it) },
        Info(0x07, len = 1, "Long-term fuel trim", "Lange brandstofcorrectie", "%", 1, Group.AIR_FUEL, plausible = -100.0..100.0) { trim(it) },
        Info(0x0A, len = 1, "Fuel pressure", "Brandstofdruk", "kPa", 0, Group.AIR_FUEL) { a(it)?.times(3) },
        Info(0x0B, len = 1, "Intake pressure (MAP)", "Inlaatdruk (MAP)", "kPa", 0, Group.AIR_FUEL, fast = true, plausible = 10.0..255.0) { a(it) },
        Info(0x0F, len = 1, "Intake air temperature", "Inlaatluchttemperatuur", "°C", 0, Group.AIR_FUEL, fast = true, plausible = -40.0..120.0) { a(it)?.minus(40) },
        Info(0x10, len = 2, "Air flow (MAF)", "Luchtmassa (MAF)", "g/s", 1, Group.AIR_FUEL, fast = true, plausible = 0.0..400.0) { ab(it)?.div(100) },
        Info(0x14, len = 2, "O2 sensor 1 (front)", "Lambdasonde 1 (voor)", "V", 2, Group.AIR_FUEL, plausible = 0.0..1.3) { a(it)?.div(200) },
        Info(0x15, len = 2, "O2 sensor 2 (rear)", "Lambdasonde 2 (achter)", "V", 2, Group.AIR_FUEL, plausible = 0.0..1.3) { a(it)?.div(200) },
        Info(0x2C, len = 1, "EGR commanded", "EGR aangestuurd", "%", 0, Group.AIR_FUEL) { pct(it) },
        Info(0x2E, len = 1, "Evap purge", "Dampspoeling", "%", 0, Group.AIR_FUEL) { pct(it) },
        Info(0x2F, len = 1, "Fuel level", "Brandstofniveau", "%", 0, Group.AIR_FUEL, plausible = 0.0..100.0) { pct(it) },
        Info(0x3C, len = 2, "Catalyst temperature", "Katalysatortemperatuur", "°C", 0, Group.AIR_FUEL, plausible = -40.0..1200.0) { ab(it)?.let { v -> v / 10 - 40 } },
        Info(0x44, len = 2, "Air-fuel ratio (lambda)", "Lucht-brandstofverhouding (lambda)", "λ", 2, Group.AIR_FUEL, plausible = 0.0..2.0) { ab(it)?.div(32768) },
        Info(0x5E, len = 2, "Fuel rate", "Brandstofverbruik", "L/h", 1, Group.AIR_FUEL, fast = true, plausible = 0.0..60.0) { ab(it)?.div(20) },

        Info(0x42, len = 2, "System voltage", "Boordspanning", "V", 1, Group.ELECTRICAL, plausible = 5.0..20.0) { ab(it)?.div(1000) },

        Info(0x33, len = 1, "Air pressure", "Luchtdruk", "kPa", 0, Group.ENVIRONMENT, plausible = 50.0..110.0) { a(it) },
        Info(0x46, len = 1, "Outside temperature", "Buitentemperatuur", "°C", 0, Group.ENVIRONMENT, plausible = -40.0..60.0) { a(it)?.minus(40) },

        Info(0x21, len = 2, "Distance with engine light on", "Afstand met storingslampje aan", "km", 0, Group.DISTANCE) { ab(it) },
        Info(0x31, len = 2, "Distance since codes cleared", "Afstand sinds codes gewist", "km", 0, Group.DISTANCE) { ab(it) },
        Info(0x30, len = 1, "Warm-ups since codes cleared", "Opwarmingen sinds codes gewist", "", 0, Group.DISTANCE) { a(it) },
        Info(0x4D, len = 2, "Time with engine light on", "Tijd met storingslampje aan", "min", 0, Group.DISTANCE) { ab(it) },
        Info(0x4E, len = 2, "Time since codes cleared", "Tijd sinds codes gewist", "min", 0, Group.DISTANCE) { ab(it) },
        Info(0xA6, len = 4, "Odometer", "Kilometerstand", "km", 0, Group.DISTANCE) { d ->
            if (d.size >= 4) ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0 else null
        },
    )

    private val byPid = ALL.associateBy { it.pid }
    fun info(pid: Int) = byPid[pid]

    /** Answer length for any value we may ask for (incl. status 01 and run time). */
    fun lengthOf(pid: Int): Int? = byPid[pid]?.len ?: when (pid) { 0x01 -> 4; 0x1F -> 2; else -> null }

    /** Values the app reads or uses on purpose (others the car supports get logged for later). */
    val KNOWN: Set<Int> by lazy { ALL.map { it.pid }.toSet() + setOf(0x01, 0x1F) + SUPPORT_BLOCKS_SET }

    private val SUPPORT_BLOCKS_SET get() = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

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
    fun litresPerHour(extra: Map<Int, Double>, engineLitres: Double): Double? =
        rawLitresPerHour(extra, engineLitres)?.takeIf { it.isFinite() && it >= 0 && it <= maxLitresPerHour(engineLitres) }

    /**
     * Even flat out an engine can't burn more than roughly 12 L/h per litre of engine size (a 1.6
     * at full throttle: ~15-20 L/h). Above that the inputs were garbled: unknown, not a huge number
     * that would wreck the trip's fuel, costs and range.
     */
    fun maxLitresPerHour(engineLitres: Double) = 15.0 * engineLitres.coerceIn(0.8, 6.0)

    private fun rawLitresPerHour(extra: Map<Int, Double>, engineLitres: Double): Double? {
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
