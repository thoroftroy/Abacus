package dev.wonderdesk.abacus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    private fun run(s: String, t: Task = Task.Auto) = Smart.run(s, t, false)

    @Test fun pastedUnicode() {
        assertEquals("x = 4", run("2x − 3 = 5").answer)
        assertTrue(run("x² = 9").answer.let { it.contains("3") && it.contains("-3") || it.contains("±3") })
        assertEquals("12", run("3 × 4").answer)
        assertEquals("0.5", run("1 ÷ 2").answer)
    }

    @Test fun latex() {
        assertEquals("x = 6", run("\\frac{x}{2} + 1 = 4").answer)
        assertEquals("3", run("\\sqrt{9}").answer)
    }

    @Test fun words() {
        assertEquals("12", run("15% of 80").answer)
        assertEquals("25", run("5 squared").answer)
        assertEquals("12", run("what is 3 times 4?").answer)
    }

    @Test fun intents() {
        assertEquals(Task.Graph, run("y = x^2 - 4").task)
        assertEquals(Task.Solve, run("x^2 - 5x + 6 = 0").task)
        assertEquals(Task.Derive, run("derivative of x^3").task)
        assertTrue(run("derivative of x^3").answer.contains("3x²"))
        assertEquals(Task.Integrate, run("integrate x^2 from 0 to 3").task)
        assertEquals("9", run("integrate x^2 from 0 to 3").answer)
        assertEquals(Task.Factor, run("factor x^2 - 4").task)
        assertTrue(run("2x + y = 5; x - y = 1").answer.contains("x = 2"))
        assertTrue(run("f(x) = 2x + 1").graphs.isNotEmpty())
    }

    @Test fun arithmeticWork() {
        val steps = run("2 + 3 * (4 - 1)^2").steps
        assertTrue(steps.toString(), steps.size >= 4)
        assertEquals("= 29", steps.last())
    }

    @Test fun compiledMatchesEvaluator() {
        val n = MathParser.expression("sin(x)^2 + sqrt(x) / (x - 1)")
        val f = compileFn(n, "x", false)
        for (x in listOf(0.5, 2.0, 3.7)) assertEquals(Evaluator(false, mapOf("x" to x)).eval(n), f(x), 1e-12)
        assertTrue(f(-1.0).isNaN())
    }
}
