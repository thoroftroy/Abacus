package dev.wonderdesk.abacus

import kotlin.math.*

/**
 * Graphing engine: turns each line the user types into something drawable.
 *
 * Understands, like a graphing calculator:
 *  - y = f(x) (or just f(x)), x = g(y)
 *  - r = f(θ) polar curves (θ, t or "theta")
 *  - (f(t), g(t)) parametric curves, and (a, b) points
 *  - any equation in x and y, e.g. x² + y² = 25 (drawn implicitly)
 *  - inequalities, e.g. y < x², x² + y² ≤ 9 (shaded)
 *  - a = 3 style definitions, which become sliders; letters used but not defined get sliders too
 */
object Plot {
    enum class Op { EQ, LT, LE, GT, GE }

    sealed class Shape {
        data class FnX(val f: (Double) -> Double) : Shape()                 // y = f(x)
        data class FnY(val f: (Double) -> Double) : Shape()                 // x = f(y)
        data class Polar(val r: (Double) -> Double, val from: Double, val to: Double) : Shape()
        data class Param(val x: (Double) -> Double, val y: (Double) -> Double, val from: Double, val to: Double) : Shape()
        data class Points(val pts: List<Pair<Double, Double>>) : Shape()
        /** f(x, y) compared with 0 using [op]. */
        data class Relation(val f: (Double, Double) -> Double, val op: Op) : Shape()
        data class Define(val name: String, val value: Double) : Shape()
        data class Fail(val message: String) : Shape()
        data object Empty : Shape()
    }

    private val RESERVED = setOf("x", "y", "t", "r", "θ", "theta", "e", "π", "pi", "tau", "phi", "ans")

    fun cleaned(raw: String) = Smart.normalize(raw).replace("theta", "θ").replace("≤", "<=").replace("≥", ">=")

