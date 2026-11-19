package dev.wonderdesk.abacus

import org.junit.Assert.*
import org.junit.Test

class PiecewiseTest {
    private val latex = """
\[
\boxed{
P(x)=
\begin{cases}
0 & x<0.25\\
1 & 0.25\le x\le1\\
\left\lfloor1+499\left(\frac{x-1}{98}\right)^2\right\rfloor & x>1
\end{cases}
}
\]
"""

    @Test fun cases_become_a_piecewise_line() {
        val s = Smart.normalize(latex)
        assertTrue(s, s.startsWith("y ="))
        assertTrue(s, Piecewise.isPiecewise(s))
        assertFalse(s, s.contains("\\"))
    }

    @Test fun values_follow_the_pieces() {
        val f = Piecewise.function(Piecewise.parse(Smart.normalize(latex), false))
        assertEquals(0.0, f(0.0), 0.0)
        assertEquals(0.0, f(0.24), 0.0)
        assertEquals(1.0, f(0.25), 0.0)
        assertEquals(1.0, f(1.0), 0.0)
        assertEquals(1.0, f(2.0), 0.0)            // floor(1 + 499 * (1/98)^2) = 1
        assertEquals(Math.floor(1 + 499 * Math.pow(97.0 / 98, 2.0) ).toDouble() * 0 + Math.floor(1 + 499 * Math.pow(98.0 / 98, 2.0)), f(99.0), 0.0)
        assertEquals(500.0, f(99.0), 0.0)
    }

    @Test fun smart_run_gives_a_graph() {
        val r = Smart.run(latex, Task.Auto, false)
        assertEquals(Task.Graph, r.task)
        assertEquals(1, r.graphs.size)
        val shape = Plot.parse(r.graphs[0], emptyMap(), false)
        assertTrue(shape.toString(), shape is Plot.Shape.FnX)
        assertTrue(r.details.isNotEmpty())
    }

    @Test fun plain_latex_still_works() {
        val a = Smart.normalize("\\[ f(x) = x^2 \\]")
        assertEquals(a, "y = x^2", a)
        val c = Smart.normalize("x \\le 3")
        assertTrue(c, c.contains("<="))
    }

    @Test fun otherwise_and_words() {
        val s = Smart.normalize("f(x)=\\begin{cases} x^2 & x\\ge 0 \\\\ -x & \\text{otherwise} \\end{cases}")
        val f = Piecewise.function(Piecewise.parse(s, false))
        assertEquals(4.0, f(2.0), 1e-9)
        assertEquals(3.0, f(-3.0), 1e-9)
    }
}
