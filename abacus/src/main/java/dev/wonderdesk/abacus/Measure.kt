package dev.wonderdesk.abacus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

/*
 * The measuring tools: the phone's sensors and camera turned into a level, a ruler, a camera ruler for distance and height, a compass,
 * a thermometer, a sound meter, a light meter, a motion meter, an altimeter and a magnet finder.
 */

val MEASURE_TOOLS = listOf("Level", "Ruler", "Camera ruler", "Compass", "Temperature", "Sound", "Light", "Motion", "Air pressure", "Magnet")

@Composable
fun MeasureTool(i: Int) {
    when (MEASURE_TOOLS.getOrNull(i)) {
        "Level" -> Level()
        "Ruler" -> Ruler()
        "Camera ruler" -> CameraRuler()
        "Compass" -> Compass()
        "Temperature" -> Temperature()
        "Sound" -> SoundMeter()
        "Light" -> LightMeter()
        "Motion" -> Motion()
        "Air pressure" -> AirPressure()
        else -> MagnetFinder()
    }
}

/* ------------------------------------------------ sensors ------------------------------------------------ */

private class Reading(val values: FloatArray, val accuracy: Int)

/** Live readings from one sensor while this screen is showing, or null when the phone does not have it. */
@Composable
private fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_GAME): State<Reading?> {
    val context = LocalContext.current
    val state = remember(type) { mutableStateOf<Reading?>(null) }
    DisposableEffect(type) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(type)
        var acc = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { state.value = Reading(e.values.copyOf(), acc) }
            override fun onAccuracyChanged(sensor: Sensor, a: Int) { acc = a; state.value?.let { state.value = Reading(it.values, a) } }
        }
        if (s != null) sm.registerListener(l, s, rate)
        onDispose { sm.unregisterListener(l) }
    }
    return state
}

@Composable
private fun hasSensor(type: Int): Boolean {
    val context = LocalContext.current
    return remember(type) { (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(type) != null }
}

/** Gravity in the phone's own axes, smoothed so the needles settle instead of shaking. */
@Composable
private fun rememberGravity(): FloatArray? {
    val type = if (hasSensor(Sensor.TYPE_GRAVITY)) Sensor.TYPE_GRAVITY else Sensor.TYPE_ACCELEROMETER
    val raw = rememberSensor(type)
    var smooth by remember { mutableStateOf<FloatArray?>(null) }
    // Eased on the screen's own clock, so it always settles on the latest reading even when the sensor goes quiet.
    LaunchedEffect(Unit) {
        while (isActive) withFrameMillis {
            val r = raw.value?.values ?: return@withFrameMillis
            val s = smooth
            smooth = if (s == null) r.copyOf(3) else FloatArray(3) { i -> s[i] + (r[i] - s[i]) * .2f }
        }
    }
    return smooth
}

/** A reading eased towards its latest value on the screen's clock (angles go the short way round when [angle] is set). */
@Composable
private fun rememberEased(target: Float?, rate: Float, angle: Boolean = false): Float? {
    val latest by rememberUpdatedState(target)
    var v by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) withFrameMillis {
            val t = latest ?: return@withFrameMillis
            val cur = v
            v = when {
                cur == null -> t
                angle -> (cur + ((((t - cur) + 540f) % 360f) - 180f) * rate + 360f) % 360f
                else -> cur + (t - cur) * rate
            }
        }
    }
    return v
}

@Composable
private fun Missing(what: String) {
    val p = wonderPalette()
    Panel { Text("This phone doesn't have $what.", Modifier.padding(16.dp), color = p.secondaryText) }
}

@Composable
private fun BigValue(value: String, label: String? = null, color: Color? = null) {
    val p = wonderPalette()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), color = color ?: p.text, textAlign = TextAlign.Center)
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, color = p.accent, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Hint(text: String) {
    val p = wonderPalette()
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText, textAlign = TextAlign.Center)
}

/** A rolling line of the last few seconds of readings. */
@Composable
private fun Trace(values: List<Float>, modifier: Modifier = Modifier, min: Float? = null, max: Float? = null) {
    val p = wonderPalette()
    Canvas(modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = .08f))) {
        if (values.size < 2) return@Canvas
        val lo = min ?: values.min(); val hi = (max ?: values.max()).let { if (it - lo < 1e-3f) lo + 1f else it }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1).toFloat(); val y = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f)) * .9f + size.height * .05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, p.accent, style = Stroke(4f, cap = StrokeCap.Round))
    }
}

/** The last [n] readings for a [Trace], sampled ten times a second so the line keeps moving even while the value holds still. */
@Composable
private fun rememberHistory(value: Float?, n: Int = 120): List<Float> {
    val latest by rememberUpdatedState(value)
    var list by remember { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            latest?.takeIf { it.isFinite() }?.let { v -> list = (list + v).takeLast(n) }
            delay(100)
        }
    }
    return list
}

private fun prefs(c: Context) = c.getSharedPreferences("measure", Context.MODE_PRIVATE)

