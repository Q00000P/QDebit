package org.qdebit.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.qdebit.android.Core
import org.qdebit.android.Core.f0
import org.qdebit.android.Core.f1
import org.qdebit.android.Core.f2
import org.qdebit.android.Measurement
import org.qdebit.android.Pump
import org.qdebit.android.WellProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Ticks(val t: List<Double>, val max: Double)

fun niceTicks(max: Double, n: Int): Ticks {
    val raw = max / n
    val p = 10.0.pow(floor(log10(raw)))
    val m = raw / p
    val step = (if (m <= 1) 1.0 else if (m <= 2) 2.0 else if (m <= 2.5) 2.5 else if (m <= 5) 5.0 else 10.0) * p
    val t = ArrayList<Double>(); var v = 0.0
    while (v <= max + 1e-9) { t += v; v += step }
    val top = if (t.last() < max) t.last() + step else t.last()
    if (top > t.last()) t += top
    return Ticks(t, top)
}

private fun DrawScope.label(tm: TextMeasurer, s: String, x: Float, y: Float, color: Color, align: Int = 0, size: Float = 11f, bold: Boolean = false) {
    val r = tm.measure(s, TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else null))
    val dx = when (align) { 1 -> -r.size.width / 2f; 2 -> -r.size.width.toFloat(); else -> 0f }
    drawText(r, topLeft = Offset(x + dx, y - r.size.height / 2f))
}

/** всплывашка: строки (текст, цвет-маркер?) */
private fun DrawScope.tooltip(tm: TextMeasurer, lines: List<Pair<String, Color?>>, anchorX: Float, top: Float, bg: Color, fg: Color, border: Color) {
    val st = TextStyle(fontSize = 12.sp, color = fg)
    val ms = lines.map { tm.measure(it.first, st) }
    val pad = 8.dp2px(this); val sw = 14.dp2px(this)
    val w = ms.maxOf { it.size.width } + pad * 2 + (if (lines.any { it.second != null }) sw else 0f)
    val h = ms.sumOf { it.size.height } + pad * 2
    var x = if (anchorX > size.width / 2) anchorX - w - 10f else anchorX + 10f
    x = x.coerceIn(0f, max(0f, size.width - w))
    drawRoundRect(bg, Offset(x, top), Size(w, h), CornerRadius(10f, 10f))
    drawRoundRect(border, Offset(x, top), Size(w, h), CornerRadius(10f, 10f), style = Stroke(1f))
    var y = top + pad
    ms.forEachIndexed { i, r ->
        var tx = x + pad
        lines[i].second?.let { c -> drawRoundRect(c, Offset(tx, y + r.size.height / 2f - 2f), Size(10f.dp2px(this), 4f), CornerRadius(2f, 2f)); tx += sw }
        drawText(r, topLeft = Offset(tx, y)); y += r.size.height
    }
}

private fun Float.dp2px(d: DrawScope) = this * d.density
private fun Int.dp2px(d: DrawScope) = this * d.density

