package dev.wonderdesk.abacus

import kotlin.math.*

/* ===================================================================================================
 * Smart input: type or paste anything (plain math, Unicode from a web page, LaTeX, or a short phrase)
 * and Abacus works out what to do with it, shows the answer, optional worked steps, and a short
 * explanation of the method.
 * ================================================================================================= */

enum class Task(val label: String) { Auto("Auto"), Calculate("Calculate"), Solve("Solve"), Graph("Graph"), Expand("Expand"), Factor("Factor"), Derive("d/dx"), Integrate("∫ dx") }

data class SmartResult(
    val task: Task,
    /** What Abacus understood, in friendly notation. */
    val understood: String,
    val answer: String,
    val details: List<String>,
    val steps: List<String>,
    /** Plain-language note on the method. */
    val explain: String,
    /** Functions of one variable that can be plotted. */
    val graphs: List<String>,
    /** Other things worth trying with the same input. */
    val alternatives: List<Task>,
)

object Smart {
    private val SUPER = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁻' to '-', '⁺' to '+', 'ⁿ' to 'n', 'ˣ' to 'x')
    private val VULGAR = mapOf("½" to "(1/2)", "⅓" to "(1/3)", "⅔" to "(2/3)", "¼" to "(1/4)", "¾" to "(3/4)", "⅕" to "(1/5)", "⅛" to "(1/8)")

