package dev.wonderdesk.abacus

/** A unit expressed as base = (value + offset) * scale. */
data class UnitDef(val name: String, val symbol: String, val scale: Double, val offset: Double = 0.0)
data class UnitCategory(val name: String, val units: List<UnitDef>, val absoluteZero: Boolean = false)

object Units {
    val categories = listOf(
        UnitCategory("Length", listOf(
            UnitDef("Millimetre", "mm", 0.001), UnitDef("Centimetre", "cm", 0.01), UnitDef("Metre", "m", 1.0),
            UnitDef("Kilometre", "km", 1000.0), UnitDef("Inch", "in", 0.0254), UnitDef("Foot", "ft", 0.3048),
            UnitDef("Yard", "yd", 0.9144), UnitDef("Mile", "mi", 1609.344), UnitDef("Nautical mile", "nmi", 1852.0),
        )),
        UnitCategory("Area", listOf(
            UnitDef("Square metre", "m²", 1.0), UnitDef("Square kilometre", "km²", 1e6), UnitDef("Square centimetre", "cm²", 1e-4),
            UnitDef("Square foot", "ft²", 0.09290304), UnitDef("Square inch", "in²", 0.00064516), UnitDef("Square mile", "mi²", 2589988.110336),
            UnitDef("Acre", "ac", 4046.8564224), UnitDef("Hectare", "ha", 10000.0),
        )),
        UnitCategory("Volume", listOf(
            UnitDef("Millilitre", "mL", 1e-6), UnitDef("Litre", "L", 1e-3), UnitDef("Cubic metre", "m³", 1.0),
            UnitDef("Teaspoon (US)", "tsp", 4.92892159375e-6), UnitDef("Tablespoon (US)", "tbsp", 1.478676478125e-5),
            UnitDef("Fluid ounce (US)", "fl oz", 2.95735295625e-5), UnitDef("Cup (US)", "cup", 2.365882365e-4),
            UnitDef("Pint (US)", "pt", 4.73176473e-4), UnitDef("Quart (US)", "qt", 9.46352946e-4), UnitDef("Gallon (US)", "gal", 3.785411784e-3),
            UnitDef("Gallon (UK)", "imp gal", 4.54609e-3), UnitDef("Cubic foot", "ft³", 0.028316846592),
        )),
        UnitCategory("Mass", listOf(
            UnitDef("Milligram", "mg", 1e-6), UnitDef("Gram", "g", 1e-3), UnitDef("Kilogram", "kg", 1.0), UnitDef("Tonne", "t", 1000.0),
            UnitDef("Ounce", "oz", 0.028349523125), UnitDef("Pound", "lb", 0.45359237), UnitDef("Stone", "st", 6.35029318), UnitDef("Short ton (US)", "ton", 907.18474),
        )),
        UnitCategory("Temperature", listOf(
            UnitDef("Celsius", "°C", 1.0, 273.15), UnitDef("Fahrenheit", "°F", 5.0 / 9.0, 459.67), UnitDef("Kelvin", "K", 1.0),
        ), absoluteZero = true),
        UnitCategory("Speed", listOf(
            UnitDef("Metres per second", "m/s", 1.0), UnitDef("Kilometres per hour", "km/h", 1 / 3.6), UnitDef("Miles per hour", "mph", 0.44704),
            UnitDef("Knot", "kn", 1852.0 / 3600), UnitDef("Feet per second", "ft/s", 0.3048),
        )),
        UnitCategory("Time", listOf(
            UnitDef("Millisecond", "ms", 1e-3), UnitDef("Second", "s", 1.0), UnitDef("Minute", "min", 60.0), UnitDef("Hour", "h", 3600.0),
            UnitDef("Day", "d", 86400.0), UnitDef("Week", "wk", 604800.0), UnitDef("Year (365 d)", "yr", 31536000.0),
        )),
        UnitCategory("Data", listOf(
            UnitDef("Bit", "bit", 0.125), UnitDef("Byte", "B", 1.0), UnitDef("Kilobyte", "KB", 1e3), UnitDef("Megabyte", "MB", 1e6),
            UnitDef("Gigabyte", "GB", 1e9), UnitDef("Terabyte", "TB", 1e12), UnitDef("Kibibyte", "KiB", 1024.0),
            UnitDef("Mebibyte", "MiB", 1048576.0), UnitDef("Gibibyte", "GiB", 1073741824.0),
        )),
        UnitCategory("Energy", listOf(
            UnitDef("Joule", "J", 1.0), UnitDef("Kilojoule", "kJ", 1000.0), UnitDef("Calorie", "cal", 4.184), UnitDef("Kilocalorie", "kcal", 4184.0),
            UnitDef("Watt-hour", "Wh", 3600.0), UnitDef("Kilowatt-hour", "kWh", 3.6e6), UnitDef("BTU", "BTU", 1055.05585262),
        )),
        UnitCategory("Pressure", listOf(
            UnitDef("Pascal", "Pa", 1.0), UnitDef("Kilopascal", "kPa", 1000.0), UnitDef("Bar", "bar", 1e5), UnitDef("Atmosphere", "atm", 101325.0),
            UnitDef("PSI", "psi", 6894.757293168), UnitDef("mm of mercury", "mmHg", 133.322387415),
        )),
        UnitCategory("Angle", listOf(
            UnitDef("Degree", "°", Math.PI / 180), UnitDef("Radian", "rad", 1.0), UnitDef("Gradian", "grad", Math.PI / 200), UnitDef("Turn", "turn", 2 * Math.PI),
        )),
        UnitCategory("Fuel", listOf(
            UnitDef("Kilometres per litre", "km/L", 1.0), UnitDef("Miles per gallon (US)", "mpg", 0.425143707), UnitDef("Miles per gallon (UK)", "mpg UK", 0.354006189),
        )),
    )

    fun convert(value: Double, c: UnitCategory, from: UnitDef, to: UnitDef): Double {
        val base = (value + from.offset) * from.scale
        if (c.absoluteZero && base < -1e-9) throw MathError("That is below absolute zero")
        return base / to.scale - to.offset
    }
}
