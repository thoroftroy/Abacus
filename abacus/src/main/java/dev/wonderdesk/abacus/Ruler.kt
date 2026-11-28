package dev.wonderdesk.abacus

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.wonderdesk.shared.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the ruler remembers: its scale correction (checked with a card) and the units shown first. */
object RulerPrefs {
    private fun p(c: Context) = c.getSharedPreferences("measure", Context.MODE_PRIVATE)
    fun scale(c: Context) = p(c).getFloat("rulerScale", 1f)
    fun setScale(c: Context, v: Float) = p(c).edit().putFloat("rulerScale", v).apply()
    fun inches(c: Context) = p(c).getBoolean("inches", java.util.Locale.getDefault().country in setOf("US", "LR", "MM"))
    fun setInches(c: Context, v: Boolean) = p(c).edit().putBoolean("inches", v).apply()
    fun last(c: Context) = p(c).getFloat("rulerLast", -1f)
    fun setLast(c: Context, mm: Float) = p(c).edit().putFloat("rulerLast", mm).apply()
    /** Screen pixels per real millimetre, from the screen's own size times the card correction. */
    fun pxPerMm(c: Context) = c.resources.displayMetrics.ydpi / 25.4f * scale(c)
}

fun lengthText(mm: Float, inches: Boolean): String =
    if (inches) "%.2f in".format(mm / 25.4f).let { s -> if (mm >= 304.8f) "$s  (%d ft %.1f in)".format((mm / 304.8f).toInt(), (mm % 304.8f) / 25.4f) else s }
    else if (mm >= 1000f) "%.3f m".format(mm / 1000f) else "%.1f cm".format(mm / 10f)

/**
 * The ruler, the whole height of the screen. Zero is the very top edge of the display (it draws under the status bar and the
 * camera hole), so an object can be butted against the phone's top. Millimetres down the left side, sixteenths of an inch down
 * the right. Two handles mark the ends; "Add and move on" walks the phone along something longer than the screen.
 */
class RulerActivity : WonderActivity() {
    @Composable override fun Content() {
        WonderTheme { RulerScreen(onClose = { finish() }) }
    }

