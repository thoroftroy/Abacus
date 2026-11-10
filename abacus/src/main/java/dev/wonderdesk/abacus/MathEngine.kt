package dev.wonderdesk.abacus

import kotlin.math.*

/*
 * Abacus math engine: parser, evaluator, polynomial algebra, solver, derivative and integral.
 * Pure Kotlin with no Android dependencies so it can be unit tested on the JVM.
 */

class MathError(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw MathError(message)

/* ------------------------------------------- AST ------------------------------------------- */

sealed class Node {
    data class Num(val v: Double) : Node()
    data class Var(val name: String) : Node()
    data class Neg(val a: Node) : Node()
    data class Add(val a: Node, val b: Node) : Node()
    data class Sub(val a: Node, val b: Node) : Node()
    data class Mul(val a: Node, val b: Node) : Node()
    data class Div(val a: Node, val b: Node) : Node()
    data class Pow(val a: Node, val b: Node) : Node()
    data class Fn(val name: String, val args: List<Node>) : Node()
    data class Fact(val a: Node) : Node()
}

val CONSTANTS = mapOf("pi" to PI, "π" to PI, "e" to E, "tau" to 2 * PI, "phi" to (1 + sqrt(5.0)) / 2)
val FUNCTIONS = setOf(
    "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
    "sec", "csc", "cot", "sqrt", "cbrt", "ln", "log", "log2", "exp", "abs", "floor", "ceil", "round",
    "sign", "root", "ncr", "npr", "gcd", "lcm", "min", "max", "mod", "frac", "deg", "rad"
)
private val NAMES = (FUNCTIONS + CONSTANTS.keys + "ans").sortedByDescending { it.length }

/* ----------------------------------------- tokenizer --------------------------------------- */

private sealed class Tok {
    data class N(val v: Double) : Tok()
    data class Id(val s: String) : Tok()
    data class Op(val c: Char) : Tok()
}

private fun tokenize(src: String): List<Tok> {
    val s = src.replace('×', '*').replace('÷', '/').replace('−', '-').replace('·', '*').replace("**", "^")
        .replace('–', '-').replace("√", " sqrt ").replace("∛", " cbrt ")
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                val text = s.substring(start, i)
                out += Tok.N(text.toDoubleOrNull() ?: fail("“$text” is not a number"))
            }
            c.isLetter() || c == 'π' -> {
                val start = i
                while (i < s.length && (s[i].isLetter() || s[i] == 'π')) i++
                // Split runs like "xsin" or "2xy" into known names and single-letter variables.
                var word = s.substring(start, i).lowercase()
                if (word == "ans" || word in FUNCTIONS || word in CONSTANTS) { out += Tok.Id(word); continue }
                while (word.isNotEmpty()) {
                    val known = NAMES.firstOrNull { word.startsWith(it) && (it.length > 1 || it == "e" || it == "π") }
                    if (known != null && known.length > 1) { out += Tok.Id(known); word = word.substring(known.length) }
                    else { out += Tok.Id(word.substring(0, 1)); word = word.substring(1) }
                }
            }
            c == '²' -> { out += Tok.Op('^'); out += Tok.N(2.0); i++ }
            c == '³' -> { out += Tok.Op('^'); out += Tok.N(3.0); i++ }
            c in "+-*/^()!%=,[]" -> { out += Tok.Op(if (c == '[') '(' else if (c == ']') ')' else c); i++ }
            else -> fail("Unexpected symbol “$c”")
        }
    }
    return out
}

/* ------------------------------------------ parser ----------------------------------------- */

class Parsed(val left: Node, val right: Node?) {
    val isEquation get() = right != null
    /** left - right, or just the expression. */
    val zeroForm: Node get() = if (right == null) left else Node.Sub(left, right)
}

object MathParser {
    fun parse(source: String): Parsed {
        val text = source.trim()
        if (text.isEmpty()) fail("Type something to calculate")
        if (text.length > 1000) fail("That expression is too long")
        val p = P(tokenize(text))
        val left = p.expr()
        var right: Node? = null
        if (p.peekOp('=')) { p.pos++; right = p.expr() }
        if (p.pos < p.toks.size) fail(if (p.peekOp(')')) "There is an extra closing bracket" else "Couldn't understand the input near item ${p.pos + 1}")
        return Parsed(left, right)
    }

    fun expression(source: String): Node {
        val parsed = parse(source)
        if (parsed.isEquation) fail("Remove the “=” sign here")
        return parsed.left
    }

    private class P(val toks: List<Tok>) {
        var pos = 0
        var depth = 0
        fun peek() = toks.getOrNull(pos)
        fun peekOp(c: Char) = (peek() as? Tok.Op)?.c == c
        fun expect(c: Char, what: String) { if (!peekOp(c)) fail(what); pos++ }

        fun expr(): Node {
            if (++depth > 200) fail("The expression is nested too deeply")
            var n = term()
            while (true) {
                n = when {
                    peekOp('+') -> { pos++; Node.Add(n, term()) }
                    peekOp('-') -> { pos++; Node.Sub(n, term()) }
                    else -> { depth--; return n }
                }
            }
        }

        private fun startsAtom(): Boolean = when (val t = peek()) {
            is Tok.N, is Tok.Id -> true
            is Tok.Op -> t.c == '('
            null -> false
        }

        fun term(): Node {
            var n = unary()
            while (true) {
                n = when {
                    peekOp('*') -> { pos++; Node.Mul(n, unary()) }
                    peekOp('/') -> { pos++; Node.Div(n, unary()) }
                    startsAtom() -> Node.Mul(n, power())   // implicit multiplication: 2x, 3(x+1), (a)(b)
                    else -> return n
                }
            }
        }

        fun unary(): Node = when {
            peekOp('-') -> { pos++; Node.Neg(unary()) }
            peekOp('+') -> { pos++; unary() }
            else -> power()
        }

        fun power(): Node {
            val base = postfix()
            if (peekOp('^')) { pos++; return Node.Pow(base, unary()) }
            return base
        }

        fun postfix(): Node {
            var n = atom()
            while (true) {
                n = when {
                    peekOp('!') -> { pos++; Node.Fact(n) }
                    peekOp('%') -> { pos++; Node.Div(n, Node.Num(100.0)) }
                    else -> return n
                }
            }
        }

        fun atom(): Node {
            when (val t = peek()) {
                null -> fail("The expression ends too early")
                is Tok.N -> { pos++; return Node.Num(t.v) }
                is Tok.Op -> {
                    if (t.c == '(') {
                        pos++
                        val n = expr()
                        if (peekOp(')')) pos++   // forgive a missing final bracket
                        else if (pos < toks.size) fail("A closing bracket is missing")
                        return n
                    }
                    fail(if (t.c == ')') "There is an extra closing bracket" else "Something is missing before “${t.c}”")
                }
                is Tok.Id -> {
                    pos++
                    val name = t.s
                    if (name in FUNCTIONS) {
                        val args = mutableListOf<Node>()
                        if (peekOp('(')) {
                            pos++
                            if (!peekOp(')')) {
                                args += expr()
                                while (peekOp(',')) { pos++; args += expr() }
                            }
                            if (peekOp(')')) pos++ else if (pos < toks.size) fail("A closing bracket is missing after $name")
                        } else {
                            // sin x, sqrt 2, sin 2x (binds the following power-level term, like most calculators)
                            args += power()
                        }
                        return Node.Fn(name, args)
                    }
                    if (name == "ans") return Node.Var("ans")
                    CONSTANTS[name]?.let { return Node.Var(name) }
                    return Node.Var(name)
                }
            }
        }
    }
}