/** Q–H: система при нужном давлении + коридор реле, насосы (до 3, цвет закреплён за слотом), предел скважины */
@Composable
fun QHChart(S: WellProfile, slots: List<Pump?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var sel by remember { mutableStateOf<Float?>(null) }
    val cs = MaterialTheme.colorScheme
    val ink = cs.onSurface; val bg = cs.surface; val border = cs.outlineVariant
    val wm = Core.wellModel(S.well)
    val P = S.target.p ?: 0.0; val Pon = S.relay.on ?: 0.0; val Poff = S.relay.off ?: 0.0
    val sel3 = slots.mapIndexedNotNull { i, p -> p?.let { i to it } }
    var qmax0 = listOf((S.target.q ?: 0.0) * 1.4, 2.0).plus(sel3.map { Core.pumpQmax(it.second) }).max()
    if (wm.Qsafe != null && wm.Qsafe < qmax0 * 1.3) qmax0 = max(qmax0, wm.Qsafe * 1.08)
    val xt = niceTicks(qmax0, 5)
    val dummy = Pump(id = "", name = "", type = Core.installOf(S.well))
    fun sys(q: Double, p: Double) = Core.reqH(dummy, q, p, S.sys, S.well, wm)
    val hmax0 = listOf(10.0, sys(min(xt.max, (S.target.q ?: 2.0) * 1.25), P)).plus(sel3.map { Core.availH(it.second, 0.0, S.sys, S.well, wm) ?: 0.0 }).max()
    val yt = niceTicks(hmax0 * 1.06, 5)

    Canvas(
        modifier.fillMaxWidth().aspectRatio(1.35f).pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(requireUnconsumed = false); sel = d.position.x
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    sel = c.position.x
                }
            }
        },
    ) {
        val L = 40.dp2px(this); val R = 10.dp2px(this); val T = 14.dp2px(this); val B = 30.dp2px(this)
        val W = size.width; val H = size.height
        fun X(q: Double) = (L + q / xt.max * (W - L - R)).toFloat()
        fun Y(h: Double) = (T + (1 - h / yt.max) * (H - T - B)).toFloat()
        yt.t.forEach { v -> drawLine(Q.grid, Offset(L, Y(v)), Offset(W - R, Y(v)), 1f); label(tm, f0(v), L - 6.dp2px(this), Y(v), Q.secondary, 2) }
        xt.t.forEach { v -> label(tm, if (v % 1.0 == 0.0) f0(v) else f1(v), X(v), H - B + 12.dp2px(this), Q.secondary, 1) }
        label(tm, "H, м", L, T - 6.dp2px(this), Q.muted)
        label(tm, "Q, м³/ч", W - R, H - 6.dp2px(this), Q.muted, 2)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        S.target.q?.let { tq ->
            drawLine(Q.muted, Offset(X(tq), T), Offset(X(tq), H - B), 1.5f, pathEffect = dash)
            label(tm, "нужно ${f1(tq)}", X(tq) + 4.dp2px(this), T + 6.dp2px(this), Q.muted)
        }
        wm.Qsafe?.let { qs ->
            if (qs < xt.max) {
                drawLine(Q.crit, Offset(X(qs), T), Offset(X(qs), H - B), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                label(tm, "предел скважины ${f2(qs)}", X(qs) + 4.dp2px(this), T + 20.dp2px(this), Q.crit)
            } else label(tm, "предел скважины ${f2(qs)} → правее", W - R, T + 20.dp2px(this), Q.crit, 2)
        }
        fun curve(f: (Double) -> Double?): Path {
            val p = Path(); var started = false
            for (i in 0..100) {
                val q = xt.max * i / 100; val h = f(q) ?: continue
                if (!started) { p.moveTo(X(q), Y(h)); started = true } else p.lineTo(X(q), Y(h))
            }
            return p
        }
        clipRect(L, T, W - R, H - B) {
            drawPath(curve { sys(it, Pon) }, Q.muted, style = Stroke(1.5f))
            drawPath(curve { sys(it, Poff) }, Q.muted, style = Stroke(1.5f))
            drawPath(curve { sys(it, P) }, ink, style = Stroke(2.dp2px(this)))
            sel3.forEach { (slot, p) ->
                drawPath(curve { q -> if (q <= Core.pumpQmax(p)) Core.availH(p, q, S.sys, S.well, wm) else null }, Q.series[slot], style = Stroke(2.dp2px(this), join = StrokeJoin.Round))
            }
            sel3.forEach { (slot, p) ->
                Core.flowAtPressure(p, P, S.sys, S.well, wm)?.takeIf { it > 0 }?.let { q ->
                    drawCircle(bg, 7.dp2px(this), Offset(X(q), Y(sys(q, P))))
                    drawCircle(Q.series[slot], 5.dp2px(this), Offset(X(q), Y(sys(q, P))))
                }
            }
        }
        sel?.let { sx ->
            val q = ((sx - L) / (W - L - R) * xt.max).coerceIn(0.0, xt.max)
            drawLine(Q.muted, Offset(X(q), T), Offset(X(q), H - B), 1f)
            val lines = ArrayList<Pair<String, Color?>>()
            lines += "${f2(q)} м³/ч · ${f0(q * 1000 / 60)} л/мин" to null
            lines += "система: ${f1(sys(q, P))} м" to ink
            sel3.forEach { (slot, p) ->
                val ph = Core.availH(p, q, S.sys, S.well, wm); val pr = Core.pressureAtFlow(p, q, S.sys, S.well, wm)
                lines += "${p.name.removePrefix("UNIPUMP ")}: ${ph?.let { f0(it) + " м" } ?: "—"}${pr?.let { " → ${f2(it)} бар" } ?: ""}" to Q.series[slot]
            }
            if (wm.ok) lines += "уровень ${f1(Core.levelAt(wm, S.well, q))} м" to null
            tooltip(tm, lines, X(q), T + 30.dp2px(this), bg, ink, border)
        }
    }
}

