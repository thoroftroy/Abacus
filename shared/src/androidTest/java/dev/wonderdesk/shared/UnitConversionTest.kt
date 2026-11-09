package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnitConversionTest {
    @Test fun standardLengthAndMassFactors() {
        val length = UnitConversion.categories.first { it.name == "Length" }
        val mass = UnitConversion.categories.first { it.name == "Mass" }
        assertEquals(1609.344, UnitConversion.convert(1.0, length, length.units.first { it.name == "Miles" }, length.units.first { it.name == "Metres" }), 1e-9)
        assertEquals(2.54, UnitConversion.convert(1.0, length, length.units.first { it.name == "Inches" }, length.units.first { it.name == "Centimetres" }), 1e-9)
        assertEquals(0.45359237, UnitConversion.convert(1.0, mass, mass.units.first { it.name == "Pounds" }, mass.units.first { it.name == "Kilograms" }), 1e-9)
    }

    @Test fun temperatureAndInvalidInputs() {
        val category = UnitConversion.categories.first { it.name == "Temperature" }
        val c = category.units.first { it.name == "Celsius" }
        val f = category.units.first { it.name == "Fahrenheit" }
        val k = category.units.first { it.name == "Kelvin" }
        assertEquals(32.0, UnitConversion.convert(0.0, category, c, f), 1e-9)
        assertEquals("32", UnitConversion.format(UnitConversion.convert(0.0, category, c, f)))
        assertEquals(100.0, UnitConversion.convert(212.0, category, f, c), 1e-9)
        assertEquals(0.0, UnitConversion.convert(-273.15, category, c, k), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { UnitConversion.convert(-274.0, category, c, k) }
        assertThrows(IllegalArgumentException::class.java) { UnitConversion.convert(Double.NaN, category, c, k) }
        assertThrows(IllegalArgumentException::class.java) { UnitConversion.convert(1.0, category, UnitConversion.categories.first().units.first(), k) }
    }
}
