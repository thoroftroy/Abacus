package dev.wonderdesk.abacus

import org.junit.Assert.assertEquals
import org.junit.Test

class MathEngineTest {
    private fun ev(s: String, deg: Boolean = false) = Evaluator(deg).eval(MathParser.expression(s))

    @Test fun arithmetic() {
        assertEquals(14.0, ev("2+3*4"), 1e-12)
        assertEquals(-9.0, ev("-3^2"), 1e-12)
        assertEquals(512.0, ev("2^3^2"), 1e-12)
        assertEquals(6.0, ev("2(3)"), 1e-12)
        assertEquals(120.0, ev("5!"), 1e-12)
        assertEquals(0.5, ev("50%"), 1e-12)
        assertEquals(1.0, ev("sin(90)", true), 1e-12)
        assertEquals(10.0, ev("nCr(5,2)"), 1e-12)
        assertEquals(3.0, ev("√9"), 1e-12)
        assertEquals(-2.0, ev("(-8)^(1/3)"), 1e-12)
        assertEquals(2.0 * Math.PI, ev("2pi"), 1e-12)
        assertEquals(4.0, ev("log(16,2)"), 1e-12)
        assertEquals(6.0, ev("gcd(12,18)"), 1e-12)
        assertEquals(Math.sqrt(Math.PI), ev("(-0.5)!") , 1e-9)
    }

    @Test fun expand() {
        assertEquals("x² + 2xy + y²", Simplify.pretty(MathParser.expression("(x+y)^2")))
        assertEquals("x² - 5x + 6", Simplify.pretty(MathParser.expression("(x-2)(x-3)")))
        assertEquals("6x", Simplify.pretty(MathParser.expression("2x+4x")))
        assertEquals("x³ + 3x² + 3x + 1", Simplify.pretty(MathParser.expression("(x+1)^3")))
    }

    @Test fun solve() {
        assertEquals(listOf(2.0, 3.0), Solver.solve("x^2-5x+6=0").roots)
        assertEquals(listOf(4.0), Solver.solve("2x+3=11").roots)
        println(Solver.solve("x^2-2=0").headline)
        println(Solver.solve("x^2+x-1=0").lines)
        println(Solver.solve("x^2+1=0").lines)
        println(Solver.solve("x^3-6x^2+11x-6=0").headline)
        println(Solver.solve("sin(x)=0.5").lines)
        println(Solver.solve("2^x=8").headline)
        println(Solver.solve("2x+y=5; x-y=1").headline)
        println(Solver.solve("x+y+z=6; 2y+5z=-4; 2x+5y-z=27").headline)
        println(Solver.solve("a*x+b=c").headline)
        println(Solver.solve("x/2 + 1/3 = 1").headline)
    }

    @Test fun calculus() {
        println("D: " + Simplify.pretty(Calculus.derivative(MathParser.expression("x^3+2x"), "x")))
        println("D: " + Simplify.pretty(Calculus.derivative(MathParser.expression("sin(x^2)"), "x")))
        println("D: " + Simplify.pretty(Calculus.derivative(MathParser.expression("x*ln(x)"), "x")))
        println("D: " + Simplify.pretty(Calculus.derivative(MathParser.expression("e^(2x)"), "x")))
        println(Calculus.polyIntegral(MathParser.expression("3x^2+2x+1"), "x"))
        assertEquals(2.0, Calculus.integrate({ Math.sin(it) }, 0.0, Math.PI), 1e-9)
    }

    @Test fun factor() {
        listOf("x^2-5x+6", "2x^2-8", "x^3-x", "6x^2+x-2", "x^2+1", "x^4-1", "x^2/2-2", "x^2-4x+4", "3x^2", "6x^2y+9xy^2").forEach {
            val p = Poly.from(MathParser.expression(it))!!
            println("$it -> " + Factor.factor(p, "x"))
        }
    }
}