/* ------------------------------------------------ level ------------------------------------------------ */

@Composable
private fun Level() {
    val p = wonderPalette()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val g = rememberGravity() ?: return Missing("a motion sensor")
    var zero by remember { mutableStateOf(prefs(context).let { floatArrayOf(it.getFloat("lx", 0f), it.getFloat("ly", 0f), it.getFloat("le", 0f)) }) }
    val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(.1f)
    // Surface: how far each way the phone tips from flat. Edge: the angle of the phone's side against the horizon. Slope: the steepness of what it lies on.
    val tipX = Math.toDegrees(atan2(g[0].toDouble(), g[2].toDouble())).toFloat() - zero[0]
    val tipY = Math.toDegrees(atan2(g[1].toDouble(), g[2].toDouble())).toFloat() - zero[1]
    val edgeRaw = Math.toDegrees(atan2(g[0].toDouble(), g[1].toDouble())).toFloat()
    val edge = (((edgeRaw - zero[2]) + 405f) % 90f) - 45f
    val slope = Math.toDegrees(acos((g[2] / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
    // The scene leans with the phone: beads roll to the low end and the bubbles run uphill, like the bubble in the vial.
    SideEffect { SceneFeed.tilting = true; SceneFeed.tiltX = (-g[0] / norm).coerceIn(-1f, 1f); SceneFeed.tiltY = (g[1] / norm).coerceIn(-1f, 1f) }
    DisposableEffect(Unit) { onDispose { SceneFeed.tilting = false } }
    val off = when (mode) { 0 -> hypot(tipX, tipY); 1 -> abs(edge); else -> slope }
    val level = mode != 2 && off < .3f
    LaunchedEffect(level) { if (level) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val good = Color(0xFF2EAE4E)
    Segmented(listOf("Surface", "Edge", "Slope"), mode, Modifier.fillMaxWidth()) { mode = it }
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                0 -> {
                    Canvas(Modifier.size(240.dp)) {
                        val c = center; val r = size.minDimension / 2
                        drawCircle(Brush.radialGradient(listOf(Color(0xFFE8FFD0), Color(0xFFB6E07A), Color(0xFF7FB03E)), c, r), r, c)
                        drawCircle(Color.Black.copy(.35f), r, c, style = Stroke(5f))
                        for (k in 1..3) drawCircle(Color.Black.copy(.18f), r * k / 4f, c, style = Stroke(2f))
                        drawLine(Color.Black.copy(.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f); drawLine(Color.Black.copy(.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2f)
                        val k = r * .8f / 15f
                        val bx = (tipX * k).coerceIn(-r * .8f, r * .8f); val by = (-tipY * k).coerceIn(-r * .8f, r * .8f)
                        val b = Offset(c.x + bx, c.y + by)
                        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFF4FFE8), Color(0x66FFFFFF)), Offset(b.x - r * .04f, b.y - r * .04f), r * .17f), r * .15f, b)
                        drawCircle(if (level) good else Color.Black.copy(.3f), r * .15f, b, style = Stroke(3f))
                    }
                    BigValue(if (level) "Level" else "%.1f°".format(off), "Side to side %.1f°  ·  End to end %.1f°".format(abs(tipX), abs(tipY)), if (level) good else null)
                    Hint("Lay the phone on the surface. The bubble floats to the high side.")
                }
                1 -> {
                    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                        val h = size.height; val w = size.width
                        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF0FFD8), Color(0xFFB6E07A), Color(0xFF8CBC4A))), Offset(0f, h * .2f), Size(w, h * .6f), CornerRadius(h * .3f))
                        drawRoundRect(Color.Black.copy(.35f), Offset(0f, h * .2f), Size(w, h * .6f), CornerRadius(h * .3f), style = Stroke(4f))
                        drawLine(Color.Black.copy(.4f), Offset(w / 2 - h * .45f, h * .2f), Offset(w / 2 - h * .45f, h * .8f), 3f); drawLine(Color.Black.copy(.4f), Offset(w / 2 + h * .45f, h * .2f), Offset(w / 2 + h * .45f, h * .8f), 3f)
                        val bx = w / 2 + (edge / 10f).coerceIn(-1f, 1f) * (w / 2 - h * .5f)
                        drawRoundRect(Brush.verticalGradient(listOf(Color.White, Color(0xCCFFFFFF))), Offset(bx - h * .4f, h * .28f), Size(h * .8f, h * .44f), CornerRadius(h * .22f))
                        drawRoundRect(if (level) good else Color.Black.copy(.3f), Offset(bx - h * .4f, h * .28f), Size(h * .8f, h * .44f), CornerRadius(h * .22f), style = Stroke(3f))
                    }
                    BigValue(if (level) "Level" else "%.1f°".format(abs(edge)), if (level) "Straight" else if (edge > 0) "Lower the right end" else "Lower the left end", if (level) good else null)
                    Hint("Stand the phone on its long or short edge against a shelf, picture frame or wall.")
                }
                else -> {
                    val grade = tan(Math.toRadians(slope.toDouble())).let { if (it.isFinite()) it * 100 else 0.0 }
                    val pitch = 12.0 * tan(Math.toRadians(slope.toDouble()))
                    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                        val w = size.width; val h = size.height; val base = Offset(w * .1f, h * .9f)
                        val len = w * .8f; val a = Math.toRadians(slope.coerceIn(0f, 80f).toDouble())
                        val end = Offset(base.x + (len * cos(a)).toFloat(), base.y - (len * sin(a)).toFloat().coerceAtMost(h * .85f))
                        drawLine(p.secondaryText.copy(.4f), base, Offset(base.x + len, base.y), 4f)
                        drawLine(p.accent, base, end, 10f, StrokeCap.Round)
                        drawArc(p.accent.copy(.5f), -slope.coerceIn(0f, 80f), slope.coerceIn(0f, 80f), true, Offset(base.x - 60f, base.y - 60f), Size(120f, 120f))
                    }
                    BigValue("%.1f°".format(slope), "Grade %.0f%%  ·  Roof pitch %.1f in 12".format(grade, pitch))
                    Hint("Lay the phone on a ramp, roof, path or table to see how steep it is.")
                }
            }
        }
    }
    if (mode != 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WButton("Set as zero", {
            zero = when (mode) {
                0 -> floatArrayOf(tipX + zero[0], tipY + zero[1], zero[2])
                1 -> floatArrayOf(zero[0], zero[1], zero[2] + edge)
                else -> zero
            }
            prefs(context).edit().putFloat("lx", zero[0]).putFloat("ly", zero[1]).putFloat("le", zero[2]).apply()
        }, Modifier.weight(1f))
        WButton("Reset", { zero = floatArrayOf(0f, 0f, 0f); prefs(context).edit().remove("lx").remove("ly").remove("le").apply() }, Modifier.weight(1f), kind = ButtonKind.Plain)
    }
    if (mode != 2) Hint("Set as zero if your phone case or camera bump makes it sit at a slight angle.")
}

