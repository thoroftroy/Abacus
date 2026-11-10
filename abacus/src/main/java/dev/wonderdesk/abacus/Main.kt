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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var graphSeed by remember { mutableStateOf<List<String>?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val setTab: (String) -> Unit = { tab = it; prefs.edit().putString("tab", it).apply() }
    val addHistory: (String, String) -> Unit = { expr, res ->
        history = (listOf("$expr = $res") + history.filter { it != "$expr = $res" }).take(100)
        prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    WonderApp(
        appName = "Abacus", about = ABOUT,
        tabs = listOf(
            WonderTab("calc", "Calculate", WonderIcons.Calculate),
            WonderTab("algebra", "Solve", WonderIcons.Functions),
            WonderTab("graph", "Graph", WonderIcons.ShowChart),
            WonderTab("convert", "Convert", WonderIcons.SwapHoriz),
        ),
        selectedTab = tab, onTab = setTab,
        title = when (tab) { "algebra" -> "Solve"; "graph" -> "Graph"; "convert" -> "Convert"; else -> "Abacus" },
        actions = {
            if (tab == "calc" || tab == "algebra") ToolbarButton(WonderIcons.ContentPaste, "Paste", {
                val text = context.pasteText()
                if (text.isNullOrBlank()) toastRef.value("Nothing to paste")
                else {
                    val clean = Smart.normalize(text)
                    // Plain arithmetic stays on the keypad; equations, functions and words go to Solve.
                    val arithmetic = tab == "calc" && runCatching { Evaluator(degrees).eval(MathParser.expression(clean)) }.isSuccess
                    if (!arithmetic) { algebraInput = text.trim(); setTab("algebra") }
                    else calcInput = clean
                }
            })
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
        toastRef.value = LocalToast.current
        when (tab) {
            "calc" -> CalculatorTab(calcInput, { calcInput = it }, degrees, { degrees = it; prefs.edit().putBoolean("degrees", it).apply() }, history.firstOrNull()?.substringAfterLast(" = "), addHistory,
                toSolve = { algebraInput = it; setTab("algebra") })
            "algebra" -> SmartTab(algebraInput, { algebraInput = it }, degrees) { fns -> graphSeed = fns; setTab("graph") }
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
private fun CalculatorTab(input: String, setInput: (String) -> Unit, degrees: Boolean, setDegrees: (Boolean) -> Unit, lastAnswer: String?, addHistory: (String, String) -> Unit, toSolve: (String) -> Unit) {
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
    // A hardware or Bluetooth keyboard types straight into the calculator.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun typed(ch: Char): Boolean {
        val map = mapOf('*' to "*", 'x' to "*", 'X' to "*", '/' to "/", '+' to "+", '-' to "-", '^' to "^", '(' to "(", ')' to ")", '.' to ".", ',' to ".", '%' to "%", '!' to "!")
        val ins = when { ch.isDigit() -> ch.toString(); else -> map[ch] } ?: return false
        press(Key(ins, ins, if (ch.isDigit() || ch == '.' || ch == ',') KeyKind.Digit else KeyKind.Op))
        return true
    }
    Column(Modifier.fillMaxSize().padding(12.dp).focusRequester(focus).focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter, androidx.compose.ui.input.key.Key.Equals -> if (e.isShiftPressed && e.key == androidx.compose.ui.input.key.Key.Equals) typed('+') else { evaluate(); true }
                androidx.compose.ui.input.key.Key.Backspace -> { press(Key("⌫", "", KeyKind.Clear)); true }
                androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.Key.Delete -> { press(Key("AC", "", KeyKind.Clear)); true }
                else -> {
                    val c = e.utf16CodePoint.toChar()
                    if (c.isLetter() && c !in "xX") { toSolve(input + c); true } else typed(c)
                }
            }
        }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/* ========================================== Solve =========================================== */

fun Context.pasteText(): String? = runCatching {
    getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
}.getOrNull()

@Composable
private fun SmartTab(input: String, setInput: (String) -> Unit, degrees: Boolean, onGraph: (List<String>) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val p = wonderPalette()
    val prefs = remember { context.prefs("abacus") }
    var task by rememberSaveable { mutableStateOf(Task.Auto) }
    var showWork by rememberSaveable { mutableStateOf(prefs.getBoolean("showWork", false)) }
    var result by remember { mutableStateOf<Result<SmartResult>?>(null) }
    var working by remember { mutableStateOf(false) }
    // Solving runs off the main thread after a short pause in typing, so the keyboard never lags.
    LaunchedEffect(input, task, degrees) {
        if (input.isBlank()) { result = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(220)
        working = true
        result = withContext(Dispatchers.Default) { runCatching { Smart.run(input, task, degrees) } }
        working = false
    }
    Page {
        Panel {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(input, setInput, "Type or paste anything", singleLine = false, keyboard = KeyboardType.Ascii, textStyle = MaterialTheme.typography.titleMedium,
                    supporting = "e.g. 2x + 3 = 11 · y = x² − 4 · derivative of x² sin x · 15% of 80",
                    trailing = if (input.isNotEmpty()) ({ IconButton(onClick = { setInput("") }) { Icon(WonderIcons.Close, "Clear") } }) else null)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("x", "y", "=", "^", "(", ")", "√", "π", "²", ";").forEach { sym ->
                        Box(Modifier.size(width = 40.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(8.dp))
                            .clickable { setInput(input + if (sym == ";") "; " else sym) }, contentAlignment = Alignment.Center) { Text(sym, style = MaterialTheme.typography.titleMedium) }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Task.entries.forEach { t -> Chip(t.label, task == t, { task = t }) }
                }
            }
        }
        val r = result
        when {
            input.isBlank() -> SmartExamples { ex -> setInput(ex); task = Task.Auto }
            r == null || (working && r.isFailure) -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            r.isFailure -> Panel {
                ListRow((r.exceptionOrNull() as? MathError)?.message ?: "Abacus couldn't read that. Check the brackets and operators, or pick what to do above.", icon = WonderIcons.Info, tint = Color(0xFFE39A12))
            }
            else -> {
                val res = r.getOrThrow()
                Panel(title = res.task.label.let { if (it == "Auto") "Result" else it }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(res.understood, style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                        SelectionContainerCompat { Text(res.answer, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)) }
                        res.details.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = p.secondaryText) }
                    }
                    PanelDivider(0.dp)
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        WButton("Copy", { context.copyText("Abacus result", res.answer); toast("Copied") }, icon = WonderIcons.ContentCopy, kind = ButtonKind.Plain)
                        if (res.graphs.isNotEmpty()) WButton("Graph", { onGraph(res.graphs) }, icon = WonderIcons.ShowChart, kind = ButtonKind.Plain)
                        Spacer(Modifier.weight(1f))
                        Text("Show work", style = MaterialTheme.typography.labelLarge, color = p.secondaryText)
                        Switch(showWork, { showWork = it; prefs.edit().putBoolean("showWork", it).apply() })
                    }
                }
                if (res.alternatives.isNotEmpty() && task == Task.Auto) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also try", style = MaterialTheme.typography.labelLarge, color = p.secondaryText)
                    res.alternatives.forEach { t -> Chip(t.label, false, { if (t == Task.Graph && res.graphs.isNotEmpty()) onGraph(res.graphs) else task = t }) }
                }
                if (showWork) {
                    if (res.steps.isNotEmpty()) Panel(title = "Work") {
                        res.steps.forEachIndexed { i, step ->
                            if (i > 0) PanelDivider(48.dp)
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) { Text("${i + 1}", color = p.onAccent, style = MaterialTheme.typography.labelMedium) }
                                Spacer(Modifier.width(10.dp))
                                Text(step, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Panel(title = "How it works") {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            IconTile(WonderIcons.Lightbulb, Color(0xFFF2A516))
                            Spacer(Modifier.width(12.dp))
                            Text(res.explain, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) = androidx.compose.foundation.text.selection.SelectionContainer { content() }

@Composable
private fun SmartExamples(pick: (String) -> Unit) {
    val examples = listOf(
        Triple("Solve an equation", "2x + 3 = 11", Task.Solve), Triple("Quadratic", "x^2 - 5x + 6 = 0", Task.Solve), Triple("System", "2x + y = 5; x - y = 1", Task.Solve),
        Triple("Graph a function", "y = x^2 - 4", Task.Graph), Triple("Solve for a letter", "solve for x: y = mx + b", Task.Solve), Triple("Exponential", "2^x = 50", Task.Solve),
        Triple("Show arithmetic work", "2 + 3 × (4 − 1)²", Task.Calculate), Triple("Percent", "15% of 80", Task.Calculate),
        Triple("Expand", "(x + 2)^3", Task.Expand), Triple("Factor", "factor x^3 - 4x", Task.Factor),
        Triple("Derivative", "derivative of x^2 sin(x)", Task.Derive), Triple("Area under a curve", "integrate x^2 from 0 to 3", Task.Integrate),
        Triple("Pasted from the web", "\\frac{x}{2} + 1 = 4", Task.Solve),
    )
    Panel(title = "Try one", footer = "Type, paste, or use a keyboard. Separate equations with ; or new lines. Multiplication can be implied: 2x, 3(x + 1). Words work too: “square root of 2”, “5 squared”.") {
        examples.forEachIndexed { i, (label, ex, t) ->
            if (i > 0) PanelDivider()
            ListRow(label, subtitle = ex, icon = when (t) {
                Task.Solve -> WonderIcons.Functions; Task.Graph -> WonderIcons.ShowChart; Task.Calculate -> WonderIcons.Calculate; Task.Expand -> WonderIcons.Code
                Task.Factor -> WonderIcons.Grain; Task.Derive -> WonderIcons.Insights; else -> WonderIcons.Timeline
            }, tint = when (t) {
                Task.Solve -> Color(0xFF1E7FE8); Task.Graph -> Color(0xFF2E9E44); Task.Calculate -> Color(0xFF7A7F87); Task.Expand -> Color(0xFF8E44AD)
                Task.Factor -> Color(0xFF16A085); Task.Derive -> Color(0xFFE67E22); else -> Color(0xFFC0392B)
            }, chevron = true, onClick = { pick(ex) })
        }
    }
}

/* ========================================== Graph =========================================== */

private val PLOT_COLORS = listOf(Color(0xFF1E7FE8), Color(0xFFE5483C), Color(0xFF2E9E44))

@Composable
private fun GraphTab(prefs: android.content.SharedPreferences, degrees: Boolean, seed: List<String>?, consumeSeed: () -> Unit) {
    val p = wonderPalette()
    val fns = remember {
        mutableStateListOf<String>().apply {
            addAll(runCatching { val a = JSONArray(prefs.getString("graph", "[\"sin(x)\",\"x^2/4 - 1\"]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(listOf("sin(x)")))
            if (isEmpty()) add("")
        }
    }
    LaunchedEffect(seed) {
        if (seed != null) {
            fns.clear(); fns.addAll(seed.take(3)); consumeSeed()
        }
    }
    LaunchedEffect(fns.toList()) { prefs.edit().putString("graph", JSONArray(fns.toList()).toString()).apply() }
    var cx by rememberSaveable { mutableFloatStateOf(0f) }
    var cy by rememberSaveable { mutableFloatStateOf(0f) }
    var scale by rememberSaveable { mutableFloatStateOf(40f) } // pixels per unit (in dp-ish units scaled below)
    var trace by remember { mutableStateOf<Offset?>(null) }
    val fnList = fns.toList()
    val parsed = remember(fnList) { fnList.map { s -> if (s.isBlank()) null else runCatching { MathParser.expression(Smart.normalize(s).removePrefix("y =").removePrefix("y=").trim()) }.getOrNull() } }
    // Each function is compiled once into a closure; drawing a frame is then just arithmetic.
    val compiled = remember(parsed, degrees) { parsed.map { n -> n?.let { node -> val vars = variables(node); if (vars.size > 1) null else compileFn(node, vars.firstOrNull() ?: "x", degrees) } } }
    val labelPaint = remember { android.graphics.Paint().apply { textSize = 28f; isAntiAlias = true } }
    val tracePaint = remember { android.graphics.Paint().apply { textSize = 34f; isAntiAlias = true; color = android.graphics.Color.BLACK } }
    val traceBg = remember { android.graphics.Paint().apply { color = android.graphics.Color.argb(230, 255, 255, 255); isAntiAlias = true } }
    val paths = remember { List(3) { Path() } }
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
                val paint = labelPaint.apply { color = labelColor }
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
                compiled.forEachIndexed { i, fn ->
                    if (fn == null || i > 2) return@forEachIndexed
                    val path = paths[i].apply { reset() }
                    var started = false
                    var lastY = 0.0
                    val n = (w / 2).toInt()
                    for (k in 0..n) {
                        val x = left + (right - left) * k / n
                        val y = fn(x)
                        if (!y.isFinite() || abs(y - cy) > (top - bottom) * 50) { started = false; continue }
                        val py = sy(y)
                        if (started && abs(y - lastY) * scale > h * 2) started = false // asymptote
                        if (!started) { path.moveTo(sx(x), py); started = true } else path.lineTo(sx(x), py)
                        lastY = y
                    }
                    drawPath(path, PLOT_COLORS[i], style = Stroke(5f))
                }
                trace?.let { t ->
                    val idx = compiled.indexOfFirst { it != null }.takeIf { it >= 0 } ?: return@let
                    val fn = compiled[idx]!!
                    val x = cx + (t.x - w / 2) / scale
                    val y = fn(x)
                    if (y.isFinite()) {
                        drawLine(axisColor.copy(alpha = .5f), Offset(sx(x), 0f), Offset(sx(x), h), 2f)
                        drawCircle(PLOT_COLORS[idx], 12f, Offset(sx(x), sy(y)))
                        drawCircle(Color.White, 5f, Offset(sx(x), sy(y)))
                        val label = "(${fmt((x * 1000).roundToLong() / 1000.0)}, ${fmt((y * 1000).roundToLong() / 1000.0)})"
                        val tp = tracePaint
                        val bg = traceBg
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