    /** Splits at the top-level comparison, if any. */
    private fun splitRelation(s: String): Triple<String, Op, String>? {
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '(') depth++ else if (c == ')') depth--
            if (depth == 0) {
                val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                when {
                    two == "<=" -> return Triple(s.substring(0, i), Op.LE, s.substring(i + 2))
                    two == ">=" -> return Triple(s.substring(0, i), Op.GE, s.substring(i + 2))
                    c == '<' -> return Triple(s.substring(0, i), Op.LT, s.substring(i + 1))
                    c == '>' -> return Triple(s.substring(0, i), Op.GT, s.substring(i + 1))
                    c == '=' -> return Triple(s.substring(0, i), Op.EQ, s.substring(i + 1))
                }
            }
            i++
        }
        return null
    }

    private fun topLevelCommas(s: String): List<String> {
        val out = mutableListOf<String>(); var depth = 0; var start = 0
        s.forEachIndexed { i, c -> if (c == '(') depth++ else if (c == ')') depth-- else if (c == ',' && depth == 0) { out += s.substring(start, i); start = i + 1 } }
        out += s.substring(start); return out.map { it.trim() }
    }

    /** Free letters (would-be sliders) used in [line]. */
    fun parameters(line: String): Set<String> = runCatching {
        val s = cleaned(line)
        val parts = splitRelation(s)?.let { listOf(it.first, it.third) } ?: listOf(s)
        parts.flatMap { p -> topLevelCommas(p.trim().removeSurrounding("(", ")")).flatMap { variables(MathParser.expression(it)) } }.filter { it !in RESERVED }.toSet()
    }.getOrDefault(emptySet())

    /** Parses one line. [params] holds slider values. */
    fun parse(line: String, params: Map<String, Double>, degrees: Boolean): Shape {
        val s = cleaned(line).trim()
        if (s.isEmpty()) return Shape.Empty
        return runCatching {
            val rel = splitRelation(s)
            if (rel == null) {
                // bare expression, point list or parametric tuple
                if (s.startsWith("(")) {
                    val items = Regex("\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)").findAll(s).map { it.groupValues[1] }.toList()
                    val tuples = items.map { topLevelCommas(it) }.filter { it.size == 2 }
                    if (tuples.isNotEmpty()) {
                        val usesT = tuples.any { tp -> tp.any { "t" in variables(MathParser.expression(it)) } }
                        if (usesT && tuples.size == 1) {
                            val fx = compile2(MathParser.expression(tuples[0][0]), params, degrees); val fy = compile2(MathParser.expression(tuples[0][1]), params, degrees)
                            return Shape.Param({ t -> fx(0.0, 0.0, t) }, { t -> fy(0.0, 0.0, t) }, 0.0, 2 * PI)
                        }
                        return Shape.Points(tuples.map { (a, b) -> Evaluator(degrees, params).eval(MathParser.expression(a)) to Evaluator(degrees, params).eval(MathParser.expression(b)) })
                    }
                }
                val n = MathParser.expression(s)
                val vars = variables(n) - RESERVED.minus(setOf("x", "y", "t", "θ")) - params.keys
                return when {
                    "y" in vars && "x" !in vars -> { val f = compile2(n, params, degrees); Shape.FnY { y -> f(0.0, y, 0.0) } }
                    "θ" in vars -> { val f = compile2(n, params, degrees); Shape.Polar({ th -> f(0.0, 0.0, th) }, 0.0, 2 * PI) }
                    else -> { val f = compile2(n, params, degrees); Shape.FnX { x -> f(x, 0.0, 0.0) } }
                }
            }
            val (l, op, r) = rel
            val ln = MathParser.expression(l.trim()); val rn = MathParser.expression(r.trim())
            val lv = variables(ln); val rv = variables(rn)
            // slider definition: a = 3
            if (op == Op.EQ && ln is Node.Var && ln.name !in RESERVED && rv.all { it in params.keys || it in CONSTANTS }) return Shape.Define(ln.name, Evaluator(degrees, params).eval(rn))
            if (op == Op.EQ && ln is Node.Var && ln.name == "y" && "y" !in rv) { val f = compile2(rn, params, degrees); return Shape.FnX { x -> f(x, 0.0, 0.0) } }
            if (op == Op.EQ && rn is Node.Var && rn.name == "y" && "y" !in lv) { val f = compile2(ln, params, degrees); return Shape.FnX { x -> f(x, 0.0, 0.0) } }
            if (op == Op.EQ && ln is Node.Var && ln.name == "x" && "x" !in rv) { val f = compile2(rn, params, degrees); return Shape.FnY { y -> f(0.0, y, 0.0) } }
            if (op == Op.EQ && ln is Node.Var && ln.name == "r") { val f = compile2(rn, params, degrees); return Shape.Polar({ th -> f(0.0, 0.0, th) }, 0.0, 2 * PI) }
            val diff = compile2(Node.Sub(ln, rn), params, degrees)
            Shape.Relation({ x, y -> diff(x, y, 0.0) }, op)
        }.getOrElse { Shape.Fail((it as? MathError)?.message ?: "Can't read this yet") }
    }

    /** Compiles with x, y and t/θ as inputs; sliders are baked in as constants. */
    fun compile2(n: Node, params: Map<String, Double>, degrees: Boolean): (Double, Double, Double) -> Double {
        val toRad = if (degrees) PI / 180 else 1.0
        val fromRad = if (degrees) 180 / PI else 1.0
        fun c(n: Node): (Double, Double, Double) -> Double = when (n) {
            is Node.Num -> { val k = n.v; { _, _, _ -> k } }
            is Node.Var -> when (n.name) {
                "x" -> { x, _, _ -> x }; "y" -> { _, y, _ -> y }; "t", "θ" -> { _, _, t -> t }
                else -> { val k = params[n.name] ?: CONSTANTS[n.name] ?: Double.NaN; { _, _, _ -> k } }
            }
            is Node.Neg -> { val a = c(n.a); { x, y, t -> -a(x, y, t) } }
            is Node.Add -> { val a = c(n.a); val b = c(n.b); { x, y, t -> a(x, y, t) + b(x, y, t) } }
            is Node.Sub -> { val a = c(n.a); val b = c(n.b); { x, y, t -> a(x, y, t) - b(x, y, t) } }
            is Node.Mul -> { val a = c(n.a); val b = c(n.b); { x, y, t -> a(x, y, t) * b(x, y, t) } }
            is Node.Div -> { val a = c(n.a); val b = c(n.b); { x, y, t -> val d = b(x, y, t); if (d == 0.0) Double.NaN else a(x, y, t) / d } }
            is Node.Pow -> { val a = c(n.a); val b = c(n.b); { x, y, t ->
                val base = a(x, y, t); val e = b(x, y, t)
                if (base < 0 && e != floor(e)) { val inv = 1 / e; if (abs(inv - inv.roundToLong()) < 1e-9 && inv.roundToLong() % 2L != 0L) -(-base).pow(e) else Double.NaN } else base.pow(e)
            } }
            is Node.Fact -> { val a = c(n.a); { x, y, t -> runCatching { factorial(a(x, y, t)) }.getOrDefault(Double.NaN) } }
            is Node.Fn -> {
                val args = n.args.map { c(it) }
                val a = args.firstOrNull() ?: { _, _, _ -> Double.NaN }
                when (n.name) {
                    "sin" -> { x, y, t -> sin(a(x, y, t) * toRad) }; "cos" -> { x, y, t -> cos(a(x, y, t) * toRad) }; "tan" -> { x, y, t -> tan(a(x, y, t) * toRad) }
                    "sec" -> { x, y, t -> 1 / cos(a(x, y, t) * toRad) }; "csc" -> { x, y, t -> 1 / sin(a(x, y, t) * toRad) }; "cot" -> { x, y, t -> 1 / tan(a(x, y, t) * toRad) }
                    "asin" -> { x, y, t -> asin(a(x, y, t)) * fromRad }; "acos" -> { x, y, t -> acos(a(x, y, t)) * fromRad }
                    "atan" -> if (args.size == 2) { x, y, t -> atan2(args[0](x, y, t), args[1](x, y, t)) * fromRad } else { x, y, t -> atan(a(x, y, t)) * fromRad }
                    "sinh" -> { x, y, t -> sinh(a(x, y, t)) }; "cosh" -> { x, y, t -> cosh(a(x, y, t)) }; "tanh" -> { x, y, t -> tanh(a(x, y, t)) }
                    "sqrt" -> { x, y, t -> sqrt(a(x, y, t)) }; "cbrt" -> { x, y, t -> cbrt(a(x, y, t)) }
                    "ln" -> { x, y, t -> ln(a(x, y, t)) }; "log" -> if (args.size == 2) { x, y, t -> ln(args[0](x, y, t)) / ln(args[1](x, y, t)) } else { x, y, t -> log10(a(x, y, t)) }
                    "log2" -> { x, y, t -> log2(a(x, y, t)) }; "exp" -> { x, y, t -> exp(a(x, y, t)) }; "abs" -> { x, y, t -> abs(a(x, y, t)) }
                    "floor" -> { x, y, t -> floor(a(x, y, t)) }; "ceil" -> { x, y, t -> ceil(a(x, y, t)) }; "round" -> { x, y, t -> Math.round(a(x, y, t)).toDouble() }
                    "sign" -> { x, y, t -> sign(a(x, y, t)) }; "min" -> { x, y, t -> args.minOf { it(x, y, t) } }; "max" -> { x, y, t -> args.maxOf { it(x, y, t) } }
                    "mod" -> { x, y, t -> val p = args[0](x, y, t); val q = args.getOrNull(1)?.invoke(x, y, t) ?: Double.NaN; val r = p % q; if (r != 0.0 && (r < 0) != (q < 0)) r + q else r }
                    "root" -> { x, y, t -> val b = args.getOrNull(1)?.invoke(x, y, t) ?: 2.0; val base = a(x, y, t); if (base < 0 && b.roundToLong() % 2L != 0L) -(-base).pow(1 / b) else base.pow(1 / b) }
                    else -> { _, _, _ -> Double.NaN }
                }
            }
        }
        return c(n)
    }

    /* --------------------------------------------- notable points --------------------------------------------- */

    data class Mark(val x: Double, val y: Double, val label: String)

    /** Roots, turning points of y = f(x) in [from, to], found by sign changes then refined. */
    fun marks(f: (Double) -> Double, from: Double, to: Double, steps: Int = 600): List<Mark> {
        val out = mutableListOf<Mark>()
        val h = (to - from) / steps
        fun refine(a0: Double, b0: Double, g: (Double) -> Double): Double? {
            var a = a0; var b = b0; var fa = g(a); val fb = g(b)
            if (!fa.isFinite() || !fb.isFinite() || fa * fb > 0) return null
            repeat(60) { val m = (a + b) / 2; val fm = g(m); if (!fm.isFinite()) return null; if (fa * fm <= 0) b = m else { a = m; fa = fm } }
            return (a + b) / 2
        }
        val d = { x: Double -> (f(x + 1e-6) - f(x - 1e-6)) / 2e-6 }
        var px = from; var py = f(px); var pd = d(px)
        for (i in 1..steps) {
            val x = from + i * h; val y = f(x); val dy = d(x)
            if (py.isFinite() && y.isFinite() && py * y <= 0 && abs(y - py) < (to - from) * 10) refine(px, x, f)?.let { r -> if (out.none { abs(it.x - r) < h }) out += Mark(r, 0.0, "root") }
            if (pd.isFinite() && dy.isFinite() && pd * dy < 0) refine(px, x, d)?.let { r -> val v = f(r); if (v.isFinite()) out += Mark(r, v, if (pd > 0) "maximum" else "minimum") }
            px = x; py = y; pd = dy
        }
        return out.take(40)
    }

    fun intersections(f: (Double) -> Double, g: (Double) -> Double, from: Double, to: Double): List<Mark> =
        marks({ x -> f(x) - g(x) }, from, to).filter { it.label == "root" }.map { Mark(it.x, f(it.x), "intersection") }

    fun nice(v: Double): String = if (abs(v) < 1e-9) "0" else if (abs(v) >= 1e5 || abs(v) < 1e-3) "%.3g".format(v) else (Math.round(v * 1000) / 1000.0).toString().removeSuffix(".0")
}
