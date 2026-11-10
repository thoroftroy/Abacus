package dev.wonderdesk.abacus

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wonderdesk.shared.*
import org.json.JSONArray
import kotlin.math.*

class MainActivity : WonderActivity() {
    @Composable override fun Content() = AbacusApp()
}

private const val ABOUT = "Abacus is a scientific calculator with algebra, graphing and unit conversion. " +
    "It solves equations and systems, expands and factors polynomials, takes derivatives and integrals, and plots up to three functions."

@Composable
fun AbacusApp() {
    val context = LocalContext.current
    val prefs = remember { context.prefs("abacus") }
    var tab by rememberSaveable { mutableStateOf(prefs.getString("tab", "calc") ?: "calc") }
    var showHistory by remember { mutableStateOf(false) }
    var degrees by remember { mutableStateOf(prefs.getBoolean("degrees", false)) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var calcInput by rememberSaveable { mutableStateOf("") }
    var algebraInput by rememberSaveable { mutableStateOf("") }
    var graphSeed by remember { mutableStateOf<String?>(null) }
    val setTab: (String) -> Unit = { tab = it; prefs.edit().putString("tab", it).apply() }
    val addHistory: (String, String) -> Unit = { expr, res ->
        history = (listOf("$expr = $res") + history.filter { it != "$expr = $res" }).take(100)
        prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    WonderApp(
        appName = "Abacus", about = ABOUT,
        tabs = listOf(
            WonderTab("calc", "Calculate", WonderIcons.Calculate),
            WonderTab("algebra", "Algebra", WonderIcons.Functions),
            WonderTab("graph", "Graph", WonderIcons.ShowChart),
            WonderTab("convert", "Convert", WonderIcons.SwapHoriz),
        ),
        selectedTab = tab, onTab = setTab,
        title = when (tab) { "algebra" -> "Algebra"; "graph" -> "Graph"; "convert" -> "Convert"; else -> "Abacus" },
        actions = {
            if (tab == "calc") ToolbarButton(WonderIcons.History, "History", { showHistory = true })
        },
        settings = {
            Panel(title = "Calculator") {
                SwitchRow("Use degrees for trigonometry", degrees, { degrees = it; prefs.edit().putBoolean("degrees", it).apply() },
                    subtitle = if (degrees) "sin(90) = 1" else "Radians: sin(π/2) = 1", icon = WonderIcons.Straighten, tint = Color(0xFFE08A1E))
                PanelDivider()
                ListRow("Clear calculation history", icon = WonderIcons.DeleteSweep, danger = true, onClick = {
                    history = emptyList(); prefs.edit().remove("history").apply()
                })
            }
        }
    ) {
        when (tab) {
            "calc" -> CalculatorTab(calcInput, { calcInput = it }, degrees, { degrees = it; prefs.edit().putBoolean("degrees", it).apply() }, history.firstOrNull()?.substringAfterLast(" = "), addHistory)
            "algebra" -> AlgebraTab(algebraInput, { algebraInput = it }, degrees) { fn -> graphSeed = fn; setTab("graph") }
            "graph" -> GraphTab(prefs, degrees, graphSeed) { graphSeed = null }
            else -> ConverterTab(prefs)
        }
    }
    if (showHistory) HistoryDialog(history, onDismiss = { showHistory = false }, onPick = { calcInput = it.substringBefore(" = "); showHistory = false }, onClear = {
        history = emptyList(); prefs.edit().remove("history").apply()
    })
}

private fun loadHistory(prefs: android.content.SharedPreferences): List<String> = runCatching {
    val a = JSONArray(prefs.getString("history", "[]")); (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

/* ======================================== Calculator ======================================== */

private enum class KeyKind { Digit, Op, Fn, Equals, Clear }
private data class Key(val label: String, val insert: String, val kind: KeyKind, val desc: String = label)

@Composable
private fun CalculatorTab(input: String, setInput: (String) -> Unit, degrees: Boolean, setDegrees: (Boolean) -> Unit, lastAnswer: String?, addHistory: (String, String) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var sci by rememberSaveable { mutableStateOf(context.prefs("abacus").getBoolean("sci", true)) }
    val env = remember(lastAnswer) { lastAnswer?.let { a -> runCatching { mapOf("ans" to Evaluator().eval(MathParser.expression(a.replace("×10^", "*10^")))) }.getOrNull() } ?: emptyMap() }
    val preview = remember(input, degrees, env) {
        if (input.isBlank()) null else runCatching {
            val v = Evaluator(degrees, env).eval(MathParser.expression(input))
            fmt(v)
        }.getOrNull()
    }
    fun evaluate() {
        if (input.isBlank()) return
        try {
            val v = Evaluator(degrees, env).eval(MathParser.expression(input))
            if (!v.isFinite()) throw MathError("The result is too large")
            val r = fmt(v)
            addHistory(input, r)
            result = r; error = null
        } catch (e: MathError) { error = e.message; result = null
        } catch (e: Exception) { error = "That doesn't look like a complete calculation"; result = null }
    }
    fun press(k: Key) {
        when (k.kind) {
            KeyKind.Clear -> if (k.label == "AC") { setInput(""); result = null; error = null } else { setInput(input.dropLast(if (input.endsWith("(") && input.length >= 2 && input[input.length - 2].isLetter()) input.takeLastWhile { it.isLetter() || it == '(' }.length else 1)); result = null; error = null }
            KeyKind.Equals -> evaluate()
            else -> {
                var base = input
                if (result != null) {
                    // After "=", operators continue from the answer; anything else starts fresh.
                    base = if (k.kind == KeyKind.Op && k.insert !in listOf("(", ")")) "ans" else ""
                    result = null
                }
                error = null
                if (base.length < 400) setInput(base + k.insert)
            }
        }
    }
    val p = wonderPalette()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- display ----
        val lcd = when (p.look) {
            WonderLook.AQUA -> listOf(Color(0xFFF3F7EC), Color(0xFFDDE6CF)) to Color(0xFF1E2A12)
            WonderLook.BLISS -> listOf(Color.White, Color(0xFFF1EFE2)) to Color.Black
            WonderLook.DARK -> listOf(Color(0xFF1B2220), Color(0xFF141A18)) to Color(0xFFD9F2E3)
            WonderLook.SUPER -> listOf(Color(0xFF160C2E), Color(0xFF0B0619)) to Color(0xFF7DF9FF)
        }
        val shape = RoundedCornerShape(p.panelRadius + 2.dp)
        Column(
            Modifier.fillMaxWidth().weight(0.9f).clip(shape).background(Brush.verticalGradient(lcd.first)).border(1.dp, p.panelBorder, shape).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (degrees) "DEG" else "RAD", color = lcd.second.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { setDegrees(!degrees) }.padding(4.dp))
                Spacer(Modifier.weight(1f))
                Text(if (sci) "Basic" else "Scientific", color = p.accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { sci = !sci; context.prefs("abacus").edit().putBoolean("sci", sci).apply() }.padding(4.dp))
            }
            val scroll = rememberScrollState()
            LaunchedEffect(input) { scroll.scrollTo(scroll.maxValue) }
            Text(
                pretty(input).ifEmpty { "0" }, Modifier.fillMaxWidth().horizontalScroll(scroll),
                color = lcd.second.copy(alpha = if (result != null) .55f else 1f), textAlign = TextAlign.End, maxLines = 1,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (result != null) 22.sp else 30.sp, fontFamily = FontFamily.SansSerif)
            )
            val big = result ?: preview
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (result != null) IconButton(onClick = { context.copyText("Result", result!!); toast("Copied $result") }) { Icon(WonderIcons.ContentCopy, "Copy result", tint = lcd.second.copy(alpha = .6f)) }
                Text(
                    when { error != null -> error!!; big != null -> "= $big"; else -> "" },
                    Modifier.weight(1f), color = if (error != null) p.danger else lcd.second.copy(alpha = if (result != null) 1f else .5f), textAlign = TextAlign.End,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (error != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.displaySmall.copy(fontSize = autoSize(big ?: "", result != null), fontWeight = if (result != null) FontWeight.Medium else FontWeight.Normal)
                )
            }
        }
        // ---- keypad ----
        val sciRows = listOf(
            listOf(Key("sin", "sin(", KeyKind.Fn), Key("cos", "cos(", KeyKind.Fn), Key("tan", "tan(", KeyKind.Fn), Key("ln", "ln(", KeyKind.Fn), Key("log", "log(", KeyKind.Fn)),
            listOf(Key("sin⁻¹", "asin(", KeyKind.Fn, "arcsine"), Key("cos⁻¹", "acos(", KeyKind.Fn, "arccosine"), Key("tan⁻¹", "atan(", KeyKind.Fn, "arctangent"), Key("eˣ", "e^(", KeyKind.Fn, "e to the power"), Key("10ˣ", "10^(", KeyKind.Fn, "ten to the power")),
            listOf(Key("√", "√(", KeyKind.Fn, "square root"), Key("x²", "^2", KeyKind.Op, "squared"), Key("xʸ", "^", KeyKind.Op, "power"), Key("π", "π", KeyKind.Fn, "pi"), Key("e", "e", KeyKind.Fn)),
            listOf(Key("x!", "!", KeyKind.Op, "factorial"), Key("%", "%", KeyKind.Op, "percent"), Key("1/x", "1/(", KeyKind.Fn, "reciprocal"), Key("nCr", "nCr(", KeyKind.Fn, "combinations"), Key("Ans", "ans", KeyKind.Fn, "previous answer")),
        )
        val rows = listOf(
            listOf(Key("AC", "", KeyKind.Clear, "All clear"), Key("(", "(", KeyKind.Op), Key(")", ")", KeyKind.Op), Key("÷", "/", KeyKind.Op, "divide")),
            listOf(Key("7", "7", KeyKind.Digit), Key("8", "8", KeyKind.Digit), Key("9", "9", KeyKind.Digit), Key("×", "*", KeyKind.Op, "multiply")),
            listOf(Key("4", "4", KeyKind.Digit), Key("5", "5", KeyKind.Digit), Key("6", "6", KeyKind.Digit), Key("−", "-", KeyKind.Op, "minus")),
            listOf(Key("1", "1", KeyKind.Digit), Key("2", "2", KeyKind.Digit), Key("3", "3", KeyKind.Digit), Key("+", "+", KeyKind.Op, "plus")),
            listOf(Key("0", "0", KeyKind.Digit), Key(".", ".", KeyKind.Digit, "point"), Key("⌫", "", KeyKind.Clear, "Backspace"), Key("=", "", KeyKind.Equals, "equals")),
        )
        if (sci) Column(Modifier.fillMaxWidth().weight(1.25f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sciRows.forEach { row -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { k -> CalcKey(k, Modifier.weight(1f).fillMaxHeight(), small = true) { press(k) } } } }
        }
        Column(Modifier.fillMaxWidth().weight(2.3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { k -> CalcKey(k, Modifier.weight(1f).fillMaxHeight()) { press(k) } } } }
        }
    }
}