/* ----------------------------------------- evaluator --------------------------------------- */

class Evaluator(val degrees: Boolean = false, val env: Map<String, Double> = emptyMap()) {
    private fun toRad(x: Double) = if (degrees) Math.toRadians(x) else x
    private fun fromRad(x: Double) = if (degrees) Math.toDegrees(x) else x

    fun eval(n: Node): Double = when (n) {
        is Node.Num -> n.v
        is Node.Var -> env[n.name] ?: CONSTANTS[n.name] ?: fail(if (n.name == "ans") "There is no previous answer yet" else "“${n.name}” has no value here. Use the Algebra tab to solve for it")
        is Node.Neg -> -eval(n.a)
        is Node.Add -> eval(n.a) + eval(n.b)
        is Node.Sub -> eval(n.a) - eval(n.b)
        is Node.Mul -> eval(n.a) * eval(n.b)
        is Node.Div -> { val d = eval(n.b); if (d == 0.0) fail("Can't divide by zero"); eval(n.a) / d }
        is Node.Pow -> power(eval(n.a), eval(n.b))
        is Node.Fact -> factorial(eval(n.a))
        is Node.Fn -> fn(n.name, n.args.map { eval(it) })
    }

    private fun power(a: Double, b: Double): Double {
        if (a < 0 && b != floor(b)) {
            // Real odd roots of negatives, e.g. (-8)^(1/3) = -2
            val inv = 1 / b
            if (abs(inv - inv.roundToLong()) < 1e-9 && inv.roundToLong() % 2L != 0L) return -(-a).pow(b)
            fail("A negative number to a fractional power is not a real number")
        }
        return a.pow(b)
    }

    private fun one(name: String, a: List<Double>): Double { if (a.size != 1) fail("$name takes one value"); return a[0] }

    private fun fn(name: String, a: List<Double>): Double = when (name) {
        "sin" -> sin(toRad(one(name, a)))
        "cos" -> cos(toRad(one(name, a)))
        "tan" -> { val x = toRad(one(name, a)); if (abs(cos(x)) < 1e-14) fail("tan is undefined there"); tan(x) }
        "sec" -> 1 / cos(toRad(one(name, a)))
        "csc" -> 1 / sin(toRad(one(name, a)))
        "cot" -> 1 / tan(toRad(one(name, a)))
        "asin" -> { val x = one(name, a); if (x !in -1.0..1.0) fail("asin needs a value from -1 to 1"); fromRad(asin(x)) }
        "acos" -> { val x = one(name, a); if (x !in -1.0..1.0) fail("acos needs a value from -1 to 1"); fromRad(acos(x)) }
        "atan" -> if (a.size == 2) fromRad(atan2(a[0], a[1])) else fromRad(atan(one(name, a)))
        "sinh" -> sinh(one(name, a)); "cosh" -> cosh(one(name, a)); "tanh" -> tanh(one(name, a))
        "asinh" -> asinh(one(name, a)); "acosh" -> acosh(one(name, a)); "atanh" -> atanh(one(name, a))
        "sqrt" -> { val x = one(name, a); if (x < 0) fail("The square root of a negative number is not real"); sqrt(x) }
        "cbrt" -> cbrt(one(name, a))
        "root" -> { if (a.size != 2) fail("root(x, n) takes a value and a degree"); power(a[0], 1 / a[1]) }
        "ln" -> { val x = one(name, a); if (x <= 0) fail("ln needs a positive value"); ln(x) }
        "log" -> if (a.size == 2) { if (a[0] <= 0 || a[1] <= 0 || a[1] == 1.0) fail("log(x, base) needs positive values"); ln(a[0]) / ln(a[1]) }
                 else { val x = one(name, a); if (x <= 0) fail("log needs a positive value"); log10(x) }
        "log2" -> { val x = one(name, a); if (x <= 0) fail("log2 needs a positive value"); log2(x) }
        "exp" -> exp(one(name, a))
        "abs" -> abs(one(name, a))
        "floor" -> floor(one(name, a)); "ceil" -> ceil(one(name, a)); "round" -> Math.round(one(name, a)).toDouble()
        "sign" -> sign(one(name, a))
        "frac" -> { val x = one(name, a); x - truncate(x) }
        "deg" -> Math.toDegrees(one(name, a)); "rad" -> Math.toRadians(one(name, a))
        "ncr" -> { if (a.size != 2) fail("nCr(n, r) takes two values"); combinations(a[0], a[1]) }
        "npr" -> { if (a.size != 2) fail("nPr(n, r) takes two values"); permutations(a[0], a[1]) }
        "gcd" -> { if (a.size < 2) fail("gcd takes two or more whole numbers"); a.map { wholeNumber(it) }.reduce(::gcd).toDouble() }
        "lcm" -> { if (a.size < 2) fail("lcm takes two or more whole numbers"); a.map { wholeNumber(it) }.reduce { x, y -> if (x == 0L || y == 0L) 0 else abs(x / gcd(x, y) * y) }.toDouble() }
        "min" -> { if (a.isEmpty()) fail("min needs values"); a.min() }
        "max" -> { if (a.isEmpty()) fail("max needs values"); a.max() }
        "mod" -> { if (a.size != 2 || a[1] == 0.0) fail("mod(a, b) needs a non-zero b"); val r = a[0] % a[1]; if (r != 0.0 && (r < 0) != (a[1] < 0)) r + a[1] else r }
        else -> fail("Unknown function $name")
    }
}

fun wholeNumber(x: Double): Long { if (abs(x - x.roundToLong()) > 1e-9) fail("${fmt(x)} is not a whole number"); return x.roundToLong() }
fun gcd(a: Long, b: Long): Long { var x = abs(a); var y = abs(b); while (y != 0L) { val t = x % y; x = y; y = t }; return x }

fun factorial(x: Double): Double {
    if (x < 0 && x == floor(x)) fail("Factorial is not defined for negative whole numbers")
    if (x > 170) fail("That factorial is too large")
    if (x == floor(x)) { var r = 1.0; for (i in 2..x.toInt()) r *= i; return r }
    return gamma(x + 1)
}

/** Lanczos approximation of the gamma function. */
fun gamma(z: Double): Double {
    if (z < 0.5) return PI / (sin(PI * z) * gamma(1 - z))
    val g = 7.0
    val c = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
    val x = z - 1
    var a = c[0]
    val t = x + g + 0.5
    for (i in 1 until 9) a += c[i] / (x + i)
    return sqrt(2 * PI) * t.pow(x + 0.5) * exp(-t) * a
}

