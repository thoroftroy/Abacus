package dev.wonderdesk.shared

data class ConversionUnit(val name: String, val scale: Double, val offset: Double = 0.0)
data class ConversionCategory(val name: String, val units: List<ConversionUnit>, val nonNegativeBase: Boolean = false)

/** Factors convert to SI units; temperatures use absolute Kelvin as their base. */
object UnitConversion {
    val categories = listOf(
        ConversionCategory("Length", listOf(ConversionUnit("Metres", 1.0), ConversionUnit("Kilometres", 1000.0), ConversionUnit("Centimetres", 0.01), ConversionUnit("Millimetres", 0.001), ConversionUnit("Inches", 0.0254), ConversionUnit("Feet", 0.3048), ConversionUnit("Miles", 1609.344))),
        ConversionCategory("Mass", listOf(ConversionUnit("Kilograms", 1.0), ConversionUnit("Grams", 0.001), ConversionUnit("Milligrams", 0.000001), ConversionUnit("Pounds", 0.45359237), ConversionUnit("Ounces", 0.028349523125))),
        ConversionCategory("Temperature", listOf(ConversionUnit("Celsius", 1.0, 273.15), ConversionUnit("Fahrenheit", 5.0 / 9.0, 459.67), ConversionUnit("Kelvin", 1.0)), nonNegativeBase = true)
    )

    fun convert(value: Double, category: ConversionCategory, from: ConversionUnit, to: ConversionUnit): Double {
        require(value.isFinite()) { "Enter a finite number" }
        require(from in category.units && to in category.units) { "Select units from the same category" }
        val base = (value + from.offset) * from.scale
        require(base.isFinite()) { "Value is outside the finite range" }
        require(!category.nonNegativeBase || base >= -1e-10) { "Temperature cannot be below absolute zero" }
        val result = (if (category.nonNegativeBase && base < 0.0) 0.0 else base) / to.scale - to.offset
        require(result.isFinite()) { "Result is outside the finite range" }
        return result
    }

    /** Avoid exposing binary floating-point noise while retaining useful precision. */
    fun format(value: Double): String {
        require(value.isFinite()) { "Result is outside the finite range" }
        return java.math.BigDecimal.valueOf(value).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }
}