/* ------------------------------------------------ ruler ------------------------------------------------ */

@Composable
private fun Ruler() {
    val p = wonderPalette()
    val context = LocalContext.current
    val density = LocalDensity.current
    var inches by rememberSaveable { mutableStateOf(prefs(context).getBoolean("inches", java.util.Locale.getDefault().country in setOf("US", "LR", "MM"))) }
    var scale by remember { mutableFloatStateOf(prefs(context).getFloat("rulerScale", 1f)) }
    var calibrating by remember { mutableStateOf(false) }
    // Real pixels per millimetre from the screen's own size, times a correction the user can set with a bank card.
    val ydpi = context.resources.displayMetrics.ydpi
    val pxPerMm = ydpi / 25.4f * scale
    var markPx by remember { mutableFloatStateOf(pxPerMm * 50f) }
    val mm = markPx / pxPerMm
    Segmented(listOf("Centimetres", "Inches"), if (inches) 1 else 0, Modifier.fillMaxWidth()) { inches = it == 1; prefs(context).edit().putBoolean("inches", inches).apply() }
    BigValue(if (inches) "%.2f in".format(mm / 25.4f) else "%.1f cm".format(mm / 10f), if (inches) "%.1f cm".format(mm / 10f) else "%.2f in".format(mm / 25.4f))
    val heightDp = with(density) { (pxPerMm * (if (calibrating) 95f else 150f)).toDp() }
    Box(Modifier.fillMaxWidth().height(heightDp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF3C4))
        .pointerInput(pxPerMm) { detectTapGestures { markPx = it.y.coerceAtLeast(0f) } }
        .pointerInput(pxPerMm) { detectDragGestures { ch, _ -> markPx = ch.position.y.coerceAtLeast(0f) } }) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = Color(0xFF3A2A10)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(58, 42, 16); textSize = 30f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            if (!inches) {
                var i = 0
                while (i * pxPerMm <= size.height) {
                    val y = i * pxPerMm; val len = when { i % 10 == 0 -> 70f; i % 5 == 0 -> 46f; else -> 26f }
                    drawLine(ink, Offset(0f, y), Offset(len, y), if (i % 10 == 0) 3f else 1.6f)
                    if (i % 10 == 0 && i > 0) drawContext.canvas.nativeCanvas.drawText("${i / 10}", 80f, y + 11f, paint)
                    i++
                }
            } else {
                val pxPerSixteenth = pxPerMm * 25.4f / 16f; var i = 0
                while (i * pxPerSixteenth <= size.height) {
                    val y = i * pxPerSixteenth; val len = when { i % 16 == 0 -> 70f; i % 8 == 0 -> 52f; i % 4 == 0 -> 38f; i % 2 == 0 -> 28f; else -> 18f }
                    drawLine(ink, Offset(0f, y), Offset(len, y), if (i % 16 == 0) 3f else 1.6f)
                    if (i % 16 == 0 && i > 0) drawContext.canvas.nativeCanvas.drawText("${i / 16}", 80f, y + 11f, paint)
                    i++
                }
            }
            drawLine(p.accent, Offset(0f, 2f), Offset(size.width, 2f), 4f)
            drawLine(Color(0xFFE5483C), Offset(0f, markPx), Offset(size.width, markPx), 5f)
            drawCircle(Color(0xFFE5483C), 16f, Offset(size.width - 40f, markPx))
            if (calibrating) {
                // A bank card is exactly 85.60 mm long: the box shows where its edge should land.
                val cardPx = 85.6f * pxPerMm
                drawRoundRect(Color(0x332E7FE8), Offset(size.width * .35f, 0f), Size(size.width * .55f, cardPx), CornerRadius(24f))
                drawRoundRect(Color(0xFF2E7FE8), Offset(size.width * .35f, 0f), Size(size.width * .55f, cardPx), CornerRadius(24f), style = Stroke(4f))
            }
        }
    }
    Hint(if (calibrating) "Hold a bank or ID card against the screen with its short edge on the top line, then slide until the blue box matches the card's length."
        else "Line one end of the object up with the blue line at the top, then tap or drag the red line to its other end.")
    if (calibrating) {
        Slider(scale, { scale = it }, valueRange = .85f..1.15f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WButton("Done", { calibrating = false; prefs(context).edit().putFloat("rulerScale", scale).apply() }, Modifier.weight(1f), kind = ButtonKind.Primary)
            WButton("Reset", { scale = 1f }, Modifier.weight(1f), kind = ButtonKind.Plain)
        }
    } else WButton("Check with a card", { calibrating = true }, Modifier.fillMaxWidth())
}

