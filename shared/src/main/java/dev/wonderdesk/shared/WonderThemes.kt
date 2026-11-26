package dev.wonderdesk.shared

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Each app's own picture in its animated scene, drawn over the water, hills or nebula: beads sliding on rods in Abacus, turning gears and a
 * clock that tells the real time in Timepiece, pages drifting down in Chronicle, rings spreading in Ripple, a compass in Wayfinder and so on.
 * Everything is soft, slow and see-through, so it sits behind the app like a watermark and never fights with what is on screen.
 */
internal class AppThemeLayer(private val w: Int, private val h: Int, private val theme: String) {
    private val rnd = java.util.Random(theme.hashCode().toLong())
    private val m = max(w, h).toFloat()
    private val s = min(w, h).toFloat()
    private var t = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD) }
    private val path = Path()
    private val rect = RectF()

    // Abacus: rods with beads that now and then slide across, as if someone were counting.
    private class Bead(var x: Float, var target: Float, val rod: Int, val hueOff: Float)
    private val rods = listOf(.2f, .38f, .56f, .74f)
    private val beads = ArrayList<Bead>().apply { rods.indices.forEach { r -> repeat(7) { i -> val x = .08f + i * .055f; add(Bead(x, x, r, i * 9f)) } } }
    private var nextCount = 2f

    // Things that drift: pages, photo frames, boxes.
    private class Drift(var x: Float, var y: Float, val size: Float, val speed: Float, var rot: Float, val spin: Float, val sway: Float, val ph: Float, val n: Int)
    private val drifts = List(when (theme) { "pages" -> 9; "frames" -> 7; "boxes" -> 8; else -> 0 }) { newDrift(true) }
    private fun newDrift(anywhere: Boolean) = Drift(rnd.nextFloat() * w, if (anywhere) rnd.nextFloat() * h else if (theme == "pages") -s * .2f else h + s * .2f,
        s * (.09f + rnd.nextFloat() * .07f), h * (.018f + rnd.nextFloat() * .02f), rnd.nextFloat() * 360f, (rnd.nextFloat() - .5f) * 14f, w * (.02f + rnd.nextFloat() * .03f), rnd.nextFloat() * 6.28f, 1 + rnd.nextInt(31))

    // Rings that spread from random places (Ripple, Echo, Beacon).
    private class Ring(val x: Float, val y: Float, var age: Float, val life: Float)
    private val rings = ArrayList<Ring>()
    private var nextRing = .3f

    // Constellations (Polaris) and the route on the compass map (Wayfinder).
    private val stars = List(18) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .85f, rnd.nextFloat() * 6.28f) }

    fun step(dt: Float) {
        t += dt
        when (theme) {
            "beads" -> {
                nextCount -= dt
                if (nextCount <= 0f) {
                    nextCount = 1.4f + rnd.nextFloat() * 2.6f
                    // Push one bead (and every bead beside it) to the other end of its rod.
                    val rod = rnd.nextInt(rods.size); val onRod = beads.filter { it.rod == rod }.sortedBy { it.target }
                    val k = rnd.nextInt(onRod.size); val toRight = onRod[k].target < .5f
                    val moving = if (toRight) onRod.drop(k) else onRod.take(k + 1)
                    moving.forEachIndexed { i, b -> b.target = if (toRight) .92f - (moving.size - 1 - i) * .055f else .08f + i * .055f }
                }
                for (b in beads) b.x += (b.target - b.x) * min(1f, dt * 3.2f)
            }
            "pages", "frames", "boxes" -> for (d in drifts) {
                d.y += if (theme == "pages") d.speed * dt else -d.speed * dt
                d.rot += d.spin * dt
                val gone = if (theme == "pages") d.y > h + d.size * 1.5f else d.y < -d.size * 1.5f
                if (gone) { val n = newDrift(false); d.x = n.x; d.y = n.y }
            }
            "rings" -> {
                nextRing -= dt
                if (nextRing <= 0f) { nextRing = 1.6f + rnd.nextFloat() * 2.2f; rings += Ring(rnd.nextFloat() * w, h * (.15f + rnd.nextFloat() * .7f), 0f, 5.5f + rnd.nextFloat() * 2f) }
                rings.forEach { it.age += dt }; rings.removeAll { it.age > it.life }
            }
        }
    }

    /** [color] is the app's own colour; [alpha] how strongly to draw (lower at night and in the darker looks). */
    fun draw(c: Canvas, color: Int, alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        if (a < 4) return
        fun col(x: Float) = Color.argb((a * x).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        val white = { x: Float -> Color.argb((a * x).toInt().coerceIn(0, 255), 255, 255, 255) }
        when (theme) {
            "beads" -> {
                line.strokeWidth = s * .006f
                for ((i, ry) in rods.withIndex()) {
                    val y = h * ry
                    line.color = col(.55f); c.drawLine(w * .02f, y, w * .98f, y, line)
                    for (b in beads.filter { it.rod == i }) {
                        val x = w * b.x; val r = s * .032f
                        rect.set(x - r * 1.25f, y - r, x + r * 1.25f, y + r)
                        fill.shader = RadialGradient(x - r * .4f, y - r * .5f, r * 1.6f, intArrayOf(white(.95f), col(.9f), col(.55f)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                        c.drawOval(rect, fill); fill.shader = null
                    }
                }
            }
            "gears" -> {
                drawGear(c, w * .78f, h * .2f, s * .26f, 14, t * 8f, col(.55f))
                drawGear(c, w * .78f - s * .43f, h * .2f + s * .2f, s * .17f, 9, -t * 8f * 14f / 9f + 12f, col(.45f))
                drawGear(c, w * .2f, h * .82f, s * .3f, 16, t * 5f, col(.4f))
                // A big faint clock face showing the real time.
                val cx = w * .5f; val cy = h * .5f; val r = s * .36f
                line.color = col(.35f); line.strokeWidth = s * .006f; c.drawCircle(cx, cy, r, line)
                for (k in 0 until 12) { val an = k * 30f * PI.toFloat() / 180f; val r1 = r * (if (k % 3 == 0) .84f else .9f); c.drawLine(cx + sin(an) * r1, cy - cos(an) * r1, cx + sin(an) * r * .97f, cy - cos(an) * r * .97f, line) }
                val now = java.time.LocalTime.now(); val mins = now.minute + now.second / 60f; val hrs = (now.hour % 12) + mins / 60f
                line.strokeWidth = s * .014f; line.color = col(.5f)
                val ha = hrs * 30f * PI.toFloat() / 180f; c.drawLine(cx, cy, cx + sin(ha) * r * .5f, cy - cos(ha) * r * .5f, line)
                line.strokeWidth = s * .009f
                val ma = mins * 6f * PI.toFloat() / 180f; c.drawLine(cx, cy, cx + sin(ma) * r * .78f, cy - cos(ma) * r * .78f, line)
            }
            "pages" -> for (d in drifts) {
                val x = d.x + sin(t * .5f + d.ph) * d.sway
                c.save(); c.translate(x, d.y); c.rotate(d.rot + sin(t * .7f + d.ph) * 14f)
                val pw = d.size; val ph = d.size * 1.3f
                rect.set(-pw / 2, -ph / 2, pw / 2, ph / 2)
                fill.color = white(.55f); c.drawRoundRect(rect, pw * .08f, pw * .08f, fill)
                line.color = col(.6f); line.strokeWidth = s * .004f; c.drawRoundRect(rect, pw * .08f, pw * .08f, line)
                line.strokeWidth = s * .003f
                for (k in 1..4) { val y = -ph / 2 + ph * (.25f + k * .14f); c.drawLine(-pw * .32f, y, pw * (if (k == 4) .05f else .32f), y, line) }
                // A calendar page shows a day number on top.
                fill.color = col(.7f); c.drawRect(-pw / 2, -ph / 2, pw / 2, -ph / 2 + ph * .18f, fill)
                text.color = col(.75f); text.textSize = ph * .2f; c.drawText(d.n.toString(), 0f, -ph / 2 + ph * .4f, text)
                c.restore()
            }
            "frames" -> for (d in drifts) {
                val x = d.x + sin(t * .4f + d.ph) * d.sway
                c.save(); c.translate(x, d.y); c.rotate(d.rot * .3f + sin(t * .5f + d.ph) * 8f)
                val fw = d.size * 1.1f; val fh = d.size * 1.3f
                rect.set(-fw / 2, -fh / 2, fw / 2, fh / 2); fill.color = white(.7f); c.drawRect(rect, fill)
                // A little "photo": a soft sky over a hill, in the app's colour.
                rect.set(-fw * .42f, -fh * .42f, fw * .42f, fh * .22f)
                fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, col(.35f), col(.75f), Shader.TileMode.CLAMP); c.drawRect(rect, fill); fill.shader = null
                fill.color = white(.6f); c.drawCircle(fw * .2f, -fh * .25f, fw * .07f, fill)
                c.restore()
            }
            "boxes" -> for (d in drifts) {
                val x = d.x + sin(t * .45f + d.ph) * d.sway
                c.save(); c.translate(x, d.y); c.rotate(sin(t * .3f + d.ph) * 10f)
                val b = d.size * .8f
                rect.set(-b / 2, -b / 2, b / 2, b / 2)
                fill.shader = LinearGradient(0f, -b / 2, 0f, b / 2, white(.65f), col(.6f), Shader.TileMode.CLAMP); c.drawRoundRect(rect, b * .14f, b * .14f, fill); fill.shader = null
                line.color = col(.7f); line.strokeWidth = s * .004f; c.drawRoundRect(rect, b * .14f, b * .14f, line)
                c.drawLine(0f, -b / 2, 0f, b / 2, line); c.drawLine(-b / 2, -b * .12f, b / 2, -b * .12f, line)
                c.restore()
            }
            "rings" -> {
                for (r in rings) {
                    val f = r.age / r.life
                    line.strokeWidth = s * .005f
                    for (k in 0..2) {
                        val ff = f - k * .12f; if (ff <= 0f) continue
                        line.color = col(.7f * (1f - ff).coerceIn(0f, 1f)); c.drawCircle(r.x, r.y, s * .5f * ff, line)
                    }
                }
            }
            "radar" -> {
                val cx = w * .5f; val cy = h * .55f; val r = s * .48f
                line.strokeWidth = s * .004f; line.color = col(.4f)
                for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, line)
                c.drawLine(cx - r, cy, cx + r, cy, line); c.drawLine(cx, cy - r, cx, cy + r, line)
                val ang = (t * 40f) % 360f
                fill.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, col(.5f)), floatArrayOf(0f, .8f, 1f))
                c.save(); c.rotate(ang - 90f, cx, cy); c.drawCircle(cx, cy, r, fill); c.restore(); fill.shader = null
                for (st in stars.take(6)) { val d = ((Math.toDegrees(kotlin.math.atan2((st[1] - cy).toDouble(), (st[0] - cx).toDouble())).toFloat() + 450f) % 360f); val since = ((ang - d + 360f) % 360f) / 360f
                    if (kotlin.math.hypot(st[0] - cx, st[1] - cy) < r) { fill.color = col(.8f * (1f - since)); c.drawCircle(st[0], st[1], s * .012f, fill) } }
            }
            "sound" -> {
                // Bars along the bottom that bounce like a level meter, under spreading rings.
                val n = 22; val bw = w / (n * 1.5f)
                for (k in 0 until n) {
                    val v = (.35f + .3f * sin(t * 2.1f + k * .7f) + .25f * sin(t * 3.7f + k * 1.9f)).coerceIn(.08f, 1f)
                    val bh = h * .18f * v; val x = w * (k + .5f) / n
                    rect.set(x - bw / 2, h - bh, x + bw / 2, h.toFloat())
                    fill.shader = LinearGradient(0f, h - bh, 0f, h.toFloat(), white(.6f), col(.6f), Shader.TileMode.CLAMP); c.drawRoundRect(rect, bw * .4f, bw * .4f, fill); fill.shader = null
                }
            }
            "compass" -> {
                val cx = w * .5f; val cy = h * .48f; val r = s * .42f
                c.save(); c.rotate(sin(t * .08f) * 25f, cx, cy)
                line.color = col(.4f); line.strokeWidth = s * .005f; c.drawCircle(cx, cy, r, line); c.drawCircle(cx, cy, r * .86f, line)
                for (k in 0 until 32) { val an = k * 11.25f * PI.toFloat() / 180f; val r1 = r * (if (k % 4 == 0) .78f else .82f); c.drawLine(cx + sin(an) * r1, cy - cos(an) * r1, cx + sin(an) * r * .86f, cy - cos(an) * r * .86f, line) }
                for (k in 0 until 4) {
                    path.reset(); val an = k * 90f * PI.toFloat() / 180f; val an2 = an + PI.toFloat() / 4
                    path.moveTo(cx + sin(an) * r * .74f, cy - cos(an) * r * .74f); path.lineTo(cx + sin(an2) * r * .14f, cy - cos(an2) * r * .14f); path.lineTo(cx, cy)
                    path.lineTo(cx + sin(an - PI.toFloat() / 4) * r * .14f, cy - cos(an - PI.toFloat() / 4) * r * .14f); path.close()
                    fill.color = if (k == 0) col(.6f) else col(.32f); c.drawPath(path, fill)
                }
                c.restore()
                // A dotted route winding across, as on a map.
                line.strokeWidth = s * .006f; line.color = col(.45f); line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(s * .02f, s * .02f), -t * s * .04f)
                path.reset(); path.moveTo(-10f, h * .86f); path.cubicTo(w * .3f, h * .7f, w * .2f, h * .95f, w * .6f, h * .82f); path.cubicTo(w * .85f, h * .74f, w * .9f, h * .9f, w + 10f, h * .78f)
                c.drawPath(path, line); line.pathEffect = null
            }
            "stars" -> {
                line.strokeWidth = s * .003f; line.color = col(.35f)
                for (k in 0 until stars.size - 1) if (k % 3 != 2) { val p = stars[k]; val q = stars[k + 1]; c.drawLine(p[0], p[1], q[0], q[1], line) }
                for (st in stars) { val tw = .55f + .45f * sin(t * 1.4f + st[2]); fill.color = white(.85f * tw); c.drawCircle(st[0], st[1], s * (.006f + .006f * tw), fill) }
            }
            "dial" -> {
                val cx = w * .5f; val cy = h * .46f; val r = s * .4f
                c.save(); c.rotate(sin(t * .15f) * 60f + t * 2f, cx, cy)
                line.color = col(.45f); line.strokeWidth = s * .012f; c.drawCircle(cx, cy, r, line)
                line.strokeWidth = s * .004f
                for (k in 0 until 60) { val an = k * 6f * PI.toFloat() / 180f; val r1 = r * (if (k % 5 == 0) .8f else .87f); c.drawLine(cx + sin(an) * r1, cy - cos(an) * r1, cx + sin(an) * r * .93f, cy - cos(an) * r * .93f, line) }
                text.color = col(.5f); text.textSize = s * .045f
                for (k in 0 until 12) { val an = k * 30f * PI.toFloat() / 180f; c.drawText((k * 5).toString(), cx + sin(an) * r * .68f, cy - cos(an) * r * .68f + text.textSize * .35f, text) }
                c.restore()
                line.color = col(.35f); line.strokeWidth = s * .008f; c.drawCircle(cx, cy, r * .22f, line)
            }
            "shield" -> {
                val cx = w * .5f; val cy = h * .46f; val sc = s * .42f * (1f + .015f * sin(t * 1.1f))
                path.reset(); path.moveTo(cx, cy - sc); path.cubicTo(cx + sc * .55f, cy - sc * .8f, cx + sc * .8f, cy - sc * .85f, cx + sc * .8f, cy - sc * .75f)
                path.cubicTo(cx + sc * .8f, cy + sc * .2f, cx + sc * .4f, cy + sc * .7f, cx, cy + sc); path.cubicTo(cx - sc * .4f, cy + sc * .7f, cx - sc * .8f, cy + sc * .2f, cx - sc * .8f, cy - sc * .75f)
                path.cubicTo(cx - sc * .8f, cy - sc * .85f, cx - sc * .55f, cy - sc * .8f, cx, cy - sc); path.close()
                fill.shader = LinearGradient(0f, cy - sc, 0f, cy + sc, white(.35f), col(.35f), Shader.TileMode.CLAMP); c.drawPath(path, fill); fill.shader = null
                line.color = col(.5f); line.strokeWidth = s * .008f; c.drawPath(path, line)
                line.strokeWidth = s * .02f; path.reset(); path.moveTo(cx - sc * .3f, cy); path.lineTo(cx - sc * .05f, cy + sc * .25f); path.lineTo(cx + sc * .35f, cy - sc * .25f); c.drawPath(path, line)
            }
            "pulse" -> {
                // A soft grid with a heartbeat trace running across it.
                line.strokeWidth = s * .002f; line.color = col(.22f)
                val g = s * .1f; var gx = (t * s * .05f) % g - g; while (gx < w) { c.drawLine(gx, 0f, gx, h.toFloat(), line); gx += g }
                var gy = 0f; while (gy < h) { c.drawLine(0f, gy, w.toFloat(), gy, line); gy += g }
                path.reset(); val base = h * .5f; val period = w * .5f; val off = (t * w * .22f) % period
                var x = -period + off; path.moveTo(-10f, base)
                while (x < w + period) {
                    path.lineTo(x, base); path.lineTo(x + period * .06f, base - h * .02f); path.lineTo(x + period * .1f, base)
                    path.lineTo(x + period * .14f, base + h * .03f); path.lineTo(x + period * .18f, base - h * .16f); path.lineTo(x + period * .22f, base + h * .06f)
                    path.lineTo(x + period * .27f, base); path.lineTo(x + period * .36f, base - h * .03f); path.lineTo(x + period * .44f, base); x += period
                }
                line.strokeWidth = s * .009f; line.color = col(.6f); c.drawPath(path, line)
            }
        }
    }

    private fun drawGear(c: Canvas, cx: Float, cy: Float, r: Float, teeth: Int, deg: Float, color: Int) {
        path.reset()
        val inner = r * .82f; val step = 360f / teeth
        for (k in 0 until teeth) {
            val a0 = (deg + k * step) * PI.toFloat() / 180f; val a1 = a0 + step * .25f * PI.toFloat() / 180f
            val a2 = a0 + step * .5f * PI.toFloat() / 180f; val a3 = a0 + step * .75f * PI.toFloat() / 180f
            if (k == 0) path.moveTo(cx + cos(a0) * inner, cy + sin(a0) * inner) else path.lineTo(cx + cos(a0) * inner, cy + sin(a0) * inner)
            path.lineTo(cx + cos(a1) * r, cy + sin(a1) * r); path.lineTo(cx + cos(a2) * r, cy + sin(a2) * r); path.lineTo(cx + cos(a3) * inner, cy + sin(a3) * inner)
        }
        path.close()
        line.color = color; line.strokeWidth = r * .05f; c.drawPath(path, line)
        c.drawCircle(cx, cy, r * .42f, line); c.drawCircle(cx, cy, r * .12f, line)
        for (k in 0 until 5) { val a = (deg + k * 72f) * PI.toFloat() / 180f; c.drawLine(cx + cos(a) * r * .12f, cy + sin(a) * r * .12f, cx + cos(a) * r * .42f, cy + sin(a) * r * .42f, line) }
    }

    companion object {
        /** Which picture each app gets. */
        fun themeFor(appName: String): String? = when {
            appName.contains("Abacus", true) -> "beads"
            appName.contains("Timepiece", true) -> "gears"
            appName.contains("Chronicle", true) || appName.contains("Pages", true) || appName.contains("Scribble", true) -> "pages"
            appName.contains("Ripple", true) -> "rings"
            appName.contains("Echo", true) -> "sound"
            appName.contains("Beacon", true) -> "radar"
            appName.contains("Wayfinder", true) -> "compass"
            appName.contains("Polaris", true) -> "stars"
            appName.contains("Memories", true) -> "frames"
            appName.contains("Vault", true) -> "dial"
            appName.contains("Safekeeper", true) -> "shield"
            appName.contains("Pulse", true) || appName.contains("Control Room", true) -> "pulse"
            appName.contains("Packrat", true) || appName.contains("Apphub", true) || appName.contains("App Hub", true) -> "boxes"
            else -> null
        }
    }
}