fun combinations(n: Double, r: Double): Double {
    val nn = wholeNumber(n); val rr = wholeNumber(r)
    if (rr < 0 || nn < 0 || rr > nn) return 0.0
    val k = min(rr, nn - rr)
    var result = 1.0
    for (i in 1..k) result = result * (nn - k + i) / i
    return Math.round(result).toDouble().takeIf { result < 1e15 } ?: result
}

fun permutations(n: Double, r: Double): Double {
    val nn = wholeNumber(n); val rr = wholeNumber(r)
    if (rr < 0 || nn < 0 || rr > nn) return 0.0
    var result = 1.0
    for (i in 0 until rr) result *= (nn - i)
    return result
}

/* ---------------------------------------- formatting --------------------------------------- */

/** Decimal for the calculator: up to 12 significant digits, scientific when huge or tiny. */
fun fmt(v: Double): String {
    if (v.isNaN()) return "undefined"
    if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
    if (v == 0.0) return "0"
    val a = abs(v)
    if (a >= 1e15 || a < 1e-9) {
        val s = "%.10e".format(java.util.Locale.ROOT, v)
        val (m, e) = s.split('e')
        val mant = m.trimEnd('0').trimEnd('.')
        return "$mant×10^${e.toInt()}"
    }
    return java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
}

/** Converter display: 10 significant digits, scientific when very large or small. */
fun unitFmt(v: Double): String {
    if (!v.isFinite()) return fmt(v)
    val a = abs(v)
    if (a != 0.0 && (a < 1e-6 || a >= 1e12)) {
        val (m, ex) = "%.6e".format(java.util.Locale.ROOT, v).split('e')
        return m.trimEnd('0').trimEnd('.') + "×10^" + ex.toInt()
    }
    return java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
}

/** Exact-looking number for algebra: integers, simple fractions, simple surds, else decimal. */
fun nice(v: Double): String {
    if (!v.isFinite()) return fmt(v)
    val r = v.roundToLong()
    if (abs(v - r) < 1e-9 * max(1.0, abs(v))) return r.toString()
    fraction(v)?.let { (p, q) -> return "$p/$q" }
    return fmt(v)
}

/** Best rational p/q with q <= 1000 matching v closely, or null. */
fun fraction(v: Double, maxDen: Long = 1000): Pair<Long, Long>? {
    if (!v.isFinite() || abs(v) > 1e9) return null
    for (q in 2..maxDen) {
        val p = (v * q).roundToLong()
        if (abs(v - p.toDouble() / q) < 1e-10 * max(1.0, abs(v))) {
            val g = gcd(p, q)
            return (p / g) to (q / g)
        }
    }
    return null
}

/** sqrt(n) for a positive integer n as k√m, e.g. √12 = 2√3. */
fun surd(n: Long): Pair<Long, Long> {
    var k = 1L; var m = n; var f = 2L
    while (f * f <= m) { while (m % (f * f) == 0L) { m /= f * f; k *= f }; f++ }
    return k to m
}

/* ---------------------------------------- polynomials -------------------------------------- */

/** Multivariate polynomial: monomial (sorted var->power) to coefficient. */
class Poly(val terms: Map<Map<String, Int>, Double>) {
    companion object {
        val ZERO = Poly(emptyMap())
        fun const(c: Double) = if (c == 0.0) ZERO else Poly(mapOf(emptyMap<String, Int>() to c))
        fun variable(name: String) = Poly(mapOf(mapOf(name to 1) to 1.0))

        /** Converts a node to a polynomial, or null when it isn't one (e.g. sin(x), 1/x). */
        fun from(n: Node, degrees: Boolean = false): Poly? = when (n) {
            is Node.Num -> const(n.v)
            is Node.Var -> if (n.name in CONSTANTS || n.name == "ans") null else variable(n.name)
            is Node.Neg -> from(n.a, degrees)?.times(const(-1.0))
            is Node.Add -> from(n.a, degrees)?.let { a -> from(n.b, degrees)?.let { a + it } }
            is Node.Sub -> from(n.a, degrees)?.let { a -> from(n.b, degrees)?.let { a - it } }
            is Node.Mul -> from(n.a, degrees)?.let { a -> from(n.b, degrees)?.let { a * it } }
            is Node.Div -> {
                val d = from(n.b, degrees)
                val c = d?.constantValue()
                if (c != null && c != 0.0) from(n.a, degrees)?.times(const(1 / c)) else null
            }
            is Node.Pow -> {
                val e = from(n.b, degrees)?.constantValue()
                if (e != null && e >= 0 && e == floor(e) && e <= 64) from(n.a, degrees)?.pow(e.toInt()) else null
            }
            else -> if (variables(n).isEmpty()) runCatching { const(Evaluator(degrees).eval(n)) }.getOrNull() else null
        }
    }

    private fun clean(m: Map<Map<String, Int>, Double>) = Poly(m.filterValues { abs(it) > 1e-12 })

    operator fun plus(o: Poly): Poly { val r = terms.toMutableMap(); o.terms.forEach { (k, v) -> r[k] = (r[k] ?: 0.0) + v }; return clean(r) }
    operator fun minus(o: Poly) = this + o * const(-1.0)
    operator fun times(o: Poly): Poly {
        val r = HashMap<Map<String, Int>, Double>()
        for ((ka, va) in terms) for ((kb, vb) in o.terms) {
            val k = (ka.keys + kb.keys).associateWith { (ka[it] ?: 0) + (kb[it] ?: 0) }.toSortedMap()
            r[k] = (r[k] ?: 0.0) + va * vb
        }
        if (r.size > 5000) fail("That expansion is too large")
        return clean(r)
    }
    fun pow(n: Int): Poly { var r = const(1.0); repeat(n) { r = r * this }; return r }
    fun constantValue(): Double? = when {
        terms.isEmpty() -> 0.0
        terms.size == 1 && terms.keys.first().isEmpty() -> terms.values.first()
        else -> null
    }
    val vars: Set<String> get() = terms.keys.flatMap { it.keys }.toSet()
    fun degree(v: String) = terms.keys.maxOfOrNull { it[v] ?: 0 } ?: 0
    val totalDegree get() = terms.keys.maxOfOrNull { it.values.sum() } ?: 0

    /** Coefficients low to high in one variable (only valid when that is the sole variable). */
    fun coefficients(v: String): DoubleArray {
        val d = degree(v)
        val c = DoubleArray(d + 1)
        terms.forEach { (k, value) -> c[k[v] ?: 0] += value }
        return c
    }