/* ------------------------------------------------ camera ruler ------------------------------------------------ */

@Composable
private fun CameraRuler() {
    val p = wonderPalette()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val metric = !java.util.Locale.getDefault().country.let { it == "US" || it == "LR" || it == "MM" }
    var heightText by rememberSaveable { mutableStateOf(prefs(context).getString("eye", null) ?: if (metric) "150" else "59") }
    val eyeM = (heightText.toFloatOrNull() ?: 0f).let { if (metric) it / 100f else it * .0254f }
    var distance by rememberSaveable { mutableStateOf<Float?>(null) }
    var objHeight by rememberSaveable { mutableStateOf<Float?>(null) }
    val g = rememberGravity()
    // The back camera looks along the phone's -Z axis; the angle between that and straight down is how far the camera is raised.
    val fromDown = g?.let { val n = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]).coerceAtLeast(.1f); Math.toDegrees(acos((it[2] / n).coerceIn(-1f, 1f).toDouble())).toFloat() }
    fun len(m: Float) = if (metric) (if (m < 10f) "%.2f m".format(m) else "%.1f m".format(m)) else { val inch = m / .0254f; "%d ft %d in".format((inch / 12).toInt(), (inch % 12).roundToInt()) }

    Panel {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (!granted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Text("The camera ruler uses the camera to aim. Nothing is recorded or kept.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp)); WButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Primary)
                }
            } else {
                AndroidView(factory = { ctx ->
                    val view = PreviewView(ctx); val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview) }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                }, Modifier.fillMaxSize(), onRelease = { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } })
                // Crosshair
                Canvas(Modifier.fillMaxSize()) {
                    val c = center; val r = size.minDimension * .07f
                    drawCircle(Color.White, r, c, style = Stroke(4f)); drawCircle(Color(0xFFE5483C), 6f, c)
                    drawLine(Color.White, Offset(c.x - r * 2.2f, c.y), Offset(c.x - r * 1.2f, c.y), 4f); drawLine(Color.White, Offset(c.x + r * 1.2f, c.y), Offset(c.x + r * 2.2f, c.y), 4f)
                    drawLine(Color.White, Offset(c.x, c.y - r * 2.2f), Offset(c.x, c.y - r * 1.2f), 4f); drawLine(Color.White, Offset(c.x, c.y + r * 1.2f), Offset(c.x, c.y + r * 2.2f), 4f)
                }
                Text(if (distance == null) "1. Aim the dot where the object meets the floor" else "2. Aim the dot at the top of the object",
                    Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WButton("Mark the bottom", {
            val a = fromDown ?: return@WButton
            if (a < 88f && eyeM > 0f) { distance = eyeM * tan(Math.toRadians(a.toDouble())).toFloat(); objHeight = null }
        }, Modifier.weight(1f), kind = if (distance == null) ButtonKind.Primary else ButtonKind.Secondary, enabled = granted && fromDown != null)
        WButton("Mark the top", {
            val a = fromDown ?: return@WButton; val d = distance ?: return@WButton
            objHeight = (eyeM + d * tan(Math.toRadians((a - 90f).toDouble())).toFloat()).coerceAtLeast(0f)
        }, Modifier.weight(1f), kind = if (distance != null) ButtonKind.Primary else ButtonKind.Secondary, enabled = granted && distance != null)
    }
    Panel(title = "Result") {
        ListRow("Distance to it", trailing = { Text(distance?.let { len(it) } ?: "Mark the bottom", style = MaterialTheme.typography.titleMedium, color = if (distance != null) p.accent else p.secondaryText) })
        PanelDivider(16.dp)
        ListRow("Its height", trailing = { Text(objHeight?.let { len(it) } ?: if (distance == null) "-" else "Mark the top", style = MaterialTheme.typography.titleMedium, color = if (objHeight != null) p.accent else p.secondaryText) })
        PanelDivider(16.dp)
        ListRow("Camera angle", trailing = { Text(fromDown?.let { "%.1f° from straight down".format(it) } ?: "-", color = p.secondaryText) })
    }
    Panel(title = "Your phone's height", footer = "Measure from the floor to the phone as you hold it (about your eye level less ${if (metric) "10 cm" else "4 in"}). The answers are only as good as this number, and assume the floor is level.") {
        OutlinedTextField(heightText, { v -> heightText = v.filter { it.isDigit() || it == '.' }; prefs(context).edit().putString("eye", heightText).apply(); distance = null; objHeight = null },
            Modifier.fillMaxWidth().padding(12.dp), label = { Text(if (metric) "Height in centimetres" else "Height in inches") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    if (distance != null) WButton("Start again", { distance = null; objHeight = null }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
}

/* ------------------------------------------------ compass ------------------------------------------------ */

private fun cardinal(deg: Float) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

@Composable
private fun Compass() {
    val p = wonderPalette()
    val rot by rememberSensor(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_UI)
    val mag by rememberSensor(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI)
    val r = rot ?: return if (!hasSensor(Sensor.TYPE_ROTATION_VECTOR)) Missing("a compass") else Unit
    val m = FloatArray(9); val o = FloatArray(3)
    SensorManager.getRotationMatrixFromVector(m, r.values); SensorManager.getOrientation(m, o)
    val target = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
    // Ease the dial round the short way so it glides instead of jittering.
    val heading = rememberEased(target, .15f, angle = true) ?: target
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(260.dp)) {
                val c = center; val rad = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFDFDFB), Color(0xFFE6E2D6)), c, rad), rad, c)
                drawCircle(Color.Black.copy(.35f), rad, c, style = Stroke(5f))
                rotate(-heading, c) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = rad * .16f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                    for (d in 0 until 360 step 5) {
                        val a = Math.toRadians(d.toDouble()); val inner = if (d % 30 == 0) rad * .82f else rad * .9f
                        drawLine(Color.Black.copy(if (d % 30 == 0) .7f else .35f), Offset(c.x + (sin(a) * inner).toFloat(), c.y - (cos(a) * inner).toFloat()), Offset(c.x + (sin(a) * rad * .96f).toFloat(), c.y - (cos(a) * rad * .96f).toFloat()), if (d % 30 == 0) 4f else 2f)
                    }
                    listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (t, d) ->
                        val a = Math.toRadians(d.toDouble()); paint.color = if (t == "N") android.graphics.Color.rgb(214, 52, 40) else android.graphics.Color.rgb(40, 40, 40)
                        drawContext.canvas.nativeCanvas.drawText(t, c.x + (sin(a) * rad * .64f).toFloat(), c.y - (cos(a) * rad * .64f).toFloat() + paint.textSize * .36f, paint)
                    }
                    val needle = Path().apply { moveTo(c.x, c.y - rad * .5f); lineTo(c.x + rad * .07f, c.y); lineTo(c.x - rad * .07f, c.y); close() }
                    drawPath(needle, Color(0xFFD63428))
                    val tail = Path().apply { moveTo(c.x, c.y + rad * .5f); lineTo(c.x + rad * .07f, c.y); lineTo(c.x - rad * .07f, c.y); close() }
                    drawPath(tail, Color(0xFF555555))
                }
                drawCircle(Color(0xFF333333), rad * .05f, c)
                // The fixed mark at the top shows which way the phone points.
                drawPath(Path().apply { moveTo(c.x, c.y - rad - 2f); lineTo(c.x - 14f, c.y - rad + 26f); lineTo(c.x + 14f, c.y - rad + 26f); close() }, p.accent)
            }
            BigValue("${target.roundToInt() % 360}° ${cardinal(target)}", "Magnetic heading")
            val accuracy = mag?.accuracy ?: r.accuracy
            if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) Text("The compass needs settling: wave the phone in a figure of eight a few times.", color = Color(0xFFD9822B), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Hint("Hold the phone flat. Keep it away from magnets, speakers and metal for the truest reading.")
        }
    }
}

