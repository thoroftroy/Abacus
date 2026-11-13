package dev.wonderdesk.abacus

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Everyday calculators beyond the keypad: number bases, dates, tips and splits, discounts and loans. */
@Composable
fun ToolsTab() {
    var tool by rememberSaveable { mutableIntStateOf(0) }
    val names = listOf("Programmer", "Dates", "Tip & split", "Discount", "Loan")
    val scroll = rememberScrollState()
    ScrollToTopOnReselect(scroll)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { names.forEachIndexed { i, n -> Chip(n, tool == i, { tool = i }) } }
        when (tool) { 0 -> Programmer(); 1 -> Dates(); 2 -> TipSplit(); 3 -> Discount(); else -> Loan() }
    }
}

@Composable
private fun Result(label: String, value: String, big: Boolean = false) {
    val context = LocalContext.current
    val p = wonderPalette()
    ListRow(label, subtitle = null, trailing = {
        Text(value, style = if (big) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.titleMedium,
            color = if (big) p.accent else p.text, fontFamily = if (label.length <= 4) FontFamily.Monospace else null)
    }, onLongClick = { context.copyText(label, value) }, onClick = { context.copyText(label, value) })
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) =
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' }) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

private fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance().format(v)

/* ------------------------------------------------ programmer ------------------------------------------------ */

@Composable
private fun Programmer() {
    var text by rememberSaveable { mutableStateOf("255") }
    var base by rememberSaveable { mutableIntStateOf(10) }
    var other by rememberSaveable { mutableStateOf("") }
    val value = text.trim().removePrefix("0x").removePrefix("0b").toLongOrNull(base)
    Panel(title = "Number", footer = "Tap a result to copy it.") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(listOf("DEC", "HEX", "OCT", "BIN"), listOf(10, 16, 8, 2).indexOf(base)) { i ->
                val nb = listOf(10, 16, 8, 2)[i]; value?.let { text = it.toString(nb).uppercase() }; base = nb }
            OutlinedTextField(text, { text = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Value in base $base") }, isError = value == null && text.isNotBlank(), textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
        }
        if (value != null) {
            PanelDivider(0.dp); Result("DEC", value.toString())
            PanelDivider(0.dp); Result("HEX", "0x" + value.toString(16).uppercase())
            PanelDivider(0.dp); Result("OCT", value.toString(8))
            PanelDivider(0.dp); Result("BIN", value.toString(2).reversed().chunked(4).joinToString(" ").reversed())
            PanelDivider(0.dp); Result("Bits", "${64 - java.lang.Long.numberOfLeadingZeros(value).coerceAtMost(63)} used · ${java.lang.Long.bitCount(value)} set")
        }
    }
    if (value != null) Panel(title = "Bitwise with another number (decimal)") {
        Column(Modifier.padding(12.dp)) { NumField(other, { other = it }, "Second number", Modifier.fillMaxWidth()) }
        other.toLongOrNull()?.let { b ->
            listOf("AND" to (value and b), "OR" to (value or b), "XOR" to (value xor b), "A << B" to (value shl b.toInt().coerceIn(0, 63)), "A >> B" to (value shr b.toInt().coerceIn(0, 63)))
                .forEach { (n, r) -> PanelDivider(0.dp); Result(n, "$r  ·  0x${r.toString(16).uppercase()}") }
        }
    }
}

/* -------------------------------------------------- dates -------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dates() {
    var from by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var to by rememberSaveable { mutableLongStateOf(LocalDate.now().plusMonths(1).toEpochDay()) }
    var add by rememberSaveable { mutableStateOf("30") }
    var picking by remember { mutableIntStateOf(0) }
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    val a = LocalDate.ofEpochDay(from); val b = LocalDate.ofEpochDay(to)
    Panel(title = "Between two dates") {
        ListRow("From", subtitle = a.format(fmt), icon = WonderIcons.Event, chevron = true, onClick = { picking = 1 })
        PanelDivider()
        ListRow("To", subtitle = b.format(fmt), icon = WonderIcons.Event, chevron = true, onClick = { picking = 2 })
        val days = ChronoUnit.DAYS.between(a, b)
        val period = java.time.Period.between(minOf(a, b), maxOf(a, b))
        val weekdays = (0 until kotlin.math.abs(days)).count { minOf(a, b).plusDays(it).dayOfWeek.value <= 5 }
        PanelDivider(0.dp); Result("Days", "$days", big = true)
        PanelDivider(0.dp); Result("Length", listOfNotNull(period.years.takeIf { it > 0 }?.let { "$it y" }, period.months.takeIf { it > 0 }?.let { "$it mo" }, "${period.days} d").joinToString(" "))
        PanelDivider(0.dp); Result("Weeks", "%.1f".format(days / 7.0))
        PanelDivider(0.dp); Result("Weekdays", "$weekdays")
    }
    Panel(title = "Add or take away days") {
        Column(Modifier.padding(12.dp)) { NumField(add, { add = it }, "Days (negative to go back)", Modifier.fillMaxWidth()) }
        add.toLongOrNull()?.let { n -> PanelDivider(0.dp); Result("Result", a.plusDays(n).format(fmt), big = true) }
    }
    if (picking > 0) {
        val st = rememberDatePickerState(LocalDate.ofEpochDay(if (picking == 1) from else to).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picking = 0 }, confirmButton = {
            TextButton(onClick = { st.selectedDateMillis?.let { ms -> val d = ms / 86_400_000L; if (picking == 1) from = d else to = d }; picking = 0 }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { picking = 0 }) { Text("Cancel") } }) { DatePicker(st) }
    }
}

/* ----------------------------------------------- tip & split ----------------------------------------------- */