@Composable
fun HistoryChart(meas: List<Measurement>, targetQ: Double?, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var sel by remember { mutableStateOf<Float?>(null) }
    val cs = MaterialTheme.colorScheme
    val pts = meas.filter { it.q > 0 }.sortedBy { it.ts }
    if (pts.size < 2) { Small(if (pts.isEmpty()) "Замеров ещё нет." else "Нужно хотя бы два замера для графика."); return }
    val t0 = pts.first().ts; val span = max(pts.last().ts - t0, 1L)
    val yt = niceTicks(max(pts.maxOf { it.q }, targetQ ?: 0.0) * 1.12, 4)
    val short = span < 36L * 3600_000
    val fmtX = SimpleDateFormat(if (short) "HH:mm" else "dd.MM", Locale.forLanguageTag("ru"))
    val fmtTip = SimpleDateFormat("dd.MM.yy HH:mm", Locale.forLanguageTag("ru"))
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1.9f).pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(requireUnconsumed = false); sel = d.position.x
                while (true) { val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break; if (!c.pressed) break; sel = c.position.x }
            }
        },
    ) {
        val L = 40.dp2px(this); val R = 10.dp2px(this); val T = 14.dp2px(this); val B = 26.dp2px(this)
        val W = size.width; val H = size.height
        fun X(ts: Long) = L + (ts - t0).toFloat() / span * (W - L - R)
        fun Y(q: Double) = (T + (1 - q / yt.max) * (H - T - B)).toFloat()
        yt.t.forEach { v -> drawLine(Q.grid, Offset(L, Y(v)), Offset(W - R, Y(v)), 1f); label(tm, f1(v), L - 6.dp2px(this), Y(v), Q.secondary, 2) }
        label(tm, "м³/ч", L, T - 6.dp2px(this), Q.muted)
        val n = min(5, pts.size)
        for (i in 0 until n) {
            val ts = t0 + span * i / max(n - 1, 1)
            label(tm, fmtX.format(Date(ts)), X(ts), H - 8.dp2px(this), Q.secondary, if (i == 0) 0 else if (i == n - 1) 2 else 1)
        }
        targetQ?.let { tq ->
            drawLine(Q.muted, Offset(L, Y(tq)), Offset(W - R, Y(tq)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            label(tm, "нужно ${f1(tq)}", W - R, Y(tq) - 8.dp2px(this), Q.muted, 2)
        }
        val p = Path(); pts.forEachIndexed { i, m -> if (i == 0) p.moveTo(X(m.ts), Y(m.q)) else p.lineTo(X(m.ts), Y(m.q)) }
        drawPath(p, Q.series[0], style = Stroke(2.dp2px(this), join = StrokeJoin.Round))
        pts.forEach { m -> drawCircle(cs.surface, 6.dp2px(this), Offset(X(m.ts), Y(m.q))); drawCircle(Q.series[0], 4.dp2px(this), Offset(X(m.ts), Y(m.q))) }
        sel?.let { sx ->
            val best = pts.minBy { kotlin.math.abs(X(it.ts) - sx) }
            drawLine(Q.muted, Offset(X(best.ts), T), Offset(X(best.ts), H - B), 1f)
            val lines = ArrayList<Pair<String, Color?>>()
            lines += fmtTip.format(Date(best.ts)) to null
            lines += "${f2(best.q)} м³/ч · ${f1(best.q * 1000 / 60)} л/мин" to Q.series[0]
            best.dynLevel?.let { lines += "дин. ${f1(it)} м" to null }
            best.note?.takeIf { it.isNotBlank() }?.let { lines += it.take(40) to null }
            tooltip(tm, lines, X(best.ts), T, cs.surface, cs.onSurface, cs.outlineVariant)
        }
    }
}