/* ------------------------------------------------ temperature ------------------------------------------------ */

@Composable
private fun Temperature() {
    val p = wonderPalette()
    val context = LocalContext.current
    var fahrenheit by rememberSaveable { mutableStateOf(prefs(context).getBoolean("fahrenheit", java.util.Locale.getDefault().country == "US")) }
    fun t(c: Float) = if (fahrenheit) "%.1f °F".format(c * 9f / 5f + 32f) else "%.1f °C".format(c)
    // Readings outside what air can really be are a sensor glitch (the first one is sometimes junk), so they are left out.
    val airRaw by rememberSensor(Sensor.TYPE_AMBIENT_TEMPERATURE, SensorManager.SENSOR_DELAY_NORMAL)
    val humidityRaw by rememberSensor(Sensor.TYPE_RELATIVE_HUMIDITY, SensorManager.SENSOR_DELAY_NORMAL)
    val air = airRaw?.takeIf { it.values[0] in -60f..80f }
    val humidity = humidityRaw?.takeIf { it.values[0] in 0f..100f }
    var battery by remember { mutableStateOf<Float?>(null) }
    var thermal by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
            thermal = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            delay(2000)
        }
    }
    val history = rememberHistory(air?.values?.get(0) ?: battery, 90)
    Segmented(listOf("°C", "°F"), if (fahrenheit) 1 else 0, Modifier.fillMaxWidth()) { fahrenheit = it == 1; prefs(context).edit().putBoolean("fahrenheit", fahrenheit).apply() }
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val main = air?.values?.get(0) ?: battery
            // A thermometer drawing whose red column follows the reading (from -10 to 50 °C).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Canvas(Modifier.width(48.dp).height(190.dp)) {
                    val w = size.width; val h = size.height; val bulb = w * .5f
                    drawRoundRect(Color.White, Offset(w * .3f, 0f), Size(w * .4f, h - bulb * 1.4f), CornerRadius(w * .2f))
                    drawRoundRect(Color.Black.copy(.35f), Offset(w * .3f, 0f), Size(w * .4f, h - bulb * 1.4f), CornerRadius(w * .2f), style = Stroke(3f))
                    val f = (((main ?: 20f) + 10f) / 60f).coerceIn(0f, 1f); val top = (h - bulb * 1.4f) * (1f - f)
                    drawRect(Color(0xFFE5483C), Offset(w * .38f, top), Size(w * .24f, h - bulb - top))
                    drawCircle(Color(0xFFE5483C), bulb, Offset(w / 2, h - bulb)); drawCircle(Color.Black.copy(.35f), bulb, Offset(w / 2, h - bulb), style = Stroke(3f))
                }
                Column {
                    Text(main?.let { t(it) } ?: "-", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                    Text(if (air != null) "Air temperature" else "Phone temperature", style = MaterialTheme.typography.titleSmall, color = p.accent)
                    if (humidity != null) Text("Humidity %.0f%%".format(humidity.values[0]), style = MaterialTheme.typography.titleSmall, color = p.text)
                }
            }
            Trace(history)
            if (air == null) Hint("This phone has no air thermometer, so this is its battery's temperature. Off the charger and left alone for 15 minutes, it comes close to the room's.")
        }
    }
    Panel(title = "Inside the phone") {
        ListRow("Battery", trailing = { Text(battery?.let { t(it) } ?: "-", color = p.text) })
        PanelDivider(16.dp)
        ListRow("Heat level", subtitle = "How hot Android thinks the phone is running", trailing = {
            Text(when (thermal) { PowerManager.THERMAL_STATUS_NONE -> "Cool"; PowerManager.THERMAL_STATUS_LIGHT -> "Warm"; PowerManager.THERMAL_STATUS_MODERATE -> "Hot"; PowerManager.THERMAL_STATUS_SEVERE -> "Very hot"; else -> "Too hot" },
                color = if (thermal <= PowerManager.THERMAL_STATUS_LIGHT) Color(0xFF2EAE4E) else Color(0xFFE5483C))
        })
    }
}

