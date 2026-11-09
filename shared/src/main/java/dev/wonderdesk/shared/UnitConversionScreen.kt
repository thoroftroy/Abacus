package dev.wonderdesk.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
private fun Choice(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AquaSecondaryButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
fun UnitConversionScreen() {
    val context = LocalContext.current
    var categoryName by rememberSaveable { mutableStateOf("Length") }
    val category = UnitConversion.categories.firstOrNull { it.name == categoryName } ?: UnitConversion.categories.first()
    var fromName by rememberSaveable { mutableStateOf(category.units.first().name) }
    var toName by rememberSaveable { mutableStateOf(category.units[1].name) }
    var value by rememberSaveable { mutableStateOf("") }
    val from = category.units.firstOrNull { it.name == fromName } ?: category.units.first()
    val to = category.units.firstOrNull { it.name == toName } ?: category.units[1]
    val converted = remember(value, category, from, to) {
        if (value.isBlank()) null else runCatching {
            val number = value.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Enter a number")
            UnitConversion.convert(number, category, from, to)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Unit converter", style = MaterialTheme.typography.titleMedium)
        Choice("Category", category.name, UnitConversion.categories.map { it.name }) { name ->
            categoryName = name
            val units = UnitConversion.categories.first { it.name == name }.units
            fromName = units[0].name; toName = units[1].name
        }
        OutlinedTextField(value, { value = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("Value") }, singleLine = true,
            supportingText = { Text("Negative and scientific notation are supported.") })
        Choice("From", from.name, category.units.map { it.name }) { fromName = it }
        Choice("To", to.name, category.units.map { it.name }) { toName = it }
        AquaSecondaryButton(onClick = { val old = fromName; fromName = toName; toName = old }) { Text("Swap units") }
        converted?.fold(onSuccess = { result ->
            val formatted = UnitConversion.format(result)
            Text("$formatted ${to.name}", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { context.copyText("Conversion result", formatted) }) { Text("Copy result") }
        }, onFailure = { Text(it.message ?: "Invalid value", color = MaterialTheme.colorScheme.error) })
        Text("Conversions are calculated locally using fixed standard factors. Floating-point results are approximate.", style = MaterialTheme.typography.bodySmall)
    }
}
