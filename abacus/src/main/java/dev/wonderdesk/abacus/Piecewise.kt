package dev.wonderdesk.abacus

import kotlin.math.abs

/**
 * Piecewise functions, like LaTeX `\begin{cases} 0 & x<0.25 \\ 1 & 0.25\le x\le1 \end{cases}`.
 *
 * [Smart.normalize] rewrites such input into one plain line, `pw[expr ? condition ; expr ? condition]`, that survives
 * being normalised again. Everything here works on that line.
 */
object Piecewise {
    class Piece(val expr: String, val cond: String, val f: (Double) -> Double, val test: (Double) -> Boolean)

    fun isPiecewise(s: String) = s.contains("pw[")

    /** The text between `pw[` and its matching `]`, plus what comes before and after it. */
    private fun split(s: String): Triple<String, String, String> {
        val start = s.indexOf("pw[")
        var depth = 0
        var end = -1
        for (i in start + 2 until s.length) {
            if (s[i] == '[') depth++ else if (s[i] == ']') { depth--; if (depth == 0) { end = i; break } }
        }
        if (end < 0) throw MathError("The pieces of this function are missing a closing bracket")
        return Triple(s.substring(0, start), s.substring(start + 3, end), s.substring(end + 1))
    }

    private fun topLevel(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>(); var depth = 0; var from = 0
        s.forEachIndexed { i, c -> if (c == '(' || c == '[') depth++ else if (c == ')' || c == ']') depth-- else if (c == sep && depth == 0) { out += s.substring(from, i); from = i + 1 } }
        out += s.substring(from); return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val OTHERWISE = Regex("^(otherwise|else|elsewhere|true|for all other x)?$")

    /** One condition such as `0.25 <= x <= 1`, `x > 1` or `x < 0 and x != -2` as a test on x. */
    private fun condition(text: String, degrees: Boolean): (Double) -> Boolean {
        val t = text.trim()
        if (OTHERWISE.matches(t)) return { true }
        val parts = t.split(Regex("\\s+and\\s+|∧|&&")).filter { it.isNotBlank() }
        val tests = parts.map { part ->
            val operands = mutableListOf<String>(); val ops = mutableListOf<String>()
            var depth = 0; var from = 0; var i = 0
            while (i < part.length) {
                val c = part[i]
                if (c == '(' || c == '[') depth++ else if (c == ')' || c == ']') depth--
                if (depth == 0 && (c == '<' || c == '>' || c == '=' || (c == '!' && i + 1 < part.length && part[i + 1] == '='))) {
                    val two = if (i + 1 < part.length && part[i + 1] == '=') part.substring(i, i + 2) else c.toString()
                    operands += part.substring(from, i); ops += if (two == "==") "=" else two
                    i += two.length; from = i; continue
                }
                i++
            }
            operands += part.substring(from)
            if (ops.isEmpty()) throw MathError("“$part” is not a condition on x, like x > 1")
            val fns = operands.map { compileFn(MathParser.expression(it.trim()), "x", degrees) }
            val test: (Double) -> Boolean = { x ->
                ops.indices.all { k ->
                    val a = fns[k](x); val b = fns[k + 1](x)
                    when (ops[k]) {
                        "<" -> a < b; "<=" -> a <= b + 1e-12; ">" -> a > b; ">=" -> a >= b - 1e-12
                        "=" -> abs(a - b) < 1e-9; else -> abs(a - b) >= 1e-9
                    }
                }
            }
            test
        }
        return { x -> tests.all { it(x) } }
    }

    fun parse(line: String, degrees: Boolean): List<Piece> {
        val (_, inner, _) = split(line)
        val pieces = topLevel(inner, ';').map { chunk ->
            val q = chunk.indexOf('?')
            val e = if (q >= 0) chunk.substring(0, q).trim() else chunk
            val c = if (q >= 0) chunk.substring(q + 1).trim() else ""
            if (e.isEmpty()) throw MathError("A piece of the function has no formula")
            Piece(e, c, compileFn(MathParser.expression(e), "x", degrees), condition(c, degrees))
        }
        if (pieces.isEmpty()) throw MathError("No pieces found in this function")
        return pieces
    }

    /** The whole function: the first piece whose condition holds, NaN where none does. */
    fun function(pieces: List<Piece>): (Double) -> Double = { x ->
        var v = Double.NaN
        for (p in pieces) if (p.test(x)) { v = p.f(x); break }
        v
    }

    private fun pretty(cond: String) = cond.ifBlank { "otherwise" }.replace("<=", " ≤ ").replace(">=", " ≥ ").replace("!=", " ≠ ").replace("<", " < ").replace(">", " > ").replace("  ", " ").replace(Regex("\\s*=\\s*"), " = ").replace("≤ =", "≤").trim()

    fun run(line: String, degrees: Boolean): SmartResult {
        val pieces = parse(line, degrees)
        val f = function(pieces)
        val shown = pieces.map { Simplify.pretty(MathParser.expression(it.expr)) to pretty(it.cond) }
        val answer = shown.joinToString("   ") { (e, c) -> "$e  if $c" }
        // Values at the edges and a few points between, so the jumps are easy to see.
        val edges = pieces.flatMap { p -> Regex("-?\\d+(?:\\.\\d+)?").findAll(p.cond).map { it.value.toDouble() }.toList() }.distinct().sorted()
        val probes = (edges + edges.flatMap { listOf(it - 0.5, it + 0.5) } + listOf(0.0, 1.0, 2.0, 5.0, 10.0)).distinct().sorted().take(40)
        val details = mutableListOf<String>()
        details += shown.mapIndexed { i, (e, c) -> "Piece ${i + 1}: y = $e when $c" }
        val values = probes.mapNotNull { x -> f(x).takeIf { it.isFinite() }?.let { "f(${nice(x)}) = ${nice(it)}" } }
        if (values.isNotEmpty()) details += "Values: " + values.take(12).joinToString(" · ")
        val gaps = probes.filter { !f(it).isFinite() }
        if (gaps.isNotEmpty()) details += "Undefined at x = " + gaps.take(5).joinToString(", ") { nice(it) }
        val graphLine = "y = " + line.substring(line.indexOf("pw["), line.lastIndexOf(']') + 1)
        return SmartResult(Task.Graph, shown.joinToString("; ") { (e, c) -> "$e if $c" }, answer, details, emptyList(),
            "A piecewise function uses a different formula on each part of the number line. For any x, Abacus finds the first piece whose condition is true and uses its formula. Open the graph to see the pieces and where they jump.",
            listOf(graphLine), listOf(Task.Calculate))
    }
}