/* ------------------------------------------------ sound ------------------------------------------------ */

private fun soundLabel(db: Float) = when {
    db < 30f -> "Whisper quiet"; db < 45f -> "Quiet room"; db < 60f -> "Normal talking"; db < 75f -> "Busy street"; db < 90f -> "Loud: a hair dryer or traffic"; else -> "Very loud: protect your ears"
}

@SuppressLint("MissingPermission")
@Composable
private fun SoundMeter() {
    val p = wonderPalette()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var level by remember { mutableStateOf<Float?>(null) }
    var peak by remember { mutableFloatStateOf(0f) }
    var sum by remember { mutableDoubleStateOf(0.0) }
    var count by remember { mutableIntStateOf(0) }
    if (!granted) {
        Panel {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The sound meter listens with the microphone while this screen is open. Nothing is recorded or kept.", color = p.text, textAlign = TextAlign.Center)
                WButton("Allow microphone", { ask.launch(Manifest.permission.RECORD_AUDIO) }, kind = ButtonKind.Primary)
            }
        }
        return
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val rate = 44100
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull()
                ?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext
            val buf = ShortArray(size / 2)
            try {
                rec.startRecording()
                var smooth = 0f
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                    var s = 0.0; for (i in 0 until n) { val v = buf[i] / 32768.0; s += v * v }
                    // Android requires the voice recognition input to give an RMS of about 2500 (of 32768) for a 94 dB tone,
                    // so the level in dB below full scale plus about 116 is the sound level in the room.
                    val db = (20 * log10(sqrt(s / n).coerceAtLeast(1e-6)) + 116.3).toFloat().coerceIn(0f, 130f)
                    smooth = if (smooth == 0f) db else smooth + (db - smooth) * .3f
                    // The scene fizzes with the room: louder is more bubbles, rising faster.
                    SceneFeed.waveOn = true; SceneFeed.push(((smooth - 30f) / 70f).coerceIn(0f, 1f))
                    withContext(Dispatchers.Main) { level = smooth; if (smooth > peak) peak = smooth; sum += smooth; count++ }
                }
            } finally { runCatching { rec.stop() }; rec.release(); SceneFeed.waveOn = false; SceneFeed.amp = 0f }
        }
    }
    val history = rememberHistory(level, 150)
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val db = level ?: 0f
            Canvas(Modifier.size(240.dp, 140.dp)) {
                val c = Offset(size.width / 2, size.height * .95f); val r = size.width * .45f
                val zones = listOf(0f to Color(0xFF2EAE4E), 60f to Color(0xFFE8C030), 85f to Color(0xFFE5483C))
                zones.forEachIndexed { i, (from, col) ->
                    val to = zones.getOrNull(i + 1)?.first ?: 120f
                    drawArc(col, 180f + from / 120f * 180f, (to - from) / 120f * 180f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(22f))
                }
                val a = Math.toRadians((180.0 + db / 120.0 * 180.0))
                drawLine(p.text, c, Offset(c.x + (cos(a) * r * .9f).toFloat(), c.y + (sin(a) * r * .9f).toFloat()), 7f, StrokeCap.Round)
                drawCircle(p.text, 12f, c)
            }
            BigValue(if (level == null) "Listening…" else "${db.roundToInt()} dB", if (level == null) null else soundLabel(db))
            Trace(history, min = 20f, max = 110f)
        }
    }
    Panel {
        ListRow("Loudest", trailing = { Text(if (peak > 0f) "${peak.roundToInt()} dB" else "-", color = p.text) })
        PanelDivider(16.dp)
        ListRow("Average", trailing = { Text(if (count > 0) "${(sum / count).roundToInt()} dB" else "-", color = p.text) })
    }
    WButton("Start over", { peak = 0f; sum = 0.0; count = 0 }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
    Hint("Phone microphones are tuned for voices, so treat this as a close guide rather than a certified meter.")
}

