package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI

@RunWith(AndroidJUnit4::class)
class ExpressionDegreesTest {
    @Test fun degreesModeInterpretsTrigArgumentsAsDegrees() {
        assertEquals(1.0, Expression.evaluate("sin(90)", degrees = true), 1e-9)
        assertEquals(0.0, Expression.evaluate("cos(90)", degrees = true), 1e-9)
        assertEquals(1.0, Expression.evaluate("sin(pi/2)", degrees = false), 1e-9)
        assertEquals(1.0, Expression.evaluate("sin(90)", degrees = true), 1e-9)
        // Non-trig functions are unaffected by the mode.
        assertEquals(14.0, Expression.evaluate("2+3*4", degrees = true), 1e-10)
        assertEquals(PI, Expression.evaluate("pi", degrees = true), 1e-12)
    }
}