    override fun toString(): String {
        if (terms.isEmpty()) return "0"
        val sorted = terms.entries.sortedWith(compareByDescending<Map.Entry<Map<String, Int>, Double>> { it.key.values.sum() }
            .thenBy { it.key.entries.joinToString { e -> e.key + (99 - e.value) } })
        val sb = StringBuilder()
        sorted.forEachIndexed { i, (mono, c) ->
            val neg = c < 0
            val mag = abs(c)
            if (i == 0) { if (neg) sb.append("-") } else sb.append(if (neg) " - " else " + ")
            val monoText = mono.entries.joinToString("") { (v, p) -> if (p == 1) v else "$v${superscript(p)}" }
            val coef = nice(mag)
            when {
                monoText.isEmpty() -> sb.append(coef)
                abs(mag - 1) < 1e-12 -> sb.append(monoText)
                coef.contains('/') -> sb.append("($coef)$monoText")
                else -> sb.append(coef).append(monoText)
            }
        }
        return sb.toString()
    }
}

private val SUPERS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
fun superscript(n: Int): String = n.toString().map { if (it == '-') '⁻' else SUPERS[it - '0'] }.joinToString("")

fun variables(n: Node): Set<String> = when (n) {
    is Node.Num -> emptySet()
    is Node.Var -> if (n.name in CONSTANTS || n.name == "ans") emptySet() else setOf(n.name)
    is Node.Neg -> variables(n.a)
    is Node.Fact -> variables(n.a)
    is Node.Add -> variables(n.a) + variables(n.b)
    is Node.Sub -> variables(n.a) + variables(n.b)
    is Node.Mul -> variables(n.a) + variables(n.b)
    is Node.Div -> variables(n.a) + variables(n.b)
    is Node.Pow -> variables(n.a) + variables(n.b)
    is Node.Fn -> n.args.flatMap { variables(it) }.toSet()
}

/* ------------------------------------ printing / simplify ---------------------------------- */

object Printer {
    private fun prec(n: Node): Int = when (n) {
        is Node.Add, is Node.Sub -> 1
        is Node.Mul, is Node.Div -> 2
        is Node.Neg -> 3
        is Node.Pow -> 4
        is Node.Fact -> 5
        else -> 6
    }

    fun show(n: Node): String = when (n) {
        is Node.Num -> nice(n.v)
        is Node.Var -> if (n.name == "pi") "π" else n.name
        is Node.Neg -> "-" + wrap(n.a, 3)
        is Node.Add -> show(n.a) + " + " + wrap(n.b, 1)
        is Node.Sub -> show(n.a) + " - " + wrap(n.b, 2)
        is Node.Mul -> {
            val a = wrap(n.a, 2); val b = wrap(n.b, 3)
            if (n.a is Node.Num && (n.b is Node.Var || n.b is Node.Fn || (n.b is Node.Pow && n.b.a is Node.Var))) a + b else "$a·$b"
        }
        is Node.Div -> wrap(n.a, 2) + "/" + wrap(n.b, 3)
        is Node.Pow -> {
            val exp = n.b
            val base = wrap(n.a, 5)
            if (exp is Node.Num && exp.v == floor(exp.v) && exp.v in 0.0..99.0) base + superscript(exp.v.toInt()) else base + "^" + wrap(exp, 5)
        }
        is Node.Fact -> wrap(n.a, 5) + "!"
        is Node.Fn -> n.name + "(" + n.args.joinToString(", ") { show(it) } + ")"
    }

    private fun wrap(n: Node, min: Int): String = if (prec(n) < min || (n is Node.Num && n.v < 0) || (n is Node.Num && min >= 3 && nice(n.v).contains('/'))) "(${show(n)})" else show(n)
}

object Simplify {
    private fun num(n: Node) = (n as? Node.Num)?.v

    fun basic(n: Node): Node = when (n) {
        is Node.Num, is Node.Var -> n
        is Node.Neg -> { val a = basic(n.a); num(a)?.let { Node.Num(-it) } ?: if (a is Node.Neg) a.a else Node.Neg(a) }
        is Node.Add -> {
            val a = basic(n.a); val b = basic(n.b)
            when {
                num(a) != null && num(b) != null -> Node.Num(num(a)!! + num(b)!!)
                num(a) == 0.0 -> b
                num(b) == 0.0 -> a
                b is Node.Neg -> Node.Sub(a, b.a)
                num(b) != null && num(b)!! < 0 -> Node.Sub(a, Node.Num(-num(b)!!))
                else -> Node.Add(a, b)
            }
        }
        is Node.Sub -> {
            val a = basic(n.a); val b = basic(n.b)
            when {
                num(a) != null && num(b) != null -> Node.Num(num(a)!! - num(b)!!)
                num(b) == 0.0 -> a
                num(a) == 0.0 -> basic(Node.Neg(b))
                a == b -> Node.Num(0.0)
                b is Node.Neg -> Node.Add(a, b.a)
                else -> Node.Sub(a, b)
            }
        }
        is Node.Mul -> {
            val a = basic(n.a); val b = basic(n.b)
            when {
                num(a) != null && num(b) != null -> Node.Num(num(a)!! * num(b)!!)
                num(a) == 0.0 || num(b) == 0.0 -> Node.Num(0.0)
                num(a) == 1.0 -> b
                num(b) == 1.0 -> a
                num(a) == -1.0 -> basic(Node.Neg(b))
                num(b) == -1.0 -> basic(Node.Neg(a))
                num(b) != null -> basic(Node.Mul(b, a))
                num(a) != null && b is Node.Mul && num(b.a) != null -> Node.Mul(Node.Num(num(a)!! * num(b.a)!!), b.b)
                a is Node.Neg -> basic(Node.Neg(Node.Mul(a.a, b)))
                b is Node.Neg -> basic(Node.Neg(Node.Mul(a, b.a)))
                a == b -> Node.Pow(a, Node.Num(2.0))
                b is Node.Div && num(b.a) == 1.0 -> basic(Node.Div(a, b.b))
                a is Node.Div && num(a.a) == 1.0 -> basic(Node.Div(b, a.b))
                (a is Node.Fn || a is Node.Pow && a.a !is Node.Var) && Poly.from(b) != null -> Node.Mul(b, a)
                else -> Node.Mul(a, b)
            }
        }
        is Node.Div -> {
            val a = basic(n.a); val b = basic(n.b)
            when {
                num(a) != null && num(b) != null && num(b) != 0.0 -> Node.Num(num(a)!! / num(b)!!)
                num(a) == 0.0 -> Node.Num(0.0)
                num(b) == 1.0 -> a
                a == b -> Node.Num(1.0)
                else -> Node.Div(a, b)
            }
        }
        is Node.Pow -> {
            val a = basic(n.a); val b = basic(n.b)
            when {
                num(a) != null && num(b) != null -> runCatching { Node.Num(Evaluator().eval(Node.Pow(a, b))) }.getOrDefault(Node.Pow(a, b))
                num(b) == 0.0 -> Node.Num(1.0)
                num(b) == 1.0 -> a
                a is Node.Pow && num(a.b) != null && num(b) != null -> Node.Pow(a.a, Node.Num(num(a.b)!! * num(b)!!))
                else -> Node.Pow(a, b)
            }
        }
        is Node.Fact -> Node.Fact(basic(n.a))
        is Node.Fn -> {
            val args = n.args.map { basic(it) }
            when {
                n.name == "ln" && args.size == 1 && args[0] == Node.Var("e") -> Node.Num(1.0)
                n.name == "exp" && args.size == 1 && num(args[0]) == 0.0 -> Node.Num(1.0)
                else -> Node.Fn(n.name, args)
            }
        }
    }