/* ------------------------------------------------ light ------------------------------------------------ */

private fun luxLabel(lux: Float) = when {
    lux < 1f -> "Pitch dark"; lux < 20f -> "Dim, like candlelight"; lux < 150f -> "Living room"; lux < 400f -> "Soft indoor light"; lux < 1000f -> "Good for reading and work"; lux < 5000f -> "Bright office or shade"; lux < 25000f -> "Daylight"; else -> "Direct sunlight"
}

@Composable
private fun LightMeter() {
    val p = wonderPalette()
    val r by rememberSensor(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_UI)
    if (r == null && !hasSensor(Sensor.TYPE_LIGHT)) return Missing("a light sensor")
    val lux = r?.values?.get(0)
    var peak by remember { mutableFloatStateOf(0f) }
    if (lux != null && lux > peak) peak = lux
    val history = rememberHistory(lux?.let { ln(1f + it) }, 150)
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val f = ((ln(1f + (lux ?: 0f)) / ln(40001f))).coerceIn(0f, 1f)
            Canvas(Modifier.size(170.dp)) {
                val c = center; val rad = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF6C0).copy(alpha = .3f + .7f * f), Color(0xFFFFC23A).copy(alpha = .15f + .6f * f), Color.Transparent), c, rad), rad, c)
                drawCircle(Color(0xFFFFC23A), rad * (.22f + .16f * f), c)
                for (k in 0 until 12) {
                    val a = k * PI / 6; val r1 = rad * (.45f + .05f * f); val r2 = rad * (.55f + .4f * f)
                    drawLine(Color(0xFFFFB020).copy(alpha = .4f + .6f * f), Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()), Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()), 7f, StrokeCap.Round)
                }
            }
            BigValue(lux?.let { if (it < 10f) "%.1f lux".format(it) else "%,d lux".format(it.roundToInt()) } ?: "-", lux?.let { luxLabel(it) })
            Trace(history, min = 0f, max = ln(40001f))
        }
    }
    Panel { ListRow("Brightest", trailing = { Text("%,d lux".format(peak.roundToInt()), color = p.text) }) }
    WButton("Start over", { peak = 0f }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
    Hint("The light sensor sits near the top of the screen. A desk for reading or work wants at least 500 lux; most houseplants like 1,000 or more.")
}

/* ------------------------------------------------ motion ------------------------------------------------ */