private fun autoSize(s: String, final: Boolean) = when { s.length > 16 -> 24.sp; s.length > 11 -> 30.sp; final -> 42.sp; else -> 34.sp }

/** Display form of the raw input: nicer operator glyphs. */
private fun pretty(s: String) = s.replace("*", "×").replace("/", "÷").replace("-", "−").replace("asin(", "sin⁻¹(").replace("acos(", "cos⁻¹(").replace("atan(", "tan⁻¹(")
    .replace("ans", "Ans").replace("nCr(", "nCr(")

@Composable
private fun CalcKey(k: Key, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    val p = wonderPalette()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (top, bottom, text) = when (k.kind) {
        KeyKind.Equals -> Triple(p.accent.lighten(.35f), p.accent.darken(.1f), p.onAccent)
        KeyKind.Clear -> if (k.label == "AC") Triple(Color(0xFFF7A35C), Color(0xFFDB6A1F), Color.White) else keyFace(p, false)
        KeyKind.Op -> if (p.dark) Triple(Color(0xFF4C5D74), Color(0xFF34435A), Color(0xFFE3EEFF)) else Triple(Color(0xFFE8F1FC), Color(0xFFC3D5EC), Color(0xFF173A66))
        KeyKind.Fn -> if (p.dark) Triple(Color(0xFF3A3F47), Color(0xFF2C3036), p.text) else Triple(Color(0xFFF4F5F7), Color(0xFFDDE0E4), Color(0xFF30343A))
        KeyKind.Digit -> keyFace(p, true)
    }
    val shape = RoundedCornerShape(if (p.look == WonderLook.BLISS) 4.dp else if (small) 10.dp else 14.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(if (pressed) listOf(bottom, top) else listOf(top, bottom)))
            .border(1.dp, if (p.dark) Color.Black.copy(alpha = .4f) else Color(0xFF8D99A6).copy(alpha = .7f), shape)
            .clickable(interaction, LocalIndication()) { onClick() }
            .semantics { contentDescription = k.desc },
        contentAlignment = Alignment.Center
    ) {
        // gloss
        Box(Modifier.fillMaxWidth().fillMaxHeight(.48f).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.dark) .08f else .5f), Color.White.copy(alpha = 0f)))))
        if (k.label == "⌫") Icon(WonderIcons.Backspace, null, tint = text, modifier = Modifier.size(24.dp))
        else Text(k.label, color = text, style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable private fun LocalIndication() = androidx.compose.material.ripple.rememberRipple()

private fun keyFace(p: WonderPalette, digit: Boolean) = when {
    p.look == WonderLook.SUPER -> Triple(Color(0xFF2A1E4D), Color(0xFF1A1230), Color(0xFFF2ECFF))
    p.dark -> Triple(Color(0xFF50565F), Color(0xFF3B4048), Color.White)
    else -> Triple(Color.White, Color(0xFFE4E7EB), Color(0xFF16191D))
}

@Composable
private fun HistoryDialog(history: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit, onClear: () -> Unit) {
    val p = wonderPalette()
    WonderDialog(onDismissRequest = onDismiss, title = { Text("History") }, text = {
        if (history.isEmpty()) Text("Calculations you finish with = appear here.", color = p.secondaryText)
        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(history) { _, line ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPick(line) }.padding(vertical = 10.dp, horizontal = 6.dp)) {
                    Text(pretty(line.substringBefore(" = ")), color = p.secondaryText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("= " + line.substringAfterLast(" = "), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = p.separator)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (history.isNotEmpty()) ({ TextButton(onClick = onClear) { Text("Clear", color = p.danger) } }) else null)
}

/* ========================================= Algebra ========================================== */

private enum class AlgebraMode(val label: String) { Solve("Solve"), Simplify("Expand"), Factor("Factor"), Derive("d/dx"), Integrate("∫ dx") }

private data class AlgebraResult(val headline: String, val lines: List<String>, val steps: List<String>, val graphable: String?)

@Composable
private fun AlgebraTab(input: String, setInput: (String) -> Unit, degrees: Boolean, onGraph: (String) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val p = wonderPalette()
    var mode by rememberSaveable { mutableStateOf(AlgebraMode.Solve) }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    val outcome: Result<AlgebraResult>? = remember(input, mode, from, to, degrees) {
        if (input.isBlank()) null else runCatching { runAlgebra(input, mode, from, to, degrees) }
    }
    Page {
        Panel {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(AlgebraMode.entries.map { it.label }, mode.ordinal) { mode = AlgebraMode.entries[it] }
                Field(input, setInput, when (mode) {
                    AlgebraMode.Solve -> "Equation, e.g. x² − 5x + 6 = 0"
                    AlgebraMode.Simplify -> "Expression to expand"
                    AlgebraMode.Factor -> "Polynomial to factor"
                    AlgebraMode.Derive -> "Function of x"
                    AlgebraMode.Integrate -> "Function of x"
                }, singleLine = false, keyboard = KeyboardType.Ascii, textStyle = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("x", "y", "=", "^", "(", ")", "√", "π", "²", ";").forEach { s ->
                        Box(Modifier.size(width = 40.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(8.dp))
                            .clickable { setInput(input + if (s == ";") "; " else s) }, contentAlignment = Alignment.Center) { Text(s, style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (mode == AlgebraMode.Integrate) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(from, { from = it }, "From (optional)", Modifier.weight(1f), keyboard = KeyboardType.Ascii)
                    Field(to, { to = it }, "To (optional)", Modifier.weight(1f), keyboard = KeyboardType.Ascii)
                }
            }
        }
        when {
            outcome == null -> AlgebraExamples(mode) { ex, m -> setInput(ex); mode = m; showSteps = false }
            outcome.isFailure -> Panel {
                ListRow(
                    (outcome.exceptionOrNull() as? MathError)?.message ?: "Abacus couldn't read that. Check the brackets and operators.",
                    icon = WonderIcons.Info, tint = Color(0xFFE39A12)
                )
            }
            else -> {
                val r = outcome.getOrThrow()
                Panel(title = "Result") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.headline, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                        r.lines.filter { it != r.headline }.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = p.secondaryText) }
                    }
                    PanelDivider(0.dp)
                    Row(Modifier.fillMaxWidth().padding(6.dp)) {
                        WButton("Copy", { context.copyText("Abacus result", r.headline); toast("Copied") }, icon = WonderIcons.ContentCopy, kind = ButtonKind.Plain)
                        if (r.graphable != null) WButton("Graph", { onGraph(r.graphable) }, icon = WonderIcons.ShowChart, kind = ButtonKind.Plain)
                        Spacer(Modifier.weight(1f))
                        if (r.steps.isNotEmpty()) WButton(if (showSteps) "Hide steps" else "Show steps", { showSteps = !showSteps }, kind = ButtonKind.Plain)
                    }
                }
                if (showSteps && r.steps.isNotEmpty()) Panel(title = "Steps") {
                    r.steps.forEachIndexed { i, s ->
                        if (i > 0) PanelDivider(48.dp)
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) { Text("${i + 1}", color = p.onAccent, style = MaterialTheme.typography.labelMedium) }
                            Spacer(Modifier.width(10.dp))
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun runAlgebra(input: String, mode: AlgebraMode, from: String, to: String, degrees: Boolean): AlgebraResult {
    val text = input.trim()
    fun graphFor(node: Node): String? = if (variables(node).let { it.size == 1 }) Printer.show(node).let { exprText(node) } else null
    return when (mode) {
        AlgebraMode.Solve -> {
            val s = Solver.solve(text, degrees)
            val parsed = if (!text.contains(';') && !text.contains('\n')) runCatching { MathParser.parse(text) }.getOrNull() else null
            AlgebraResult(s.headline, s.lines, s.steps, parsed?.let { graphFor(it.zeroForm) })
        }
        AlgebraMode.Simplify -> {
            val n = MathParser.expression(text)
            val out = Simplify.pretty(n)
            val vars = variables(n)
            val lines = mutableListOf(out)
            if (vars.isEmpty()) lines += "≈ " + fmt(Evaluator(degrees).eval(n))
            AlgebraResult(out, lines, listOf("Multiply out every bracket", "Collect like terms"), graphFor(n))
        }
        AlgebraMode.Factor -> {
            val n = MathParser.expression(text)
            val poly = Poly.from(n) ?: throw MathError("Factoring works on polynomials, like x³ − x or 6x² + x − 2")
            val x = if ("x" in poly.vars) "x" else poly.vars.firstOrNull() ?: "x"
            val f = Factor.factor(poly, x)
            AlgebraResult(f, listOf(f, "Expanded: $poly"), listOf("Expanded form: $poly", "Take out common factors", "Find rational roots p/q and divide them out", "What remains has no rational roots"), graphFor(n))
        }
        AlgebraMode.Derive -> {
            val n = MathParser.expression(text)
            val x = variables(n).let { if ("x" in it || it.isEmpty()) "x" else it.first() }
            val d = Calculus.derivative(n, x)
            val out = Simplify.pretty(d)
            AlgebraResult("d/d$x = $out", listOf("d/d$x = $out"), listOf("Differentiate term by term using the sum, product, quotient and chain rules", "Simplify"), graphFor(d))
        }
        AlgebraMode.Integrate -> {
            val n = MathParser.expression(text)
            val x = variables(n).let { if ("x" in it || it.isEmpty()) "x" else it.first() }
            if (variables(n).size > 1) throw MathError("Integrals work with one variable")
            if (from.isNotBlank() && to.isNotBlank()) {
                val a = Evaluator(degrees).eval(MathParser.expression(from)); val b = Evaluator(degrees).eval(MathParser.expression(to))
                val v = Calculus.integrate({ Evaluator(degrees, mapOf(x to it)).eval(n) }, a, b)
                val anti = Calculus.polyIntegral(n, x)
                AlgebraResult("= ${nice(v).let { s -> if (s.contains('/')) "$s ≈ ${fmt(v)}" else fmt(v) }}", listOfNotNull("∫ from ${fmt(a)} to ${fmt(b)} = ${fmt(v)}", anti?.let { "Antiderivative: $it" }),
                    listOfNotNull(anti?.let { "Antiderivative F($x) = $it" }, "Evaluate F(${fmt(b)}) − F(${fmt(a)})".takeIf { anti != null } ?: "Adaptive Simpson's rule (numeric)"), graphFor(n))
            } else {
                val anti = Calculus.polyIntegral(n, x) ?: throw MathError("Abacus finds exact antiderivatives of polynomials. For other functions, enter From and To for a definite integral")
                AlgebraResult(anti, listOf(anti), listOf("Raise each power by one and divide by the new power", "Add the constant of integration C"), graphFor(n))
            }
        }
    }
}

/** Expression back to input text that the parser accepts. */
private fun exprText(n: Node): String = when (n) {
    is Node.Num -> if (n.v < 0) "(${fmt(n.v)})" else fmt(n.v)
    is Node.Var -> n.name
    is Node.Neg -> "-(${exprText(n.a)})"
    is Node.Add -> "(${exprText(n.a)})+(${exprText(n.b)})"
    is Node.Sub -> "(${exprText(n.a)})-(${exprText(n.b)})"
    is Node.Mul -> "(${exprText(n.a)})*(${exprText(n.b)})"
    is Node.Div -> "(${exprText(n.a)})/(${exprText(n.b)})"
    is Node.Pow -> "(${exprText(n.a)})^(${exprText(n.b)})"
    is Node.Fact -> "(${exprText(n.a)})!"
    is Node.Fn -> n.name + "(" + n.args.joinToString(",") { exprText(it) } + ")"
}.let { s -> Poly.from(n)?.let { Simplify.polyInput(it) } ?: s }

@Composable
private fun AlgebraExamples(mode: AlgebraMode, pick: (String, AlgebraMode) -> Unit) {
    val examples = listOf(
        Triple("Linear equation", "2x + 3 = 11", AlgebraMode.Solve),
        Triple("Quadratic", "x^2 - 5x + 6 = 0", AlgebraMode.Solve),
        Triple("Irrational roots", "x^2 + x - 1 = 0", AlgebraMode.Solve),
        Triple("System of equations", "2x + y = 5; x - y = 1", AlgebraMode.Solve),
        Triple("Solve for a letter", "y = mx + b", AlgebraMode.Solve),
        Triple("Exponential", "2^x = 50", AlgebraMode.Solve),
        Triple("Expand", "(x + 2)^3", AlgebraMode.Simplify),
        Triple("Factor", "x^3 - 4x", AlgebraMode.Factor),
        Triple("Derivative", "x^2 sin(x)", AlgebraMode.Derive),
        Triple("Antiderivative", "3x^2 + 2x + 1", AlgebraMode.Integrate),
    )
    Panel(title = "Try an example", footer = "Use ; to separate equations in a system. Multiplication can be implied: 2x, 3(x + 1), x(x − 2).") {
        val sorted = examples.sortedBy { if (it.third == mode) 0 else 1 }
        sorted.forEachIndexed { i, (label, ex, m) ->
            if (i > 0) PanelDivider()
            ListRow(label, subtitle = ex.replace("^2", "²").replace("^3", "³"), icon = when (m) {
                AlgebraMode.Solve -> WonderIcons.Functions; AlgebraMode.Simplify -> WonderIcons.Code; AlgebraMode.Factor -> WonderIcons.Grain
                AlgebraMode.Derive -> WonderIcons.ShowChart; AlgebraMode.Integrate -> WonderIcons.Insights
            }, tint = when (m) {
                AlgebraMode.Solve -> Color(0xFF1E7FE8); AlgebraMode.Simplify -> Color(0xFF8E44AD); AlgebraMode.Factor -> Color(0xFF16A085)
                AlgebraMode.Derive -> Color(0xFFE67E22); AlgebraMode.Integrate -> Color(0xFFC0392B)
            }, chevron = true, onClick = { pick(ex, m) })
        }
    }
}

/* ========================================== Graph =========================================== */

private val PLOT_COLORS = listOf(Color(0xFF1E7FE8), Color(0xFFE5483C), Color(0xFF2E9E44))

@Composable
private fun GraphTab(prefs: android.content.SharedPreferences, degrees: Boolean, seed: String?, consumeSeed: () -> Unit) {
    val p = wonderPalette()
    val fns = remember {
        mutableStateListOf<String>().apply {
            addAll(runCatching { val a = JSONArray(prefs.getString("graph", "[\"sin(x)\",\"x^2/4 - 1\"]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(listOf("sin(x)")))
            if (isEmpty()) add("")
        }
    }
    LaunchedEffect(seed) {
        if (seed != null) {
            if (fns.size >= 3) fns.removeAt(2)
            fns.add(0, seed); consumeSeed()
        }
    }
    LaunchedEffect(fns.toList()) { prefs.edit().putString("graph", JSONArray(fns.toList()).toString()).apply() }
    var cx by rememberSaveable { mutableFloatStateOf(0f) }
    var cy by rememberSaveable { mutableFloatStateOf(0f) }
    var scale by rememberSaveable { mutableFloatStateOf(40f) } // pixels per unit (in dp-ish units scaled below)
    var trace by remember { mutableStateOf<Offset?>(null) }
    val parsed = fns.map { s -> if (s.isBlank()) null else runCatching { MathParser.expression(s) }.getOrNull() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(p.panelRadius)
        Box(Modifier.fillMaxWidth().weight(1f).clip(shape).background(if (p.dark) Color(0xFF15181C) else Color.White).border(1.dp, p.panelBorder, shape)) {
            val gridColor = if (p.dark) Color(0xFF2A2F36) else Color(0xFFE9ECF0)
            val axisColor = if (p.dark) Color(0xFF8A939E) else Color(0xFF59616B)
            val labelColor = (if (p.dark) Color(0xFFAAB2BC) else Color(0xFF6B7078)).let { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        val newScale = (scale * zoom).coerceIn(2f, 4000f)
                        // keep the point under the fingers fixed while zooming
                        val wx = cx + (centroid.x - size.width / 2) / oldScale
                        val wy = cy - (centroid.y - size.height / 2) / oldScale
                        scale = newScale
                        cx = wx - (centroid.x - size.width / 2) / newScale - pan.x / newScale
                        cy = wy + (centroid.y - size.height / 2) / newScale + pan.y / newScale
                        trace = null
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { trace = if (trace == null) it else null }, onDoubleTap = { scale = (scale * 2).coerceAtMost(4000f) }) }
            ) {
                val w = size.width; val h = size.height
                val cx = cx.toDouble(); val cy = cy.toDouble(); val scale = scale.toDouble()
                fun sx(x: Double) = ((x - cx) * scale + w / 2).toFloat()
                fun sy(y: Double) = (h / 2 - (y - cy) * scale).toFloat()
                // grid step: 1, 2, 5 × 10^k so lines are ~ 60–150 px apart
                val rawStep = 90.0 / scale
                val mag = 10.0.pow(floor(log10(rawStep)))
                val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= rawStep }
                val left = cx - w / 2 / scale; val right = cx + w / 2 / scale
                val bottom = cy - h / 2 / scale; val top = cy + h / 2 / scale
                val paint = android.graphics.Paint().apply { color = labelColor; textSize = 28f; isAntiAlias = true }
                var gx = ceil(left / step) * step
                while (gx <= right) {
                    drawLine(gridColor, Offset(sx(gx), 0f), Offset(sx(gx), h), 1.5f)
                    if (abs(gx) > step / 2) drawContext.canvas.nativeCanvas.drawText(fmt(gx), sx(gx) + 4, (sy(0.0) + 30).coerceIn(30f, h - 6), paint)
                    gx += step
                }
                var gy = ceil(bottom / step) * step
                while (gy <= top) {
                    drawLine(gridColor, Offset(0f, sy(gy)), Offset(w, sy(gy)), 1.5f)
                    if (abs(gy) > step / 2) drawContext.canvas.nativeCanvas.drawText(fmt(gy), (sx(0.0) + 6).coerceIn(4f, w - 80), sy(gy) - 6, paint)
                    gy += step
                }
                drawLine(axisColor, Offset(sx(0.0), 0f), Offset(sx(0.0), h), 2.5f)
                drawLine(axisColor, Offset(0f, sy(0.0)), Offset(w, sy(0.0)), 2.5f)
                parsed.forEachIndexed { i, node ->
                    if (node == null) return@forEachIndexed
                    val vars = variables(node)
                    val v = vars.firstOrNull() ?: "x"
                    if (vars.size > 1) return@forEachIndexed
                    val path = Path()
                    var started = false
                    var lastY = 0.0
                    val n = (w / 2).toInt()
                    for (k in 0..n) {
                        val x = left + (right - left) * k / n
                        val y = runCatching { Evaluator(degrees, mapOf(v to x)).eval(node) }.getOrDefault(Double.NaN)
                        if (!y.isFinite() || abs(y - cy) > (top - bottom) * 50) { started = false; continue }
                        val py = sy(y)
                        if (started && abs(y - lastY) * scale > h * 2) started = false // asymptote
                        if (!started) { path.moveTo(sx(x), py); started = true } else path.lineTo(sx(x), py)
                        lastY = y
                    }
                    drawPath(path, PLOT_COLORS[i], style = Stroke(5f))
                }
                trace?.let { t ->
                    val node = parsed.firstOrNull { it != null } ?: return@let
                    val v = variables(node).firstOrNull() ?: "x"
                    val x = cx + (t.x - w / 2) / scale
                    val y = runCatching { Evaluator(degrees, mapOf(v to x)).eval(node) }.getOrDefault(Double.NaN)
                    if (y.isFinite()) {
                        drawLine(axisColor.copy(alpha = .5f), Offset(sx(x), 0f), Offset(sx(x), h), 2f)
                        drawCircle(PLOT_COLORS[parsed.indexOf(node)], 12f, Offset(sx(x), sy(y)))
                        drawCircle(Color.White, 5f, Offset(sx(x), sy(y)))
                        val label = "(${fmt((x * 1000).roundToLong() / 1000.0)}, ${fmt((y * 1000).roundToLong() / 1000.0)})"
                        val tp = android.graphics.Paint(paint).apply { textSize = 34f; color = android.graphics.Color.BLACK }
                        val bg = android.graphics.Paint().apply { color = android.graphics.Color.argb(230, 255, 255, 255) }
                        val tx = (sx(x) + 16).coerceAtMost(w - tp.measureText(label) - 16)
                        val ty = (sy(y) - 20).coerceIn(50f, h - 10)
                        drawContext.canvas.nativeCanvas.drawRoundRect(tx - 10, ty - 38, tx + tp.measureText(label) + 10, ty + 12, 12f, 12f, bg)
                        drawContext.canvas.nativeCanvas.drawText(label, tx, ty, tp)
                    }
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                SmallRound(WonderIcons.ZoomIn, "Zoom in") { scale = (scale * 1.5f).coerceAtMost(4000f) }
                SmallRound(WonderIcons.ZoomOut, "Zoom out") { scale = (scale / 1.5f).coerceAtLeast(2f) }
                SmallRound(WonderIcons.Home, "Reset view") { cx = 0f; cy = 0f; scale = 40f; trace = null }
            }
            Text("Drag to move · pinch to zoom · tap to trace", Modifier.align(Alignment.BottomStart).padding(8.dp), style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
        }
        Panel {
            fns.forEachIndexed { i, f ->
                if (i > 0) PanelDivider(0.dp)
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(PLOT_COLORS[i]))
                    Text("  y =", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.text.BasicTextField(f, { fns[i] = it }, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 14.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = if (f.isNotBlank() && parsed[i] == null) p.danger else p.text),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    if (fns.size > 1) IconButton(onClick = { fns.removeAt(i) }) { Icon(WonderIcons.Close, "Remove", tint = p.secondaryText) }
                }
            }
            if (fns.size < 3) {
                PanelDivider(0.dp)
                ListRow("Add function", leading = { Icon(WonderIcons.AddCircle, null, tint = p.accent) }, onClick = { fns.add("") })
            }
        }
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val p = wonderPalette()
    Box(Modifier.padding(3.dp).size(40.dp).clip(CircleShape).background(p.panel.copy(alpha = .92f)).border(1.dp, p.panelBorder, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = p.text, modifier = Modifier.size(22.dp))
    }
}

/* ========================================= Convert ========================================== */

@Composable
private fun ConverterTab(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val p = wonderPalette()
    var catIndex by rememberSaveable { mutableIntStateOf(prefs.getInt("conv_cat", 0).coerceIn(0, Units.categories.lastIndex)) }
    val cat = Units.categories[catIndex]
    var fromIndex by rememberSaveable(catIndex) { mutableIntStateOf(prefs.getInt("conv_from_$catIndex", 0).coerceIn(0, cat.units.lastIndex)) }
    var toIndex by rememberSaveable(catIndex) { mutableIntStateOf(prefs.getInt("conv_to_$catIndex", 1.coerceAtMost(cat.units.lastIndex)).coerceIn(0, cat.units.lastIndex)) }
    var value by rememberSaveable { mutableStateOf("1") }
    LaunchedEffect(catIndex, fromIndex, toIndex) { prefs.edit().putInt("conv_cat", catIndex).putInt("conv_from_$catIndex", fromIndex).putInt("conv_to_$catIndex", toIndex).apply() }
    val amount = remember(value) { runCatching { Evaluator().eval(MathParser.expression(value)) }.getOrNull() }
    val result = remember(amount, catIndex, fromIndex, toIndex) { amount?.let { runCatching { Units.convert(it, cat, cat.units[fromIndex], cat.units[toIndex]) } } }
    val icons = mapOf("Length" to WonderIcons.Straighten, "Area" to WonderIcons.GridView, "Volume" to WonderIcons.Science, "Mass" to WonderIcons.Scale,
        "Temperature" to WonderIcons.Thermostat, "Speed" to WonderIcons.Speed, "Time" to WonderIcons.Schedule, "Data" to WonderIcons.Storage,
        "Energy" to WonderIcons.Bolt, "Pressure" to WonderIcons.Tune, "Angle" to WonderIcons.RotateRight, "Fuel" to WonderIcons.Thermostat)
    Page {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Units.categories.forEachIndexed { i, c -> Chip(c.name, i == catIndex, { catIndex = i; value = if (c.name == "Temperature") "20" else "1" }, icons[c.name]) }
        }
        Panel {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(value, { value = it.take(40) }, "Amount", Modifier.weight(1f), keyboard = KeyboardType.Decimal, isError = amount == null && value.isNotBlank())
                    Spacer(Modifier.width(10.dp))
                    UnitPicker(cat, fromIndex, Modifier.weight(1f)) { fromIndex = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(p.accent).clickable { val t = fromIndex; fromIndex = toIndex; toIndex = t
                        result?.getOrNull()?.let { value = unitFmt(it) } }, contentAlignment = Alignment.Center) {
                        Icon(WonderIcons.SwapVert, "Swap units", tint = p.onAccent)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = result?.isSuccess == true) {
                        val r = unitFmt(result!!.getOrThrow()); context.copyText("Conversion", r); toast("Copied $r")
                    }) {
                        Text("Result", style = MaterialTheme.typography.labelMedium, color = p.secondaryText)
                        Text(
                            when { result == null -> "—"; result.isFailure -> result.exceptionOrNull()?.message ?: "—"; else -> unitFmt(result.getOrThrow()) },
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (result?.isFailure == true) p.danger else p.text
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    UnitPicker(cat, toIndex, Modifier.weight(1f)) { toIndex = it }
                }
            }
        }
        if (amount != null) Panel(title = "${fmt(amount)} ${cat.units[fromIndex].symbol} is") {
            cat.units.forEachIndexed { i, u ->
                if (i > 0) PanelDivider(16.dp)
                val v = runCatching { Units.convert(amount, cat, cat.units[fromIndex], u) }.getOrNull()
                Row(Modifier.fillMaxWidth().clickable { toIndex = i }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(u.name, Modifier.weight(1f), color = if (i == toIndex) p.accent else p.text)
                    Text((v?.let { unitFmt(it) } ?: "—") + " " + u.symbol, color = p.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun UnitPicker(cat: UnitCategory, index: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val p = wonderPalette()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(p.window).border(1.dp, p.fieldBorder, RoundedCornerShape(10.dp))
            .clickable { open = true }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cat.units[index].symbol, style = MaterialTheme.typography.titleMedium)
                Text(cat.units[index].name, style = MaterialTheme.typography.labelSmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(WonderIcons.ExpandMore, null, tint = p.secondaryText)
        }
        DropdownMenu(open, { open = false }, Modifier.background(p.panel)) {
            cat.units.forEachIndexed { i, u ->
                DropdownMenuItem(text = { Text("${u.name} (${u.symbol})", color = if (i == index) p.accent else p.text) }, onClick = { onPick(i); open = false })
            }
        }
    }
}