    override fun onResume() {
        super.onResume()
        // Edge to edge, bars hidden, drawn into the camera cutout too, and the screen kept on while measuring.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun RulerScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    val ink = Color(0xFF3A2A10)
    val red = Color(0xFFE5483C)
    val blue = Color(0xFF2E7FE8)
    val baseDensity = context.resources.displayMetrics.ydpi / 25.4f
    var scale by remember { mutableFloatStateOf(RulerPrefs.scale(context)) }
    val pxPerMm = baseDensity * scale
    var inches by remember { mutableStateOf(RulerPrefs.inches(context)) }
    // The two ends, in pixels from the top of the screen.
    var a by remember { mutableFloatStateOf(0f) }
    var b by remember { mutableFloatStateOf(pxPerMm * 50f) }
    var dragging by remember { mutableIntStateOf(0) }   // 1 = start handle, 2 = end handle
    var walked by remember { mutableFloatStateOf(0f) }  // millimetres added up from earlier stretches
    var checking by remember { mutableStateOf(false) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val mm = abs(b - a) / pxPerMm
    val total = walked + mm
    // A light tick each whole centimetre (or half inch) while a handle moves, like the clicks of a tape measure.
    val step = if (inches) 12.7f else 10f
    var lastStep by remember { mutableIntStateOf(-1) }
    LaunchedEffect((mm / step).toInt()) { val s = (mm / step).toInt(); if (dragging != 0 && s != lastStep) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastStep = s }
    // Saved as it changes, so the Measure page shows it the moment you come back.
    LaunchedEffect(total) { RulerPrefs.setLast(context, total) }

    Box(Modifier.fillMaxSize().background(Color(0xFFFFF3C4))) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(pxPerMm, checking) {
                detectTapGestures { pos ->
                    if (checking) { scale = (pos.y / (85.6f * baseDensity)).coerceIn(.6f, 1.4f); return@detectTapGestures }
                    // A tap moves whichever end is nearer.
                    if (abs(pos.y - a) < abs(pos.y - b)) a = pos.y.coerceAtLeast(0f) else b = pos.y.coerceAtLeast(0f)
                }
            }
            .pointerInput(pxPerMm, checking) {
                detectDragGestures(onDragStart = { pos ->
                    dragging = if (checking) 3 else if (abs(pos.y - a) < abs(pos.y - b)) 1 else 2
                }, onDragEnd = { dragging = 0 }, onDragCancel = { dragging = 0 }) { ch, _ ->
                    val y = ch.position.y.coerceIn(0f, heightPx)
                    when (dragging) { 1 -> a = y; 2 -> b = y; 3 -> scale = (y / (85.6f * baseDensity)).coerceIn(.6f, 1.4f) }
                    ch.consume()
                }
            }) {
            heightPx = size.height
            val w = size.width
            val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(58, 42, 16); textSize = 34f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            // Millimetres down the left edge.
            var i = 0
            while (i * pxPerMm <= size.height) {
                val y = i * pxPerMm; val len = when { i % 10 == 0 -> 78f; i % 5 == 0 -> 52f; else -> 30f }
                drawLine(ink, Offset(0f, y), Offset(len, y), if (i % 10 == 0) 3.2f else 1.6f)
                if (i % 10 == 0 && i > 0) drawContext.canvas.nativeCanvas.drawText("${i / 10}", 88f, y + 12f, text)
                i++
            }
            // Sixteenths of an inch down the right edge.
            val px16 = pxPerMm * 25.4f / 16f
            text.textAlign = android.graphics.Paint.Align.RIGHT
            i = 0
            while (i * px16 <= size.height) {
                val y = i * px16; val len = when { i % 16 == 0 -> 78f; i % 8 == 0 -> 58f; i % 4 == 0 -> 42f; i % 2 == 0 -> 30f; else -> 20f }
                drawLine(ink, Offset(w - len, y), Offset(w, y), if (i % 16 == 0) 3.2f else 1.6f)
                if (i % 16 == 0 && i > 0) drawContext.canvas.nativeCanvas.drawText("${i / 16}", w - 88f, y + 12f, text)
                i++
            }
            text.textAlign = android.graphics.Paint.Align.LEFT; text.textSize = 26f
            drawContext.canvas.nativeCanvas.drawText("cm", 88f, 3.4f * pxPerMm + 12f, text)
            text.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("in", w - 88f, 3.4f * pxPerMm + 12f, text)
            if (checking) {
                // A bank or ID card is exactly 85.60 mm long: drag the blue box's bottom edge to the card's end.
                val cardPx = 85.6f * pxPerMm
                drawRoundRect(blue.copy(alpha = .16f), Offset(w * .25f, 0f), Size(w * .5f, cardPx), CornerRadius(26f))
                drawRoundRect(blue, Offset(w * .25f, 0f), Size(w * .5f, cardPx), CornerRadius(26f), style = Stroke(5f))
                drawLine(blue, Offset(0f, cardPx), Offset(w, cardPx), 4f)
            } else {
                // The measured stretch, shaded, with a handle at each end.
                drawRect(red.copy(alpha = .10f), Offset(0f, minOf(a, b)), Size(w, abs(b - a)))
                listOf(a, b).forEach { y ->
                    drawLine(red, Offset(0f, y), Offset(w, y), 5f)
                    drawCircle(red, 26f, Offset(w / 2, y)); drawCircle(Color.White, 10f, Offset(w / 2, y))
                }
            }
        }
        // Readout and controls in the middle, clear of both scales.
        Column(Modifier.align(Alignment.BottomCenter).padding(start = 60.dp, end = 60.dp, bottom = 28.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .92f)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checking) {
                Text("Check the scale", style = MaterialTheme.typography.titleMedium, color = ink, fontWeight = FontWeight.SemiBold)
                Text("Hold a bank or ID card against the screen, its short edge at the very top. Drag until the blue line meets the card's other end.",
                    style = MaterialTheme.typography.bodySmall, color = ink, textAlign = TextAlign.Center)
                Text("Correction %+.1f%%".format((scale - 1f) * 100f), style = MaterialTheme.typography.labelLarge, color = blue)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WButton("Done", { RulerPrefs.setScale(context, scale); checking = false }, kind = ButtonKind.Primary)
                    WButton("Reset", { scale = 1f }, kind = ButtonKind.Plain)
                }
            } else {
                Text(lengthText(total, inches), style = MaterialTheme.typography.headlineMedium, color = ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(lengthText(total, !inches), style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = .7f))
                if (walked > 0f) Text("This stretch ${lengthText(mm, inches)}", style = MaterialTheme.typography.labelMedium, color = red)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("cm", !inches, { inches = false; RulerPrefs.setInches(context, false) })
                    Chip("in", inches, { inches = true; RulerPrefs.setInches(context, true) })
                }
                WButton("Add, then move on", { walked += mm; a = 0f; b = pxPerMm * 20f }, Modifier.fillMaxWidth())
                Text("Longer than the screen? Mark the lower red line, slide the phone's top edge to the mark, tap Add.",
                    style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .7f), textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    androidx.compose.material3.TextButton(onClick = { walked = 0f; a = 0f; b = pxPerMm * 50f }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Reset", maxLines = 1) }
                    androidx.compose.material3.TextButton(onClick = { checking = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Check scale", maxLines = 1) }
                    androidx.compose.material3.TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Close", maxLines = 1) }
                }
            }
        }
    }
}