    /** Turns pasted or typed text into something the parser reads. Pure; safe on any input. */
    fun normalize(raw: String): String {
        var s = raw.trim()
        // invisible and look-alike characters from web pages and PDFs
        s = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace("​", "")
            .replace('−', '-').replace('–', '-').replace('—', '-').replace('×', '*').replace('∗', '*').replace('⋅', '*').replace('·', '*').replace('÷', '/').replace('∕', '/')
            .replace('“', ' ').replace('”', ' ').replace('’', '\'').replace("≠", "!=").replace("≈", "=").replace("：", ":")
        VULGAR.forEach { (k, v) -> s = s.replace(k, v) }
        // LaTeX
        s = s.replace("$", "").replace("\\left", "").replace("\\right", "").replace("\\,", " ").replace("\\!", "").replace("\\ ", " ")
        repeat(4) { s = s.replace(Regex("\\\\[dt]?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "(($1)/($2))") }
        repeat(3) { s = s.replace(Regex("\\\\sqrt\\s*\\[([^\\]]*)\\]\\s*\\{([^{}]*)\\}"), "root($2,$1)").replace(Regex("\\\\sqrt\\s*\\{([^{}]*)\\}"), "sqrt($1)") }
        s = s.replace("\\cdot", "*").replace("\\times", "*").replace("\\div", "/").replace("\\pi", "π").replace("\\theta", "t")
            .replace(Regex("\\\\(sin|cos|tan|ln|log|exp|arcsin|arccos|arctan|sinh|cosh|tanh)")) { m -> m.groupValues[1].replace("arc", "a") }
            .replace("\\infty", "1e300").replace("{", "(").replace("}", ")").replace("\\", "")
        // superscripts: x² → x^2, x⁻¹ → x^(-1)
        s = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺ⁿˣ]+").replace(s) { m -> "^(" + m.value.map { SUPER[it] ?: it }.joinToString("") + ")" }
        s = s.replace("√", "sqrt").replace("∛", "cbrt").replace("**", "^")
        // words
        val lower = s.lowercase()
        s = lower
            .replace(Regex("\\bsquare root of\\b"), "sqrt ").replace(Regex("\\bcube root of\\b"), "cbrt ")
            .replace(Regex("\\bsquared\\b"), "^2").replace(Regex("\\bcubed\\b"), "^3").replace(Regex("\\bto the power of\\b"), "^")
            .replace(Regex("\\btimes\\b|\\bmultiplied by\\b"), "*").replace(Regex("\\bdivided by\\b|\\bover\\b"), "/")
            .replace(Regex("\\bplus\\b"), "+").replace(Regex("\\bminus\\b"), "-").replace(Regex("\\bequals\\b|\\bis equal to\\b"), "=")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*"), "($1/100)*")
            .replace(Regex("\\bpi\\b"), "π")
        s = s.replace(Regex("^(what is|what's|whats|calculate|compute|evaluate|work out|find)\\s+"), "")
        s = s.replace(Regex("\\s*=\\s*\\?\\s*$"), "").replace(Regex("\\s*=\\s*$"), "").replace(Regex("[?!.]+$"), "")
        // f(x) = … and g(t) = … become y = …
        s = s.replace(Regex("^[a-z]\\s*\\(\\s*([a-z])\\s*\\)\\s*="), "y =")
        return s.trim()
    }

    private val VERB = Regex("^(solve|graph|plot|draw|sketch|expand|simplify|factor|factorise|factorize|differentiate|derive|derivative( of)?|d/d[a-z]|integrate|integral( of)?|antiderivative( of)?|∫)\\s*(for [a-z]\\s*)?[:,]?\\s*")

    /** Splits "solve 2x+1=5", "derivative of x^2", "integrate x^2 from 0 to 3", etc. */
    fun interpret(raw: String, forced: Task): Pair<Task, String> {
        var s = normalize(raw)
        var task = forced
        val m = VERB.find(s)
        if (m != null) {
            val v = m.groupValues[1]
            if (forced == Task.Auto) task = when {
                v.startsWith("solve") -> Task.Solve
                v.startsWith("graph") || v.startsWith("plot") || v.startsWith("draw") || v.startsWith("sketch") -> Task.Graph
                v.startsWith("expand") || v.startsWith("simplify") -> Task.Expand
                v.startsWith("factor") -> Task.Factor
                v.startsWith("diff") || v.startsWith("deriv") || v.startsWith("d/d") -> Task.Derive
                else -> Task.Integrate
            }
            s = s.substring(m.range.last + 1).trim()
        }
        s = s.replace(Regex("\\bd[a-z]\\s*$"), "").trim()   // trailing "dx" of an integral
        return task to s
    }

    /** Separate equations: ";", new lines, " and ", or commas between equations. */
    private fun equations(s: String): List<String> {
        val parts = s.split(';', '\n').flatMap { it.split(Regex("\\s+and\\s+")) }.map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 1 && s.count { it == '=' } > 1 && ',' in s) return s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts
    }

    fun run(raw: String, forced: Task, degrees: Boolean): SmartResult {
        val (task0, text) = interpret(raw, forced)
        if (text.isBlank()) throw MathError("Type a calculation, an equation or a function")
        val eqs = equations(text)
        if (eqs.size > 1) return system(eqs, degrees)
        var range: Pair<String, String>? = null
        var body = text
        Regex("\\s+from\\s+(.+?)\\s+to\\s+(.+)$").find(body)?.let { r -> range = r.groupValues[1] to r.groupValues[2]; body = body.substring(0, r.range.first) }
        val parsed = MathParser.parse(body)
        val vars = variables(parsed.zeroForm) - "ans"
        val task = when {
            task0 != Task.Auto -> task0
            range != null -> Task.Integrate
            parsed.isEquation && isGraphable(parsed) -> Task.Graph
            parsed.isEquation -> Task.Solve
            vars.isEmpty() -> Task.Calculate
            else -> Task.Expand
        }
        return when (task) {
            Task.Calculate, Task.Auto -> calculate(parsed, degrees)
            Task.Solve -> solve(body, parsed, degrees)
            Task.Graph -> graph(parsed, degrees)
            Task.Expand -> expand(parsed, degrees)
            Task.Factor -> factor(parsed)
            Task.Derive -> derive(parsed)
            Task.Integrate -> integrate(parsed, range, degrees)
        }
    }

    /** "y = …" (or "… = y") with y not on the other side. */
    private fun isGraphable(p: Parsed): Boolean {
        val l = p.left; val r = p.right ?: return false
        return (l is Node.Var && l.name == "y" && "y" !in variables(r) && variables(r).size <= 1) || (r is Node.Var && r.name == "y" && "y" !in variables(l) && variables(l).size <= 1)
    }

    private fun graphRhs(p: Parsed): Node? {
        val r = p.right ?: return p.left.takeIf { variables(it).size == 1 }
        return when {
            p.left is Node.Var && (p.left as Node.Var).name == "y" -> r
            r is Node.Var && r.name == "y" -> p.left
            else -> null
        }
    }

    private fun show(n: Node) = Printer.show(n)

    /* ------------------------------------------------ tasks ------------------------------------------------ */

    private fun calculate(p: Parsed, degrees: Boolean): SmartResult {
        if (p.isEquation) {
            val s = Solver.solve(show(p.left) + "=" + show(p.right!!), degrees)
            return SmartResult(Task.Calculate, show(p.left) + " = " + show(p.right), s.headline, s.lines, emptyList(), "Both sides have no unknowns, so Abacus checks whether they're equal.", emptyList(), emptyList())
        }
        val v = Evaluator(degrees).eval(p.left)
        if (!v.isFinite()) throw MathError("The result is too large")
        val details = mutableListOf<String>()
        val n = nice(v); if (n != fmt(v)) details += "Exact: $n"
        if (abs(v) >= 1e6 || (abs(v) < 1e-3 && v != 0.0)) details += "Scientific: " + "%.6e".format(v).replace("e", " × 10^")
        if (v == floor(v) && abs(v) < 1e15 && abs(v) >= 2) factorInt(v.toLong())?.let { details += if (it.endsWith("prime")) it else "Prime factors: $it" }
        return SmartResult(Task.Calculate, show(p.left), fmt(v), details, arithmeticSteps(p.left, degrees),
            "Abacus follows the order of operations: brackets first, then powers and roots, then × and ÷ from left to right, then + and − from left to right.", emptyList(), emptyList())
    }

    private fun solve(text: String, p: Parsed, degrees: Boolean): SmartResult {
        val s = Solver.solve(text, degrees)
        val f = p.zeroForm
        val vars = variables(f)
        val x = if ("x" in vars) "x" else vars.firstOrNull() ?: "x"
        val poly = Poly.from(f, degrees)
        val deg = poly?.degree(x) ?: -1
        val explain = when {
            vars.size > 1 -> "With several letters, Abacus treats the others as fixed numbers and moves everything except $x to the other side, then divides by whatever multiplies $x."
            deg == 1 -> "A linear equation has $x only to the first power. Do the same thing to both sides until $x is alone: undo + with −, undo × with ÷."
            deg == 2 -> "A quadratic ax² + bx + c = 0 is solved with the quadratic formula x = (−b ± √(b² − 4ac)) / 2a. The part under the root, the discriminant, says how many real answers there are: positive means two, zero means one, negative means none."
            deg > 2 -> "For higher powers Abacus tries rational roots p/q (p divides the constant term, q divides the leading coefficient), divides out each one it finds, and solves what's left numerically."
            else -> "There's no simple algebra shortcut here, so Abacus narrows in on the values where both sides are equal, the same as zooming in on where two graphs cross."
        }
        val graph = if (vars.size == 1) listOfNotNull(exprInput(p.left).takeIf { p.isEquation }, p.right?.let { exprInput(it) }, exprInput(f).takeIf { !p.isEquation }) .distinct().filter { it.isNotBlank() && variables(MathParser.expression(it)).isNotEmpty() } else emptyList()
        val steps = s.steps.toMutableList()
        if (deg == 1 && steps.size < 4 && p.isEquation) steps.add(0, "Start with ${show(p.left)} = ${show(p.right!!)}")
        if (s.roots.isNotEmpty() && vars.size == 1) steps += "Check: put $x = ${nice(s.roots.first())} back in, both sides match"
        return SmartResult(Task.Solve, if (p.isEquation) "${show(p.left)} = ${show(p.right!!)}" else "${show(f)} = 0", s.headline, s.lines.filter { it != s.headline }, steps, explain,
            graph.map { if (it.contains(x)) it.replace(x, "x") else it }, listOfNotNull(Task.Graph.takeIf { graph.isNotEmpty() }, Task.Factor.takeIf { deg >= 2 }))
    }

    private fun system(eqs: List<String>, degrees: Boolean): SmartResult {
        val s = Solver.system(eqs)
        val lines = eqs.mapNotNull { e -> runCatching { MathParser.parse(e) }.getOrNull()?.let { graphRhs(it) ?: lineAsY(it) } }.map { exprInput(it) }
        return SmartResult(Task.Solve, eqs.joinToString("; "), s.headline, s.lines.filter { it != s.headline },
            s.steps.ifEmpty { listOf("Write the equations as rows of coefficients", "Eliminate one letter at a time by subtracting multiples of one row from another", "Back-substitute to find each letter") },
            "Each equation is a line (or plane). The answer is where they all meet. Abacus eliminates one letter at a time until each one has a value, the same as solving by substitution on paper.",
            if (lines.size == eqs.size) lines else emptyList(), emptyList())
    }

    /** a·x + b·y = c → y = (c − a·x)/b, for plotting two-variable linear equations. */
    private fun lineAsY(p: Parsed): Node? {
        val poly = Poly.from(p.zeroForm) ?: return null
        if (poly.vars != setOf("x", "y") || poly.degree("y") != 1) return null
        val ycoef = poly.terms.filterKeys { (it["y"] ?: 0) == 1 && it.size == 1 }.values.singleOrNull() ?: return null
        val rest = Poly(poly.terms.filterKeys { (it["y"] ?: 0) == 0 })
        return runCatching { MathParser.expression("-(" + Simplify.polyInput(rest) + ")/(" + fmt(ycoef) + ")") }.getOrNull()
    }

    private fun graph(p: Parsed, degrees: Boolean): SmartResult {
        val rhs = graphRhs(p) ?: lineAsY(p) ?: if (!p.isEquation) p.left else throw MathError("To graph, write it as y = …, for example y = x^2 − 4")
        val vars = variables(rhs)
        if (vars.size > 1) throw MathError("Graphs work with one variable, like y = 2x + 1")
        val v = vars.firstOrNull() ?: "x"
        val fn = exprInput(rhs).let { if (v != "x") it.replace(v, "x") else it }
        val details = mutableListOf<String>()
        runCatching {
            val f0 = Evaluator(degrees, mapOf(v to 0.0)).eval(rhs); if (f0.isFinite()) details += "Crosses the y-axis at y = ${nice(f0)}"
        }
        runCatching { val roots = Solver.solve(exprInput(rhs) + " = 0", degrees); if (roots.roots.isNotEmpty()) details += "Crosses the x-axis at " + roots.roots.take(4).joinToString(", ") { "$v = ${nice(it)}" } }
        runCatching { details += "Slope function: y′ = " + Simplify.pretty(Calculus.derivative(rhs, v)) }
        return SmartResult(Task.Graph, "y = ${show(rhs)}", "y = ${Simplify.pretty(rhs)}", details, emptyList(),
            "A graph shows every pair (x, y) that makes the equation true. Where it crosses the x-axis, y = 0: those are the solutions of ${show(rhs)} = 0.", listOf(fn), listOf(Task.Derive, Task.Solve))
    }

    private fun expand(p: Parsed, degrees: Boolean): SmartResult {
        if (p.isEquation) return solve(show(p.left) + "=" + show(p.right!!), p, degrees)
        val n = p.left
        val out = Simplify.pretty(n)
        val vars = variables(n)
        val details = mutableListOf<String>()
        Poly.from(n)?.let { poly -> runCatching { val x = if ("x" in poly.vars) "x" else poly.vars.first(); Factor.factor(poly, x).takeIf { it != out }?.let { details += "Factored: $it" } } }
        if (vars.size == 1) runCatching { details += "Derivative: " + Simplify.pretty(Calculus.derivative(n, vars.first())) }
        return SmartResult(Task.Expand, show(n), out, details, listOf("Multiply out each bracket: every term in one bracket times every term in the other", "Collect like terms (same letters to the same powers)", "Write the powers from highest to lowest"),
            "Expanding uses the distributive law, a(b + c) = ab + ac, on every bracket, then adds up terms that match.", if (vars.size == 1) listOf(exprInput(n).let { s -> vars.first().let { v -> if (v != "x") s.replace(v, "x") else s } }) else emptyList(),
            listOf(Task.Factor, Task.Derive, Task.Graph).filter { vars.size == 1 || it == Task.Factor })
    }

    private fun factor(p: Parsed): SmartResult {
        val n = p.zeroForm
        val poly = Poly.from(n) ?: throw MathError("Factoring works on polynomials, like x³ − x or 6x² + x − 2")
        val x = if ("x" in poly.vars) "x" else poly.vars.firstOrNull() ?: "x"
        val f = Factor.factor(poly, x)
        val coeffs = runCatching { poly.coefficients(x) }.getOrNull()
        val steps = mutableListOf("Write it out: $poly")
        coeffs?.let { c ->
            val ints = c.filter { it != 0.0 }.map { abs(it) }.filter { it == floor(it) }.map { it.toLong() }
            if (ints.size == c.count { it != 0.0 } && ints.isNotEmpty()) { val g = ints.reduce(::gcd); if (g > 1) steps += "Take out the common factor $g" }
            if (c.firstOrNull() == 0.0) steps += "Every term has $x, so take out $x"
            steps += "Try roots p/q: p divides ${nice(c.firstOrNull { it != 0.0 } ?: 0.0)}, q divides ${nice(c.last())}"
        }
        steps += "Each root r gives a factor ($x − r); divide it out and repeat"
        return SmartResult(Task.Factor, show(n), f, listOf("Expanded: $poly"), steps,
            "Factoring writes a polynomial as a product. If r makes it zero, then ($x − r) is a factor. That's why factoring and solving = 0 are two views of the same thing.",
            listOf(exprInput(n)), listOf(Task.Solve, Task.Graph))
    }

    private fun derive(p: Parsed): SmartResult {
        val n = graphRhs(p) ?: p.left
        val vars = variables(n)
        val x = if ("x" in vars || vars.isEmpty()) "x" else vars.first()
        val d = Calculus.derivative(n, x)
        val out = Simplify.pretty(d)
        val steps = mutableListOf<String>()
        termsOf(n).forEach { t -> runCatching { steps += "d/d$x ${show(t)} = ${Simplify.pretty(Calculus.derivative(t, x))}${ruleFor(t, x)?.let { " ($it)" } ?: ""}" } }
        steps += "Add the pieces: $out"
        return SmartResult(Task.Derive, "d/d$x ${show(n)}", "d/d$x = $out", emptyList(), steps,
            "The derivative is the slope of the graph at every point. Rules: power rule d/dx xⁿ = n·xⁿ⁻¹; constants vanish; product rule (uv)′ = u′v + uv′; chain rule (f(g(x)))′ = f′(g(x))·g′(x).",
            listOf(exprInput(n), exprInput(d)).map { s -> if (x != "x") s.replace(x, "x") else s }, listOf(Task.Integrate, Task.Graph))
    }

    private fun integrate(p: Parsed, range: Pair<String, String>?, degrees: Boolean): SmartResult {
        val n = graphRhs(p) ?: p.left
        val vars = variables(n)
        if (vars.size > 1) throw MathError("Integrals work with one variable")
        val x = vars.firstOrNull() ?: "x"
        val anti = Calculus.polyIntegral(n, x)
        val steps = mutableListOf<String>()
        termsOf(n).forEach { t -> Calculus.polyIntegral(t, x)?.let { steps += "∫ ${show(t)} d$x = ${it.removeSuffix(" + C")}" } }
        if (range != null) {
            val a = Evaluator(degrees).eval(MathParser.expression(range.first)); val b = Evaluator(degrees).eval(MathParser.expression(range.second))
            val v = Calculus.integrate({ Evaluator(degrees, mapOf(x to it)).eval(n) }, a, b)
            if (anti != null) { steps += "F($x) = ${anti.removeSuffix(" + C")}"; steps += "F(${fmt(b)}) − F(${fmt(a)}) = ${nice(v)}" }
            else steps += "No simple antiderivative, so Abacus adds up thin slices under the curve (adaptive Simpson's rule)"
            return SmartResult(Task.Integrate, "∫ from ${fmt(a)} to ${fmt(b)} of ${show(n)} d$x", nice(v).let { s -> if ('/' in s) "$s ≈ ${fmt(v)}" else fmt(v) }, listOfNotNull(anti?.let { "Antiderivative: $it" }), steps,
                "A definite integral is the signed area between the curve and the x-axis from ${fmt(a)} to ${fmt(b)}. Find an antiderivative F, then compute F(b) − F(a).", listOf(exprInput(n)), listOf(Task.Derive, Task.Graph))
        }
        anti ?: throw MathError("Abacus finds exact antiderivatives of polynomials. For other functions add a range, like “integrate sin(x) from 0 to π”")
        steps += "Add the constant of integration C"
        return SmartResult(Task.Integrate, "∫ ${show(n)} d$x", anti, emptyList(), steps,
            "Integration undoes differentiation. For each power, raise it by one and divide by the new power: xⁿ → xⁿ⁺¹/(n + 1). Add C because any constant disappears when you differentiate.",
            listOf(exprInput(n)), listOf(Task.Derive))
    }

    /* ----------------------------------------------- helpers ----------------------------------------------- */

    private fun termsOf(n: Node): List<Node> = when (n) {
        is Node.Add -> termsOf(n.a) + termsOf(n.b)
        is Node.Sub -> termsOf(n.a) + termsOf(n.b).map { Node.Neg(it) }
        else -> listOf(n)
    }.take(8)

    private fun ruleFor(t: Node, x: String): String? = when {
        x !in variables(t) -> "a constant's slope is 0"
        t is Node.Mul && x in variables(t.a) && x in variables(t.b) -> "product rule"
        t is Node.Div && x in variables(t.b) -> "quotient rule"
        t is Node.Fn && (t.args.firstOrNull() !is Node.Var) -> "chain rule"
        t is Node.Pow || (t is Node.Mul && t.b is Node.Pow) -> "power rule"
        else -> null
    }

    private fun factorInt(n: Long): String? {
        var m = abs(n); val out = mutableListOf<String>(); var p = 2L
        if (m > 1_000_000_000_000L) return null
        while (p * p <= m) { var k = 0; while (m % p == 0L) { m /= p; k++ }; if (k > 0) out += if (k > 1) "$p${superscript(k)}" else "$p"; p++ }
        if (m > 1) out += "$m"
        return if (out.size == 1 && !out[0].any { it in "⁰¹²³⁴⁵⁶⁷⁸⁹" }) "$n is prime" else out.joinToString(" × ")
    }

    /** Back to text the parser accepts (for the graph tab). */
    fun exprInput(n: Node): String = Poly.from(n)?.let { Simplify.polyInput(it) } ?: raw(n)

    private fun raw(n: Node): String = when (n) {
        is Node.Num -> if (n.v < 0) "(${fmt(n.v)})" else fmt(n.v)
        is Node.Var -> if (n.name == "π") "pi" else n.name
        is Node.Neg -> "-(${raw(n.a)})"
        is Node.Add -> "${raw(n.a)}+${raw(n.b)}"
        is Node.Sub -> "${raw(n.a)}-(${raw(n.b)})"
        is Node.Mul -> "(${raw(n.a)})*(${raw(n.b)})"
        is Node.Div -> "(${raw(n.a)})/(${raw(n.b)})"
        is Node.Pow -> "(${raw(n.a)})^(${raw(n.b)})"
        is Node.Fact -> "(${raw(n.a)})!"
        is Node.Fn -> n.name + "(" + n.args.joinToString(",") { raw(it) } + ")"
    }

    /**
     * Worked arithmetic: evaluates one operation at a time in the order a person would,
     * printing the whole expression after each step. Limited to 12 steps.
     */
    fun arithmeticSteps(root: Node, degrees: Boolean): List<String> {
        if (variables(root).any { it !in CONSTANTS && it != "ans" }) return emptyList()
        val out = mutableListOf<String>()
        var n = root
        val ev = Evaluator(degrees)
        fun isLeaf(x: Node) = x is Node.Num
        // reduce the first node (in evaluation order) whose children are all numbers
        fun reduce(x: Node): Pair<Node, Boolean> {
            fun kids(): List<Node> = when (x) { is Node.Neg -> listOf(x.a); is Node.Fact -> listOf(x.a); is Node.Add -> listOf(x.a, x.b); is Node.Sub -> listOf(x.a, x.b); is Node.Mul -> listOf(x.a, x.b)
                is Node.Div -> listOf(x.a, x.b); is Node.Pow -> listOf(x.a, x.b); is Node.Fn -> x.args; else -> emptyList() }
            if (x is Node.Num) return x to false
            if (x is Node.Var) return Node.Num(ev.eval(x)) to true
            val ks = kids()
            if (ks.all(::isLeaf)) return Node.Num(ev.eval(x)) to true
            // powers and brackets deeper in the tree go first, then left to right
            val order = when (x) { is Node.Add, is Node.Sub -> ks.indices.sortedByDescending { depth(ks[it]) }; else -> ks.indices.toList() }
            for (i in order) {
                val (r, changed) = reduce(ks[i])
                if (changed) {
                    val nk = ks.toMutableList().also { it[i] = r }
                    return when (x) {
                        is Node.Neg -> Node.Neg(nk[0]); is Node.Fact -> Node.Fact(nk[0]); is Node.Add -> Node.Add(nk[0], nk[1]); is Node.Sub -> Node.Sub(nk[0], nk[1])
                        is Node.Mul -> Node.Mul(nk[0], nk[1]); is Node.Div -> Node.Div(nk[0], nk[1]); is Node.Pow -> Node.Pow(nk[0], nk[1]); is Node.Fn -> Node.Fn(x.name, nk); else -> x
                    } to true
                }
            }
            return x to false
        }
        var guard = 0
        while (n !is Node.Num && guard++ < 12) {
            val (r, changed) = runCatching { reduce(n) }.getOrElse { return out }
            if (!changed) break
            n = r
            out += "= " + Printer.show(n).let { s -> if (n is Node.Num) fmt((n as Node.Num).v) else s }
        }
        return if (out.size <= 1) emptyList() else listOf(Printer.show(root)) + out
    }

    private fun depth(n: Node): Int = when (n) {
        is Node.Num, is Node.Var -> 0
        is Node.Neg -> 1 + depth(n.a); is Node.Fact -> 1 + depth(n.a)
        is Node.Add -> 1 + max(depth(n.a), depth(n.b)); is Node.Sub -> 1 + max(depth(n.a), depth(n.b))
        is Node.Mul -> 1 + max(depth(n.a), depth(n.b)); is Node.Div -> 1 + max(depth(n.a), depth(n.b)); is Node.Pow -> 2 + max(depth(n.a), depth(n.b))
        is Node.Fn -> 2 + (n.args.maxOfOrNull { depth(it) } ?: 0)
    }
}

/* ===================================== fast compiled functions ===================================== */

/**
 * Compiles an expression of one variable into a closure for plotting. No allocation and no
 * exceptions per point: anything undefined simply returns NaN, so panning and zooming stay smooth.
 */
fun compileFn(n: Node, v: String, degrees: Boolean): (Double) -> Double {
    val toRad = if (degrees) PI / 180 else 1.0
    val fromRad = if (degrees) 180 / PI else 1.0
    fun c(n: Node): (Double) -> Double = when (n) {
        is Node.Num -> { val k = n.v; { k } }
        is Node.Var -> if (n.name == v) { x -> x } else { val k = CONSTANTS[n.name] ?: Double.NaN; { k } }
        is Node.Neg -> { val a = c(n.a); { x -> -a(x) } }
        is Node.Add -> { val a = c(n.a); val b = c(n.b); { x -> a(x) + b(x) } }
        is Node.Sub -> { val a = c(n.a); val b = c(n.b); { x -> a(x) - b(x) } }
        is Node.Mul -> { val a = c(n.a); val b = c(n.b); { x -> a(x) * b(x) } }
        is Node.Div -> { val a = c(n.a); val b = c(n.b); { x -> val d = b(x); if (d == 0.0) Double.NaN else a(x) / d } }
        is Node.Pow -> { val a = c(n.a); val b = c(n.b); { x ->
            val base = a(x); val e = b(x)
            if (base < 0 && e != floor(e)) { val inv = 1 / e; if (abs(inv - inv.roundToLong()) < 1e-9 && inv.roundToLong() % 2L != 0L) -(-base).pow(e) else Double.NaN } else base.pow(e)
        } }
        is Node.Fact -> { val a = c(n.a); { x -> runCatching { factorial(a(x)) }.getOrDefault(Double.NaN) } }
        is Node.Fn -> {
            val args = n.args.map { c(it) }
            val a = args.firstOrNull() ?: { _ -> Double.NaN }
            when (n.name) {
                "sin" -> { x -> sin(a(x) * toRad) }; "cos" -> { x -> cos(a(x) * toRad) }; "tan" -> { x -> tan(a(x) * toRad) }
                "sec" -> { x -> 1 / cos(a(x) * toRad) }; "csc" -> { x -> 1 / sin(a(x) * toRad) }; "cot" -> { x -> 1 / tan(a(x) * toRad) }
                "asin" -> { x -> asin(a(x)) * fromRad }; "acos" -> { x -> acos(a(x)) * fromRad }
                "atan" -> if (args.size == 2) { x -> atan2(args[0](x), args[1](x)) * fromRad } else { x -> atan(a(x)) * fromRad }
                "sinh" -> { x -> sinh(a(x)) }; "cosh" -> { x -> cosh(a(x)) }; "tanh" -> { x -> tanh(a(x)) }
                "asinh" -> { x -> asinh(a(x)) }; "acosh" -> { x -> acosh(a(x)) }; "atanh" -> { x -> atanh(a(x)) }
                "sqrt" -> { x -> sqrt(a(x)) }; "cbrt" -> { x -> cbrt(a(x)) }
                "root" -> { x -> val b = args.getOrNull(1)?.invoke(x) ?: 2.0; val base = a(x); if (base < 0 && b.roundToLong() % 2L != 0L) -(-base).pow(1 / b) else base.pow(1 / b) }
                "ln" -> { x -> ln(a(x)) }
                "log" -> if (args.size == 2) { x -> ln(args[0](x)) / ln(args[1](x)) } else { x -> log10(a(x)) }
                "log2" -> { x -> log2(a(x)) }; "exp" -> { x -> exp(a(x)) }; "abs" -> { x -> abs(a(x)) }
                "floor" -> { x -> floor(a(x)) }; "ceil" -> { x -> ceil(a(x)) }; "round" -> { x -> Math.round(a(x)).toDouble() }
                "sign" -> { x -> sign(a(x)) }; "frac" -> { x -> val y = a(x); y - truncate(y) }
                "deg" -> { x -> Math.toDegrees(a(x)) }; "rad" -> { x -> Math.toRadians(a(x)) }
                "min" -> { x -> args.minOf { it(x) } }; "max" -> { x -> args.maxOf { it(x) } }
                "mod" -> { x -> val p = args[0](x); val q = args.getOrNull(1)?.invoke(x) ?: Double.NaN; val r = p % q; if (r != 0.0 && (r < 0) != (q < 0)) r + q else r }
                else -> { x -> runCatching { Evaluator(degrees, mapOf(v to x)).eval(n) }.getOrDefault(Double.NaN) }
            }
        }
    }
    return c(n)
}
