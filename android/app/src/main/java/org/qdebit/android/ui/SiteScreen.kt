package org.qdebit.android.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qdebit.android.AppState
import org.qdebit.android.Core
import org.qdebit.android.Core.f0
import org.qdebit.android.Core.f1
import org.qdebit.android.Core.f2
import org.qdebit.android.Core.fx
import org.qdebit.android.WellProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** «Участок»: план всех скважин, отметки и водоносные, попарные перепады, профиль-разрез. */
@Composable
fun SiteScreen(app: AppState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        MapCard(app, siteMode = true)
        ZCard(app)
        PairsCard(app)
        ProfileCard(app)
    }
}

private fun sgn(x: Double?, d: Int = 2): String = when {
    x == null -> "—"
    x > 0 -> "+" + fx(x, d)
    x < 0 -> "−" + fx(abs(x), d)
    else -> "±" + fx(0.0, d)
}

@Composable
private fun ZCard(app: AppState) {
    val ws = app.liveWells
    QCard("Скважины: отметки и водоносные") {
        ws.forEach { wp ->
            val z = Core.zInfo(wp)
            val last = app.measLive.firstOrNull { it.wellId == wp.id && it.q > 0 }
            val kind = when (wp.well.kind) { "abyssinian" -> "абиссинка"; "dug" -> "колодец"; else -> "обсадная" }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Q.surface2, RoundedCornerShape(10.dp)).padding(10.dp),
            ) {
                Text(wp.name, fontWeight = FontWeight.SemiBold)
                Small(listOfNotNull(kind, z.aquifer?.let { Core.AQ_NAMES[it] }, z.horizon?.let { "$it-й горизонт" }, last?.let { "${f2(it.q)} м³/ч" }).joinToString(" · "))
                Small(listOf(
                    "устье " + (z.Z?.let { "${Core.fmtZ(it, z.src)} м абс. (${z.src})" } ?: "—"),
                    "статика ${f1(z.static)} м → зеркало ${Core.fmtZ(z.Zw, z.src)} м абс.",
                    "кровля ${f1(z.aqTop)} м → ${Core.fmtZ(z.Ztop, z.src)} м абс.",
                ).joinToString(" · "), color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (ws.any { Core.groundZ(it.well) == null })
            Note(NoteKind.INFO, "Без отметки устья абсолютные значения не считаются. Отметка по рельефу подтягивается сама по координатам скважины (±2–5 м); для перепадов между скважинами одного участка точнее своя — нивелир/лазер/гидроуровень, вписывается на «Скважине».")
        if (app.elevStatus.isNotEmpty()) Small(app.elevStatus, color = Q.muted)
        Small("Здесь только просмотр — всё задаётся на вкладке «Скважина».", color = Q.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PairsCard(app: AppState) {
    val pr = Core.wellPairs(app.liveWells)
    QCard("Сравнение скважин") {
        if (pr.isEmpty()) { Small("Нужно минимум две скважины."); return@QCard }
        pr.forEach { p ->
            val (rows, flow) = Core.pairRows(p)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Q.surface2, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.a.name} ↔ ${p.b.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(Core.fmtDist(p.dist), color = Q.secondary)
                }
                QTable(listOf("", p.a.name, p.b.name, "разница"), rows, listOf(1.3f, 0.9f, 0.9f, 1.4f))
                Small(flow, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(app: AppState) {
    val ws = app.liveWells
    val sel = app.s.profile.mapNotNull { id -> app.wellById(id) }
    QCard("Профиль-разрез") {
        Small("Отмечай скважины по порядку линии разреза (тап ещё раз — убрать).", color = Q.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            ws.forEach { w ->
                val k = sel.indexOfFirst { it.id == w.id }
                Chip(if (k >= 0) "${k + 1}. ${w.name}" else w.name, k >= 0) {
                    app.updatePrefs { s -> s.copy(profile = if (w.id in s.profile) s.profile - w.id else s.profile.filter { app.wellById(it) != null } + w.id) }
                }
            }
            if (sel.isNotEmpty()) Chip("сброс", false) { app.updatePrefs { it.copy(profile = emptyList()) } }
        }
        if (sel.size < 2) Small("Выбери две и больше скважины.")
        else ProfileChart(sel)
    }
}

private val AQ_COLOR = mapOf("sand" to Color(0xFFE0B84A), "limestone" to Color(0xFF78909C), "" to Color(0xFF9E9E9E))
private val WATER = Color(0xFF1E88E5)
private val GROUND = Color(0xFF8D6E63)

@Composable
private fun ProfileChart(sel: List<WellProfile>) {
    val zs = sel.map { Core.zInfo(it) }
    val notes = ArrayList<String>()
    // по горизонтали — расстояния по координатам; без координат — шаг 30 м
    val xs = ArrayList<Double>().apply { add(0.0) }
    for (i in 1 until zs.size) {
        val a = zs[i - 1]; val b = zs[i]
        val d = if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) Core.distM(a.lat, a.lon, b.lat, b.lon) else null
        if (d == null) notes += "${a.name} – ${b.name}: нет координат, условно 30 м"
        xs.add(xs[i - 1] + (d ?: 30.0))
    }
    val known = zs.mapNotNull { it.Z }
    val baseZ = if (known.isNotEmpty()) known.average() else 0.0
    val noZ = zs.filter { it.Z == null }
    if (noZ.isNotEmpty()) notes += "Нет отметки устья: ${noZ.joinToString { it.name }} — поставлено на " + (if (known.isNotEmpty()) "среднюю ${f1(baseZ)} м" else "0 (относительно)")
    val Z = zs.map { it.Z ?: baseZ }
    fun bottomOf(i: Int): Double {
        val z = zs[i]; val lb = sel[i].well.layers.maxOfOrNull { it.to ?: 0.0 } ?: 0.0
        return maxOf(z.depth ?: 0.0, lb, (z.aqTop ?: 0.0) + 5, (z.static ?: 0.0) + 5, 10.0)
    }
    val bots = zs.indices.map { Z[it] - bottomOf(it) }
    val top = Z.max() + 2; val bot = bots.min() - 2
    val L = xs.last().takeIf { it > 0 } ?: 1.0
    val dens = LocalDensity.current.density
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * dens; color = android.graphics.Color.GRAY }
    val txtB = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dens; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    val txtW = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * dens; color = WATER.toArgb() }
    val onSurf = MaterialTheme.colorScheme.onSurface
    txtB.color = onSurf.toArgb()
    val gridC = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(340.dp).background(Q.surface2, RoundedCornerShape(12.dp))) {
        val pl = 44 * dens; val pr = 16 * dens; val pt = 26 * dens; val pb = 22 * dens; val ix = 30 * dens
        fun X(x: Double) = (pl + ix + x / L * (size.width - pl - pr - 2 * ix)).toFloat()
        fun Y(z: Double) = (pt + (top - z) / (top - bot) * (size.height - pt - pb)).toFloat()
        // шкала отметок
        val span = top - bot
        val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0).firstOrNull { span / it <= 7 } ?: 100.0
        var v = ceil(bot / step) * step
        while (v <= top) {
            drawLine(gridC, Offset(pl, Y(v)), Offset(size.width - pr, Y(v)), 1f)
            drawContext.canvas.nativeCanvas.drawText(fx(v, 0), 4 * dens, Y(v) + 4 * dens, txt)
            v += step
        }
        drawContext.canvas.nativeCanvas.drawText("м абс.", 4 * dens, 12 * dens, txt)
        // рельеф
        val gp = Path().apply { zs.indices.forEach { i -> if (i == 0) moveTo(X(xs[i]), Y(Z[i])) else lineTo(X(xs[i]), Y(Z[i])) } }
        drawPath(gp, GROUND, style = Stroke(width = 2.5f * dens))
        // кровли и зеркала — отдельно по каждому водоносному
        for (aq in listOf("sand", "limestone", "")) {
            val idx = zs.indices.filter { (zs[it].aquifer ?: "") == aq }
            val tops = idx.filter { zs[it].aqTop != null }
            if (tops.size >= 2) drawPath(Path().apply { tops.forEachIndexed { k, i -> val p = Offset(X(xs[i]), Y(Z[i] - zs[i].aqTop!!)); if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
                AQ_COLOR[aq]!!, style = Stroke(width = 2f * dens, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * dens, 4 * dens))))
            val wl = idx.filter { zs[it].static != null }
            if (wl.size >= 2) drawPath(Path().apply { wl.forEachIndexed { k, i -> val p = Offset(X(xs[i]), Y(Z[i] - zs[i].static!!)); if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
                WATER, style = Stroke(width = 2f * dens, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dens, 3 * dens))))
        }
        // скважины: слои, ствол, кровля, зеркало, подписи
        zs.forEachIndexed { i, z ->
            val x = X(xs[i]); val z0 = Z[i]; val ls = sel[i].well.layers.filter { it.to != null }
            ls.forEach { l ->
                val f = l.from ?: 0.0; val t = l.to!!
                val y1 = Y(z0 - f); val y2 = Y(z0 - t)
                drawRect(Color(Core.rock(l.rock).color), Offset(x - 7 * dens, y1), Size(14 * dens, max(1f, y2 - y1)))
                if (l.water) drawRect(WATER, Offset(x - 7 * dens, y1), Size(14 * dens, max(1f, y2 - y1)), style = Stroke(1.5f * dens))
            }
            val dep = z.depth ?: bottomOf(i)
            drawLine(onSurf, Offset(x, Y(z0)), Offset(x, Y(z0 - dep)), (if (ls.isEmpty()) 2f else 1f) * dens)
            z.aqTop?.let { t -> drawLine(AQ_COLOR[z.aquifer ?: ""]!!, Offset(x - 11 * dens, Y(z0 - t)), Offset(x + 11 * dens, Y(z0 - t)), 3f * dens) }
            z.static?.let { s0 ->
                val yy = Y(z0 - s0)
                drawPath(Path().apply { moveTo(x - 6 * dens, yy - 8 * dens); lineTo(x + 6 * dens, yy - 8 * dens); lineTo(x, yy); close() }, WATER)
                drawContext.canvas.nativeCanvas.drawText(Core.fmtZ(z0 - s0, z.src), x + 9 * dens, yy + 4 * dens, txtW)
            }
            drawContext.canvas.nativeCanvas.drawText(z.name, x, Y(z0) - 8 * dens, txtB)
            drawContext.canvas.nativeCanvas.drawText(Core.fmtDist(xs[i]), min(max(x - 12 * dens, 0f), size.width - 50 * dens), size.height - 6 * dens, txt)
        }
    }
    Small("— рельеф (коричневый) · ▼ статический уровень, м абс. (синий) · кровля песка (жёлтая) / известняка (серая) · колонка — слои из журнала бурения. Масштабы по осям разные.",
        color = Q.muted, modifier = Modifier.padding(top = 6.dp))
    notes.forEach { Note(NoteKind.WARN, it) }
}