    /** Best human form: canonical polynomial when possible, otherwise the simplified tree. */
    fun pretty(n: Node): String {
        Poly.from(n)?.let { return it.toString() }
        return Printer.show(collectPolySubterms(basic(n)))
    }

    /** Expand polynomial pieces inside a larger non-polynomial expression. */
    private fun collectPolySubterms(n: Node): Node = when (n) {
        is Node.Fn -> Node.Fn(n.name, n.args.map { a -> Poly.from(a)?.let { p -> MathParser.expression(p.toString().replace("·", "*")) } ?: collectPolySubterms(a) })
        is Node.Add -> Node.Add(collectPolySubterms(n.a), collectPolySubterms(n.b))
        is Node.Sub -> Node.Sub(collectPolySubterms(n.a), collectPolySubterms(n.b))
        is Node.Mul -> Node.Mul(collectPolySubterms(n.a), collectPolySubterms(n.b))
        is Node.Div -> Node.Div(polyOr(n.a), polyOr(n.b))
        else -> n
    }

    private fun polyOr(n: Node): Node = Poly.from(n)?.let { runCatching { MathParser.expression(polyInput(it)) }.getOrNull() } ?: collectPolySubterms(n)

    /** Polynomial rendered back as parseable input text. */
    fun polyInput(p: Poly): String = p.toString().map { c -> val i = SUPERS.indexOf(c); if (i >= 0) "^$i" else c.toString() }.joinToString("")
        .replace(Regex("\\^(\\d)\\^(\\d)"), "^$1$2")
}

/* ----------------------------------------- calculus ---------------------------------------- */

object Calculus {
    private val ONE = Node.Num(1.0)
    private val TWO = Node.Num(2.0)

    fun derivative(n: Node, x: String): Node = Simplify.basic(d(n, x))

    private fun d(n: Node, x: String): Node = when (n) {
        is Node.Num -> Node.Num(0.0)
        is Node.Var -> Node.Num(if (n.name == x) 1.0 else 0.0)
        is Node.Neg -> Node.Neg(d(n.a, x))
        is Node.Add -> Node.Add(d(n.a, x), d(n.b, x))
        is Node.Sub -> Node.Sub(d(n.a, x), d(n.b, x))
        is Node.Mul -> Node.Add(Node.Mul(d(n.a, x), n.b), Node.Mul(n.a, d(n.b, x)))
        is Node.Div -> Node.Div(Node.Sub(Node.Mul(d(n.a, x), n.b), Node.Mul(n.a, d(n.b, x))), Node.Pow(n.b, TWO))
        is Node.Pow -> {
            val baseVars = x in variables(n.a); val expVars = x in variables(n.b)
            when {
                !baseVars && !expVars -> Node.Num(0.0)
                !expVars -> Node.Mul(Node.Mul(n.b, Node.Pow(n.a, Simplify.basic(Node.Sub(n.b, ONE)))), d(n.a, x))
                !baseVars -> Node.Mul(Node.Mul(n, Node.Fn("ln", listOf(n.a))), d(n.b, x))
                else -> Node.Mul(n, Node.Add(Node.Mul(d(n.b, x), Node.Fn("ln", listOf(n.a))), Node.Div(Node.Mul(n.b, d(n.a, x)), n.a)))
            }
        }
        is Node.Fact -> if (x in variables(n.a)) fail("Can't differentiate a factorial") else Node.Num(0.0)
        is Node.Fn -> {
            if (x !in variables(n)) Node.Num(0.0) else {
                if (n.args.size != 1) fail("Can't differentiate ${n.name} with several values")
                val u = n.args[0]; val du = d(u, x)
                val outer: Node = when (n.name) {
                    "sin" -> Node.Fn("cos", listOf(u))
                    "cos" -> Node.Neg(Node.Fn("sin", listOf(u)))
                    "tan" -> Node.Div(ONE, Node.Pow(Node.Fn("cos", listOf(u)), TWO))
                    "sec" -> Node.Mul(Node.Fn("sec", listOf(u)), Node.Fn("tan", listOf(u)))
                    "csc" -> Node.Neg(Node.Mul(Node.Fn("csc", listOf(u)), Node.Fn("cot", listOf(u))))
                    "cot" -> Node.Neg(Node.Pow(Node.Fn("csc", listOf(u)), TWO))
                    "asin" -> Node.Div(ONE, Node.Fn("sqrt", listOf(Node.Sub(ONE, Node.Pow(u, TWO)))))
                    "acos" -> Node.Neg(Node.Div(ONE, Node.Fn("sqrt", listOf(Node.Sub(ONE, Node.Pow(u, TWO))))))
                    "atan" -> Node.Div(ONE, Node.Add(ONE, Node.Pow(u, TWO)))
                    "sinh" -> Node.Fn("cosh", listOf(u))
                    "cosh" -> Node.Fn("sinh", listOf(u))
                    "tanh" -> Node.Sub(ONE, Node.Pow(Node.Fn("tanh", listOf(u)), TWO))
                    "sqrt" -> Node.Div(ONE, Node.Mul(TWO, Node.Fn("sqrt", listOf(u))))
                    "cbrt" -> Node.Div(ONE, Node.Mul(Node.Num(3.0), Node.Pow(Node.Fn("cbrt", listOf(u)), TWO)))
                    "ln" -> Node.Div(ONE, u)
                    "log" -> Node.Div(ONE, Node.Mul(u, Node.Fn("ln", listOf(Node.Num(10.0)))))
                    "log2" -> Node.Div(ONE, Node.Mul(u, Node.Fn("ln", listOf(TWO))))
                    "exp" -> Node.Fn("exp", listOf(u))
                    "abs" -> Node.Div(u, Node.Fn("abs", listOf(u)))
                    else -> fail("Can't differentiate ${n.name}")
                }
                Node.Mul(outer, du)
            }
        }
    }

    /** Antiderivative of a polynomial in x, or null when not a polynomial. */
    fun polyIntegral(n: Node, x: String): String? {
        val p = Poly.from(n) ?: return null
        var r = Poly.ZERO
        p.terms.forEach { (mono, c) ->
            val k = (mono[x] ?: 0) + 1
            val m = (mono + (x to k)).toSortedMap()
            r += Poly(mapOf(m to c / k))
        }
        return "$r + C"
    }