@Composable
private fun TipSplit() {
    var bill by rememberSaveable { mutableStateOf("") }
    var tip by rememberSaveable { mutableFloatStateOf(15f) }
    var people by rememberSaveable { mutableIntStateOf(2) }
    var roundUp by rememberSaveable { mutableStateOf(false) }
    val p = wonderPalette()
    Panel(title = "Bill") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumField(bill, { bill = it }, "Amount", Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tip ${tip.toInt()}%", Modifier.width(80.dp))
                Slider(tip, { tip = it.toInt().toFloat() }, Modifier.weight(1f), valueRange = 0f..30f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10, 15, 18, 20, 25).forEach { t -> Chip("$t%", tip.toInt() == t, { tip = t.toFloat() }) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Split between", Modifier.weight(1f))
                IconButton(onClick = { if (people > 1) people-- }) { Icon(WonderIcons.Remove, "Fewer") }
                Text("$people", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { if (people < 50) people++ }) { Icon(WonderIcons.Add, "More") }
            }
        }
        PanelDivider(0.dp)
        SwitchRow("Round each share up", roundUp, { roundUp = it })
    }
    bill.toDoubleOrNull()?.let { b ->
        val tipAmt = b * tip / 100; val total = b + tipAmt
        var each = total / people; if (roundUp) each = kotlin.math.ceil(each)
        Panel(title = "Result") {
            Result("Tip", money(tipAmt)); PanelDivider(0.dp)
            Result("Total", money(if (roundUp) each * people else total)); PanelDivider(0.dp)
            Result("Each pays", money(each), big = true)
        }
    }
}

/* ------------------------------------------------- discount ------------------------------------------------- */

@Composable
private fun Discount() {
    var price by rememberSaveable { mutableStateOf("") }
    var off by rememberSaveable { mutableStateOf("20") }
    var tax by rememberSaveable { mutableStateOf("0") }
    Panel(title = "Price") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(price, { price = it }, "Original price", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(off, { off = it }, "% off", Modifier.weight(1f)); NumField(tax, { tax = it }, "Sales tax %", Modifier.weight(1f))
            }
        }
    }
    val pr = price.toDoubleOrNull(); val o = off.toDoubleOrNull() ?: 0.0; val t = tax.toDoubleOrNull() ?: 0.0
    if (pr != null) Panel(title = "Result") {
        val sale = pr * (1 - o / 100); val withTax = sale * (1 + t / 100)
        Result("You save", money(pr - sale)); PanelDivider(0.dp)
        Result("Sale price", money(sale)); PanelDivider(0.dp)
        Result("With tax", money(withTax), big = true)
    }
}

/* --------------------------------------------------- loan --------------------------------------------------- */

@Composable
private fun Loan() {
    var amount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("5") }
    var years by rememberSaveable { mutableStateOf("5") }
    Panel(title = "Loan or mortgage") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(amount, { amount = it }, "Amount borrowed", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(rate, { rate = it }, "Interest % a year", Modifier.weight(1f)); NumField(years, { years = it }, "Years", Modifier.weight(1f)) }
        }
    }
    val a = amount.toDoubleOrNull(); val r = (rate.toDoubleOrNull() ?: 0.0) / 100 / 12; val n = ((years.toDoubleOrNull() ?: 0.0) * 12).toInt()
    if (a != null && n > 0) Panel(title = "Repayments") {
        val monthly = if (r == 0.0) a / n else a * r / (1 - Math.pow(1 + r, -n.toDouble()))
        Result("Each month", money(monthly), big = true); PanelDivider(0.dp)
        Result("Total paid", money(monthly * n)); PanelDivider(0.dp)
        Result("Interest", money(monthly * n - a))
    }
}
