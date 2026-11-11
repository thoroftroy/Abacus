package dev.wonderdesk.abacus

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** A working calculator on the home screen: tap keys, see the answer as you type, = keeps it. */
class CalcWidget : AppWidgetProvider() {
    companion object {
        private const val KEY = "dev.wonderdesk.abacus.WIDGET_KEY"
        private val KEYS = mapOf(
            R.id.k_c to "C", R.id.k_bs to "BS", R.id.k_lp to "(", R.id.k_rp to ")", R.id.k_div to "/", R.id.k_7 to "7", R.id.k_8 to "8", R.id.k_9 to "9", R.id.k_mul to "*",
            R.id.k_pct to "%", R.id.k_4 to "4", R.id.k_5 to "5", R.id.k_6 to "6", R.id.k_sub to "-", R.id.k_pow to "^", R.id.k_1 to "1", R.id.k_2 to "2", R.id.k_3 to "3",
            R.id.k_add to "+", R.id.k_sqrt to "√(", R.id.k_0 to "0", R.id.k_dot to ".", R.id.k_pi to "π", R.id.k_ans to "ans", R.id.k_eq to "=",
        )
        private fun prefs(c: Context) = c.getSharedPreferences("calc-widget", Context.MODE_PRIVATE)

        fun build(c: Context, id: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_calc)
            val p = prefs(c)
            val expr = p.getString("e$id", "") ?: ""
            val done = p.getBoolean("d$id", false)
            val preview = if (expr.isBlank()) null else runCatching { fmt(Evaluator(false, mapOf("ans" to p.getFloat("ans", 0f).toDouble())).eval(MathParser.expression(Smart.normalize(expr)))) }.getOrNull()
            v.setTextViewText(R.id.w_expr, expr.replace("*", "×").replace("/", "÷").replace("-", "−") + if (done) " =" else "")
            v.setTextViewText(R.id.w_result, preview ?: if (expr.isBlank()) "0" else "…")
            KEYS.forEach { (vid, k) ->
                val i = Intent(c, CalcWidget::class.java).setAction(KEY).putExtra("k", k).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                v.setOnClickPendingIntent(vid, PendingIntent.getBroadcast(c, id * 100 + k.hashCode() % 97 + 50, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            v.setOnClickPendingIntent(R.id.w_expr, PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = ids.forEach { mgr.updateAppWidget(it, build(c, it)) }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action != KEY) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        val k = intent.getStringExtra("k") ?: return
        val p = prefs(c)
        var e = p.getString("e$id", "") ?: ""
        var done = p.getBoolean("d$id", false)
        when (k) {
            "C" -> { e = ""; done = false }
            "BS" -> { if (done) done = false else e = e.dropLast(if (e.endsWith("√(") || e.endsWith("ans")) (if (e.endsWith("ans")) 3 else 2) else 1) }
            "=" -> runCatching { Evaluator(false, mapOf("ans" to p.getFloat("ans", 0f).toDouble())).eval(MathParser.expression(Smart.normalize(e))) }.onSuccess { r ->
                p.edit().putFloat("ans", r.toFloat()).apply(); done = true
            }
            else -> {
                if (done) { e = if (k in setOf("+", "-", "*", "/", "^", "%")) "ans" else ""; done = false }
                if (e.length < 80) e += k
            }
        }
        if (done && k == "=") { /* keep the expression visible with its answer */ }
        p.edit().putString("e$id", e).putBoolean("d$id", done).apply()
        AppWidgetManager.getInstance(c).updateAppWidget(id, build(c, id))
    }
}