    /** Adaptive Simpson integration. */
    fun integrate(f: (Double) -> Double, a: Double, b: Double): Double {
        fun simpson(fa: Double, fm: Double, fb: Double, a: Double, b: Double) = (b - a) / 6 * (fa + 4 * fm + fb)
        fun rec(a: Double, b: Double, fa: Double, fm: Double, fb: Double, whole: Double, eps: Double, depth: Int): Double {
            val m = (a + b) / 2
            val lm = (a + m) / 2; val rm = (m + b) / 2
            val flm = f(lm); val frm = f(rm)
            val left = simpson(fa, flm, fm, a, m); val right = simpson(fm, frm, fb, m, b)
            if (depth <= 0 || abs(left + right - whole) <= 15 * eps) return left + right + (left + right - whole) / 15
            return rec(a, m, fa, flm, fm, left, eps / 2, depth - 1) + rec(m, b, fm, frm, fb, right, eps / 2, depth - 1)
        }
        val fa = f(a); val fb = f(b); val fm = f((a + b) / 2)
        if (!fa.isFinite() || !fb.isFinite()) fail("The function is undefined at an end of the interval")
        val r = rec(a, b, fa, fm, fb, simpson(fa, fm, fb, a, b), 1e-10, 40)
        if (!r.isFinite()) fail("The integral does not converge on that interval")
        return r
    }
}

/* ------------------------------------------ solver ----------------------------------------- */

data class Solution(val headline: String, val lines: List<String>, val steps: List<String> = emptyList(), val roots: List<Double> = emptyList())

object Solver {
    fun solve(input: String, degrees: Boolean = false): Solution {
        val equations = input.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (equations.size > 1) return system(equations)
        val parsed = MathParser.parse(input)
        val f = parsed.zeroForm
        val vars = variables(f)
        if (vars.isEmpty()) {
            val l = Evaluator(degrees).eval(parsed.left)
            if (!parsed.isEquation) return Solution(fmt(l), listOf("No variable to solve for. The value is ${fmt(l)}."))
            val r = Evaluator(degrees).eval(parsed.right!!)
            return if (abs(l - r) < 1e-9 * max(1.0, abs(l))) Solution("True", listOf("Both sides equal ${fmt(l)}."))
            else Solution("False", listOf("Left side is ${fmt(l)}, right side is ${fmt(r)}."))
        }
        val x = when {
            "x" in vars -> "x"
            vars.size == 1 -> vars.first()
            else -> vars.sorted().first()
        }
        if (vars.size > 1) return solveFor(f, x, vars)
        val poly = Poly.from(f, degrees)
        if (poly != null && poly.degree(x) >= 1) return polynomial(poly, x)
        return numeric(f, x, degrees)
    }

    /** Linear in x with other letters as parameters: x = -(rest)/(coef). */
    private fun solveFor(f: Node, x: String, vars: Set<String>): Solution {
        val poly = Poly.from(f) ?: fail("With several letters, Abacus can solve equations that are polynomial. Enter one equation per variable (separate with ;) for a system")
        val deg = poly.degree(x)
        if (deg != 1) fail("Solving for $x with other letters works when $x appears to the first power")
        var coef = Poly.ZERO; var rest = Poly.ZERO
        poly.terms.forEach { (m, c) -> if ((m[x] ?: 0) == 1) coef += Poly(mapOf(m.filterKeys { it != x }.toSortedMap() to c)) else rest += Poly(mapOf(m to c)) }
        val num = rest * Poly.const(-1.0)
        val cv = coef.constantValue()
        fun par(p: Poly) = if (p.terms.size > 1) "($p)" else p.toString()
        val answer = if (cv != null) (num * Poly.const(1 / cv)).toString() else "${par(num)}/${par(coef)}"
        return Solution("$x = $answer", listOf("$x = $answer"), listOf("Collect the $x terms: (${coef})·$x + (${rest}) = 0", "Divide by the coefficient of $x"))
    }

    private fun polynomial(p: Poly, x: String): Solution {
        val c = p.coefficients(x)
        val deg = c.size - 1
        val steps = mutableListOf("Rewrite as ${p} = 0")
        when (deg) {
            1 -> {
                val r = -c[0] / c[1]
                steps += "Move the constant: ${nice(c[1])}$x = ${nice(-c[0])}"
                steps += "Divide by ${nice(c[1])}"
                return Solution("$x = ${nice(r)}", listOf("$x = ${nice(r)}") + decimalLine(listOf(r)), steps, listOf(r))
            }
            2 -> {
                val (a, b, cc) = Triple(c[2], c[1], c[0])
                val disc = b * b - 4 * a * cc
                steps += "Quadratic with a = ${nice(a)}, b = ${nice(b)}, c = ${nice(cc)}"
                steps += "Discriminant b² - 4ac = ${nice(disc)}"
                steps += "$x = (-b ± √(b² - 4ac)) / 2a"
                if (abs(disc) < 1e-12) {
                    val r = -b / (2 * a)
                    return Solution("$x = ${nice(r)}", listOf("$x = ${nice(r)} (double root)"), steps, listOf(r))
                }
                if (disc > 0) {
                    val r1 = (-b - sqrt(disc)) / (2 * a); val r2 = (-b + sqrt(disc)) / (2 * a)
                    val exact = exactQuadratic(a, b, disc, x)
                    val roots = listOf(min(r1, r2), max(r1, r2))
                    val lines = mutableListOf<String>()
                    if (exact != null) lines += exact
                    lines += roots.map { "$x = ${nice(it)}" }.joinToString("   or   ")
                    return Solution(if (exact != null && roots.any { fraction(it) == null && abs(it - it.roundToLong()) > 1e-9 }) exact else roots.joinToString(",  ") { "$x = ${nice(it)}" }, lines, steps, roots)
                }
                val re = -b / (2 * a); val im = sqrt(-disc) / (2 * abs(a))
                return Solution("No real solutions", listOf("Complex: $x = ${nice(re)} ± ${nice(im)}i"), steps)
            }
            else -> {
                val roots = durandKerner(c)
                val real = roots.filter { abs(it.second) < 1e-7 }.map { polish(c, it.first) }.sorted().distinctBy { (it * 1e8).roundToLong() }
                val complex = roots.filter { abs(it.second) >= 1e-7 && it.second > 0 }
                steps += "Degree $deg polynomial: roots found numerically"
                val factored = runCatching { Factor.factor(p, x) }.getOrNull()
                if (factored != null && factored != p.toString()) steps += "Factored: $factored"
                val lines = mutableListOf<String>()
                if (real.isEmpty()) lines += "No real solutions" else lines += real.joinToString("   or   ") { "$x = ${nice(it)}" }
                complex.forEach { (re, im) -> lines += "Complex: $x = ${nice(re)} ± ${nice(abs(im))}i" }
                return Solution(if (real.isEmpty()) "No real solutions" else real.joinToString(",  ") { "$x = ${nice(it)}" }, lines, steps, real)
            }
        }
    }

    private fun exactQuadratic(a: Double, b: Double, disc: Double, x: String): String? {
        val dl = disc.roundToLong()
        if (abs(disc - dl) > 1e-9 || abs(a - a.roundToLong()) > 1e-9 || abs(b - b.roundToLong()) > 1e-9) return null
        val (k, m) = surd(dl)
        if (m == 1L) return null // rational roots, shown as plain numbers
        var num = -b.roundToLong(); var kk = k; var den = 2 * a.roundToLong()
        val g = gcd(gcd(num, kk), den)
        num /= g; kk /= g; den /= g
        if (den < 0) { num = -num; den = -den }
        val root = (if (kk == 1L) "" else "$kk") + "√$m"
        val top = if (num == 0L) "±$root" else "$num ± $root"
        return if (den == 1L) "$x = $top" else "$x = ($top)/$den"
    }