@Composable
private fun Motion() {
    val p = wonderPalette()
    val gyro by rememberSensor(Sensor.TYPE_GYROSCOPE)
    val linear by rememberSensor(if (hasSensor(Sensor.TYPE_LINEAR_ACCELERATION)) Sensor.TYPE_LINEAR_ACCELERATION else Sensor.TYPE_ACCELEROMETER)
    var peakG by remember { mutableFloatStateOf(0f) }
    var peakSpin by remember { mutableFloatStateOf(0f) }
    val gForce = linear?.values?.let { v -> sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / 9.81f }
    val spin = gyro?.values?.let { v -> Math.toDegrees(sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat() }
    if (gForce != null && gForce > peakG) peakG = gForce
    if (spin != null && spin > peakSpin) peakSpin = spin
    val gHistory = rememberHistory(gForce, 150)
    val sHistory = rememberHistory(spin, 150)
    Panel(title = "Turning (gyroscope)") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gyro == null) Text("This phone doesn't have a gyroscope.", color = p.secondaryText)
            else {
                BigValue("${(spin ?: 0f).roundToInt()}°/s", "%.0f turns a minute".format((spin ?: 0f) / 6f))
                val v = gyro!!.values
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Tilt forward" to v[0], "Tilt sideways" to v[1], "Spin flat" to v[2]).forEach { (n, x) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%+.0f°/s".format(Math.toDegrees(x.toDouble())), style = MaterialTheme.typography.titleMedium, color = p.text, fontFamily = FontFamily.Monospace)
                            Text(n, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                        }
                    }
                }
                Trace(sHistory, min = 0f)
            }
        }
    }
    Panel(title = "Pushing (g-force)") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigValue("%.2f g".format(gForce ?: 0f), "Strongest %.2f g  ·  fastest turn %d°/s".format(peakG, peakSpin.roundToInt()))
            Trace(gHistory, min = 0f)
        }
    }
    WButton("Start over", { peakG = 0f; peakSpin = 0f }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
    Hint("Try it in a car, on a ride or on a swing: g-force shows how hard you are pushed, without gravity counted.")
}

/* ------------------------------------------------ air pressure ------------------------------------------------ */

@Composable
private fun AirPressure() {
    val p = wonderPalette()
    val context = LocalContext.current
    val r by rememberSensor(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_UI)
    if (r == null && !hasSensor(Sensor.TYPE_PRESSURE)) return Missing("an air pressure sensor (barometer)")
    val hpa = r?.values?.get(0)
    val smooth = rememberEased(hpa, .05f)
    val alt = smooth?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it) }
    var start by remember { mutableStateOf<Float?>(null) }
    if (start == null && alt != null) start = alt
    val climbed = if (alt != null && start != null) alt - start!! else null
    val metric = java.util.Locale.getDefault().country != "US"
    fun m(v: Float) = if (metric) "%.1f m".format(v) else "%.0f ft".format(v / .3048f)
    val history = rememberHistory(alt, 150)
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(climbed?.let { (if (it >= 0) "+" else "") + m(it) } ?: "-", "Up or down since you started")
            Trace(history)
        }
    }
    Panel {
        ListRow("Air pressure", trailing = { Text(smooth?.let { "%.1f hPa".format(it) } ?: "-", color = p.text) })
        PanelDivider(16.dp)
        ListRow("Height above sea level", subtitle = "A rough guess: the weather moves it by tens of metres", trailing = { Text(alt?.let { m(it) } ?: "-", color = p.text) })
        PanelDivider(16.dp)
        ListRow("Floors", subtitle = "About 3 metres each", trailing = { Text(climbed?.let { "%+.1f".format(it / 3f) } ?: "-", color = p.text) })
    }
    WButton("Start from here", { start = alt }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
    Hint("Changes in height are much more exact than the height itself: take the stairs and watch the floors count.")
}

/* ------------------------------------------------ magnet ------------------------------------------------ */

@Composable
private fun MagnetFinder() {
    val p = wonderPalette()
    val haptic = LocalHapticFeedback.current
    val r by rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    if (r == null && !hasSensor(Sensor.TYPE_MAGNETIC_FIELD)) return Missing("a magnetic sensor")
    val b = r?.values?.let { v -> sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) }
    var base by remember { mutableStateOf<Float?>(null) }
    if (base == null && b != null) base = b
    val extra = if (b != null && base != null) b - base!! else 0f
    val found = extra > 25f
    LaunchedEffect(found) { if (found) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val history = rememberHistory(b, 150)
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val f = (extra / 200f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(p.accent.copy(alpha = .14f))) {
                Box(Modifier.fillMaxWidth(f.coerceAtLeast(.02f)).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(if (found) Color(0xFFE5483C) else p.accent))
            }
            BigValue(b?.let { "${it.roundToInt()} µT" } ?: "-", when { b == null -> null; found -> "Something magnetic or metal is close"; else -> "Just the Earth's field" }, if (found) Color(0xFFE5483C) else null)
            Trace(history, min = 0f)
        }
    }
    WButton("Set this spot as normal", { base = b }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
    Hint("Sweep the top of the phone slowly along a wall to find screws, nails, pipes or wires behind it. It buzzes when it finds one.")
}
