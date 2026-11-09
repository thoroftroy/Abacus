package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WonderMotionTest {
    @Test fun burstsCreateThemeAppropriateParticles() {
        val superField = WonderMotionField(1L)
        superField.burst(10f, 10f, WonderLook.SUPER, true)
        assertEquals(3, superField.rings.size)
        assertTrue(superField.sparks.isNotEmpty())

        val aquaField = WonderMotionField(1L)
        aquaField.burst(10f, 10f, WonderLook.AQUA, true)
        assertEquals(2, aquaField.rings.size)
        assertTrue(aquaField.sparks.isEmpty())

        val blissField = WonderMotionField(1L)
        blissField.burst(10f, 10f, WonderLook.BLISS, true)
        assertTrue(blissField.rings.isEmpty())
        assertEquals(8, blissField.sparks.size)
    }

    @Test fun stepAgesAndRemovesParticles() {
        val field = WonderMotionField(1L)
        field.burst(10f, 10f, WonderLook.SUPER, true)
        assertTrue(field.sparks.isNotEmpty())
        repeat(40) { field.step(0.05f, 320f, 640f, false) }
        assertTrue(field.sparks.isEmpty())
        assertTrue(field.rings.isEmpty())
    }

    @Test fun ambientSeedsOnlyWhenRequested() {
        val field = WonderMotionField(1L)
        field.step(0.05f, 320f, 640f, false)
        assertTrue(field.sparks.none { it.ambient })
        field.step(0.05f, 320f, 640f, true)
        assertTrue(field.sparks.any { it.ambient })
    }
}