    private fun decimalLine(r: List<Double>) = if (r.any { nice(it).contains('/') }) listOf("≈ " + r.joinToString(", ") { fmt(it) }) else emptyList()

    private fun polish(c: DoubleArray, guess: Double): Double {
        var r = guess
        repeat(30) {
            var f = 0.0; var df = 0.0
            for (i in c.indices.reversed()) { df = df * r + f; f = f * r + c[i] }
            if (df == 0.0) return r
            val next = r - f / df
            if (abs(next - r) < 1e-15) return next
            r = next
        }
        return if (abs(r - r.roundToLong()) < 1e-9) r.roundToLong().toDouble() else r
    }

    /** All complex roots of a polynomial (coefficients low to high). */
    fun durandKerner(coef: DoubleArray): List<Pair<Double, Double>> {
        val n = coef.size - 1
        val lead = coef[n]
        val a = DoubleArray(n + 1) { coef[it] / lead }
        var re = DoubleArray(n) { cos(2 * PI * it / n + .4) * (1 + abs(a.dropLast(1).maxOf { v -> abs(v) })).pow(1.0 / 1) }
        var im = DoubleArray(n) { sin(2 * PI * it / n + .4) * 1.0 }
        val radius = 1 + a.dropLast(1).maxOf { abs(it) }
        for (i in 0 until n) { re[i] = radius * cos(2 * PI * i / n + .4); im[i] = radius * sin(2 * PI * i / n + .4) }
        repeat(2000) {
            var change = 0.0
            val nr = re.copyOf(); val ni = im.copyOf()
            for (i in 0 until n) {
                // p(z)
                var pr = 1.0; var pi = 0.0
                for (k in n - 1 downTo 0) { val t = pr * re[i] - pi * im[i] + a[k]; pi = pr * im[i] + pi * re[i]; pr = t }
                var dr = 1.0; var di = 0.0
                for (j in 0 until n) if (j != i) {
                    val xr = re[i] - re[j]; val xi = im[i] - im[j]
                    val t = dr * xr - di * xi; di = dr * xi + di * xr; dr = t
                }
                val den = dr * dr + di * di
                if (den == 0.0) continue
                val qr = (pr * dr + pi * di) / den; val qi = (pi * dr - pr * di) / den
                nr[i] = re[i] - qr; ni[i] = im[i] - qi
                change = max(change, abs(qr) + abs(qi))
            }
            re = nr; im = ni
            if (change < 1e-14) return re.indices.map { re[it] to im[it] }
        }
        return re.indices.map { re[it] to im[it] }
    }

    /** Sign-change scan plus refinement for non-polynomial equations. */
    private fun numeric(f: Node, x: String, degrees: Boolean): Solution {
        val fn = { v: Double -> runCatching { Evaluator(degrees, mapOf(x to v)).eval(f) }.getOrDefault(Double.NaN) }
        val samples = buildList {
            var v = -100.0; while (v <= 100.0) { add(v); v += 0.01 }
        }
        val roots = mutableListOf<Double>()
        var prevX = Double.NaN; var prevY = Double.NaN
        for (v in samples) {
            val y = fn(v)
            if (y.isFinite()) {
                if (y == 0.0) roots += v
                else if (prevY.isFinite() && prevY * y < 0) {
                    val r = bisect(fn, prevX, v)
                    if (r != null && abs(fn(r)) < 1e-6) roots += r
                }
            }
            prevX = v; prevY = y
            if (roots.size > 40) break
        }
        if (roots.isEmpty()) {
            // Nothing near zero: look further out on a coarse logarithmic grid.
            var prev = Double.NaN; var py = Double.NaN
            val far = buildList { var w = 100.0; while (w < 1e6) { add(w); add(-w); w *= 1.02 } }.sorted()
            for (v in far) { val y = fn(v); if (y.isFinite() && py.isFinite() && py * y < 0) bisect(fn, prev, v)?.let { if (abs(fn(it)) < 1e-6) roots += it }; prev = v; py = y; if (roots.size > 12) break }
        }
        val unique = roots.map { if (abs(it - it.roundToLong()) < 1e-9) it.roundToLong().toDouble() else it }.sorted().fold(mutableListOf<Double>()) { acc, r -> if (acc.isEmpty() || abs(acc.last() - r) > 1e-6) acc += r; acc }
        if (unique.isEmpty()) return Solution("No solutions found", listOf("No real solution between -1,000,000 and 1,000,000."), listOf("Searched numerically for sign changes"))
        val shown = unique.sortedBy { abs(it) }.take(8).sorted()
        fun show(v: Double) = if (abs(v - v.roundToLong()) < 1e-9) "$x = ${v.roundToLong()}" else "$x ≈ ${fmt(v)}"
        val lines = shown.map { show(it) }.toMutableList()
        if (unique.size > 8) lines += if (isPeriodic(unique.sorted().take(8))) "…and more: the solutions repeat every ${fmt(unique.sorted().let { it[1] - it[0] })}" else "…and ${unique.size - 8} more"
        return Solution(shown.sortedBy { abs(it) }.take(3).sorted().joinToString(",  ") { show(it) } + if (unique.size > 3) ", …" else "", lines,
            listOf("Not a polynomial, so Abacus searched numerically", "Each solution is accurate to about 12 digits"), shown)
    }

    private fun isPeriodic(r: List<Double>): Boolean { val d = r.zipWithNext { a, b -> b - a }; return d.size > 3 && d.all { abs(it - d[0]) < 1e-6 } }

    private fun bisect(f: (Double) -> Double, lo0: Double, hi0: Double): Double? {
        var lo = lo0; var hi = hi0; var flo = f(lo)
        repeat(200) {
            val mid = (lo + hi) / 2
            val fm = f(mid)
            if (!fm.isFinite()) return null
            if (fm == 0.0 || hi - lo < 1e-15 * max(1.0, abs(mid))) return mid
            if (flo * fm < 0) hi = mid else { lo = mid; flo = fm }
        }
        return (lo + hi) / 2
    }

    /** Linear systems via Gaussian elimination with partial pivoting. */
    fun system(eqs: List<String>): Solution {
        val forms = eqs.map { MathParser.parse(it).zeroForm }
        val vars = forms.flatMap { variables(it) }.toSortedSet().toList()
        if (vars.isEmpty()) fail("The system has no variables")
        if (vars.size > 8) fail("Up to 8 variables are supported")
        val polys = forms.map { Poly.from(it) ?: fail("Each equation in a system must be linear (like 2x + 3y = 7)") }
        if (polys.any { p -> p.terms.keys.any { it.values.sum() > 1 } }) fail("Each equation in a system must be linear (no x², xy and so on)")
        val m = polys.map { p -> DoubleArray(vars.size + 1) { j -> if (j < vars.size) p.terms[mapOf(vars[j] to 1)] ?: 0.0 else -(p.terms[emptyMap()] ?: 0.0) } }.toMutableList()
        val rows = m.size; val cols = vars.size
        var r = 0
        val pivotCol = IntArray(rows) { -1 }
        for (c in 0 until cols) {
            if (r >= rows) break
            val best = (r until rows).maxByOrNull { abs(m[it][c]) }!!
            if (abs(m[best][c]) < 1e-12) continue
            val t = m[r]; m[r] = m[best]; m[best] = t
            val pv = m[r][c]
            for (j in 0..cols) m[r][j] /= pv
            for (i in 0 until rows) if (i != r) { val k = m[i][c]; if (k != 0.0) for (j in 0..cols) m[i][j] -= k * m[r][j] }
            pivotCol[r] = c; r++
        }
        for (i in r until rows) if (abs(m[i][cols]) > 1e-9) return Solution("No solution", listOf("The equations contradict each other."), listOf("Gaussian elimination found 0 = ${nice(m[i][cols])}"))
        if (r < cols) {
            val free = vars.filterIndexed { j, _ -> j !in pivotCol }
            return Solution("Infinitely many solutions", listOf("There are fewer independent equations than variables.", "Free: ${free.joinToString()}"), listOf("Rank $r for ${cols} variables"))
        }
        val values = vars.indices.map { j -> m[pivotCol.indexOf(j)][cols] }
        val lines = vars.mapIndexed { j, v -> "$v = ${nice(values[j])}" }
        return Solution(lines.joinToString(",  "), lines, listOf("${eqs.size} linear equations in ${vars.size} variables", "Solved by Gaussian elimination"), values)
    }
}

/* ------------------------------------------ factor ----------------------------------------- */

object Factor {
    /** Factor a polynomial over the rationals: common factor, rational roots, irreducible rest. */
    fun factor(p: Poly, x: String): String {
        if (p.vars.size > 1) return commonMonomial(p)
        val c0 = p.coefficients(x)
        if (c0.size <= 1) return p.toString()
        // Scale to integers.
        val dens = c0.map { fraction(it)?.second ?: if (abs(it - it.roundToLong()) < 1e-9) 1L else fail("Factoring needs whole-number or fraction coefficients") }
        val l = dens.fold(1L) { a, b -> a / gcd(a, b) * b }
        var ints = c0.map { (it * l).roundToLong() }.toMutableList()
        var content = ints.filter { it != 0L }.fold(0L) { a, b -> gcd(a, b) }
        if (ints.last() < 0) content = -content
        ints = ints.map { it / content }.toMutableList()
        val factors = mutableListOf<String>()
        // x^k factor
        var k = 0
        while (ints.size > 1 && ints[0] == 0L) { ints.removeAt(0); k++ }
        if (k > 0) factors += if (k == 1) x else "$x${superscript(k)}"
        // rational roots p/q
        var changed = true
        while (ints.size > 1 && changed) {
            changed = false
            val a0 = abs(ints.first()); val an = abs(ints.last())
            loop@ for (pp in divisors(a0)) for (qq in divisors(an)) for (s in listOf(1L, -1L)) {
                val num = s * pp
                if (gcd(num, qq) != 1L) continue
                if (evalInt(ints, num, qq) == 0L) {
                    ints = syntheticDivide(ints, num, qq).toMutableList()
                    factors += linear(x, qq, num)
                    changed = true
                    break@loop
                }
            }
        }
        val rest = if (ints.size > 1) polyText(ints, x) else null
        val counted = factors.groupingBy { it }.eachCount()
        val pieces = counted.map { (f, n) ->
            val base = if (f.contains(' ')) "($f)" else f
            if (n == 1) base else base + superscript(n)
        }.toMutableList()
        if (rest != null) pieces += if (pieces.isEmpty() && content == 1L && l == 1L) rest else "($rest)"
        // Overall constant = content / l, times the leading coefficient left in a constant remainder.
        var num = content * (if (ints.size == 1) ints[0] else 1L)
        var den = l
        val g = gcd(num, den).coerceAtLeast(1)
        num /= g; den /= g
        val lead = when {
            den == 1L && num == 1L -> ""
            den == 1L && num == -1L -> "-"
            den == 1L -> num.toString()
            else -> "($num/$den)"
        }
        return lead + pieces.joinToString("").ifEmpty { if (lead.isEmpty() || lead == "-") lead + "1" else "" }
    }

    private fun linear(x: String, q: Long, p: Long): String = when {
        p == 0L -> x
        else -> (if (q == 1L) x else "$q$x") + (if (p > 0) " - $p" else " + ${-p}")
    }
    private fun divisors(n: Long): List<Long> { if (n == 0L) return listOf(1); val r = mutableListOf<Long>(); var i = 1L; while (i * i <= n) { if (n % i == 0L) { r += i; if (i != n / i) r += n / i }; i++; if (i > 100000) break }; return r }
    /** Evaluates q^n * P(p/q) exactly. */
    private fun evalInt(c: List<Long>, p: Long, q: Long): Long {
        val n = c.size - 1
        var sum = 0L
        var pp = 1L
        for (i in 0..n) {
            var qpow = 1L; repeat(n - i) { qpow *= q }
            sum += c[i] * pp * qpow
            pp *= p
        }
        return sum
    }
    /** Divide by (q x - p); coefficients low to high. */
    private fun syntheticDivide(c: List<Long>, p: Long, q: Long): List<Long> {
        val n = c.size - 1
        val out = LongArray(n)
        var carry = 0L
        for (i in n downTo 1) {
            val v = c[i] + carry
            out[i - 1] = v / q
            carry = out[i - 1] * p
        }
        return out.toList()
    }
    private fun polyText(c: List<Long>, x: String): String {
        val p = Poly(c.mapIndexed { i, v -> (if (i == 0) emptyMap() else mapOf(x to i)) to v.toDouble() }.filter { it.second != 0.0 }.toMap())
        return p.toString()
    }
    private fun commonMonomial(p: Poly): String {
        val vars = p.vars
        val common = vars.associateWith { v -> p.terms.keys.minOf { it[v] ?: 0 } }.filterValues { it > 0 }
        val coeffs = p.terms.values.map { fraction(it)?.let { f -> f.first } ?: it.roundToLong() }
        val g = coeffs.fold(0L) { a, b -> gcd(a, b) }.takeIf { it > 1 && p.terms.values.all { v -> abs(v - v.roundToLong()) < 1e-9 } } ?: 1L
        if (common.isEmpty() && g == 1L) return p.toString()
        val inner = Poly(p.terms.map { (m, c) -> m.mapValues { (k, e) -> e - (common[k] ?: 0) }.filterValues { it > 0 }.toSortedMap() as Map<String, Int> to c / g }.toMap())
        val mono = common.entries.joinToString("") { (v, e) -> if (e == 1) v else "$v${superscript(e)}" }
        return (if (g > 1) "$g" else "") + mono + "($inner)"
    }
}
