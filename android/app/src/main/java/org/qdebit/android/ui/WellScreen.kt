package org.qdebit.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.qdebit.android.AppState
import org.qdebit.android.Core
import org.qdebit.android.Core.f0
import org.qdebit.android.Core.f1
import org.qdebit.android.Core.f2
import org.qdebit.android.Core.fx
import org.qdebit.android.Well
import java.util.Date

@Composable
fun WellScreen(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var delArm by remember { mutableStateOf(false) }
    val WP = app.W; val w = WP.well
    fun up(f: (Well) -> Well) = app.updateWell { it.copy(well = f(it.well)) }
    val ref = app.measLive.firstOrNull { it.id == WP.refId }
    val wm = Core.wellModel(w)
    val tq = WP.target.q
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        // ---- база
        QCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("База скважин", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.addWell(); Toast.makeText(ctx, "Новая скважина — задай название и параметры", Toast.LENGTH_SHORT).show() }) { Text("+ новая") }
                TextButton(onClick = { app.addWell(dupOf = WP); Toast.makeText(ctx, "Копия создана — замеры не копируются", Toast.LENGTH_SHORT).show() }) { Text("копия") }
                TextButton(enabled = app.liveWells.size > 1, onClick = {
                    if (!delArm) { delArm = true; scope.launch { delay(3000); delArm = false }; return@TextButton }
                    delArm = false
                    val name = WP.name; val n = app.deleteWell(WP.id)
                    Toast.makeText(ctx, "Удалена «$name»" + if (n > 0) "; $n замеров остались без привязки" else "", Toast.LENGTH_LONG).show()
                }) { Text(if (delArm) "точно?" else "удалить", color = Q.crit) }
            }
            app.liveWells.forEach { x ->
                val act = x.id == WP.id
                val ms = app.measLive.filter { it.wellId == x.id && it.q > 0 }
                val xm = Core.wellModel(x.well)
                val facts = listOfNotNull(
                    x.well.static?.let { "статика ${f1(it)} м" }, x.well.dyn?.let { "динамика ${f1(it)} м" },
                    x.well.pumpDepth?.let { "насос на ${f0(it)} м" }, if (xm.ok) "безопасно ${f2(xm.Qsafe)} м³/ч" else null,
                ).joinToString(" · ")
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(if (act) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Q.surface2, RoundedCornerShape(10.dp))
                        .clickable { app.setActiveWell(x.id) }.padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(x.name, fontWeight = FontWeight.SemiBold)
                        if (act) { Spacer(Modifier.width(8.dp)); Badge(NoteKind.OK, "активная") }
                    }
                    Small(facts.ifEmpty { "параметры не заданы" }, selectable = false)
                    Small((if (ms.isEmpty()) "замеров нет" else "замеров: ${ms.size} · последний ${f2(ms.first().q)} м³/ч, ${DATE_FMT.format(Date(ms.first().ts))}") +
                        (if (x.note.isNotBlank()) " · ${x.note}" else ""), color = Q.muted, selectable = false)
                }
            }
        }
        // ---- параметры активной
        QCard("Параметры: ${WP.name}") {
            NameNoteFields(app)
            val inst = Core.installOf(w)
            val ab = Core.isAbyss(w)
            FieldGrid(
                { m -> Select("Тип скважины", listOf("borehole" to "обсадная скважина", "abyssinian" to "абиссинская (игла)", "dug" to "колодец"), w.kind, { v ->
                    val fresh = v == "abyssinian" && w.install != "surface"
                    app.updateWell { x ->
                        if (v == "abyssinian" && x.well.install != "surface")
                            x.copy(well = x.well.copy(kind = v, install = "surface", casingID = null, minAbove = null, riserOD = null, pumpDepth = null, dyn = null, dynQ = null,
                                    depth = x.well.depth?.takeIf { it <= 30 } ?: 10.0, static = x.well.static?.takeIf { it <= 8 } ?: 4.0),
                                sys = x.sys.copy(riserPipe = "pe32-16", riserLen = null), relay = org.qdebit.android.Relay(1.5, 3.0), target = org.qdebit.android.Target(1.5, 2.0), fmt = 3)
                        else x.copy(well = x.well.copy(kind = v, install = if (v == "abyssinian") "surface" else x.well.install), fmt = 3)
                    }
                    if (fresh) Toast.makeText(ctx, "Абиссинка: игла ПНД 32×3 (внутр. 26), 10 м, статика 4 м, реле 1,5–3,0 бар, потребность 1,5 м³/ч при 2 бар — поправь под себя", Toast.LENGTH_LONG).show()
                }, m) },
                { m -> if (ab) NumField("Насос", null, {}, m, placeholder = "поверхностный (сосёт)", enabled = false)
                       else Select("Насос", Core.INSTALL_NAMES.entries.map { it.key to it.value }, inst, { v -> up { it.copy(install = v) } }, m) },
            )
            PumpPickerField("Стоит насос (его режим — ниже)", app.allPumps(), WP.pumpId?.takeIf { app.pumpById(it) != null }, { p ->
                val id = p?.id
                app.updateWell { x -> x.copy(pumpId = id, chart = if (id != null && id !in x.chart && x.chart.indexOf(null) >= 0) x.chart.toMutableList().also { it[x.chart.indexOf(null)] = id } else x.chart) }
            }, Modifier.fillMaxWidth().padding(vertical = 4.dp), preferType = inst)
            if (inst == "sub") NumField("Уставка пост. давления, бар", w.cpSet, { v -> up { it.copy(cpSet = v) } }, Modifier.fillMaxWidth(), hint = "SQE / частотник; пусто — обычное реле")
            Small("Система (трубы, давление, реле, насосы на графике) — на вкладке «Насос», тоже своя у каждой скважины.", color = Q.muted, modifier = Modifier.padding(vertical = 6.dp))
            fun sys(f: (org.qdebit.android.Sys) -> org.qdebit.android.Sys) = app.updateWell { it.copy(sys = f(it.sys)) }
            if (ab) {
                // абиссинка: насос сверху тянет со статики; динамики, глубины насоса и колонны нет
                FieldGrid(
                    { m -> NumField("Статика, м", w.static, { v -> up { it.copy(static = v) } }, m, hint = "насос всегда поднимает с этого уровня") },
                    { m -> NumField("Глубина иглы, м", w.depth, { v -> up { it.copy(depth = v) } }, m, hint = "для справки, в расчёт не идёт") },
                    { m -> Select("Труба иглы", pipeOptions(), WP.sys.riserPipe, { v -> sys { it.copy(riserPipe = v) } }, m) },
                    { m -> if (WP.sys.riserPipe == "custom") NumField("Внутр. Ø иглы, мм", WP.sys.riserID, { v -> sys { it.copy(riserID = v) } }, m)
                           else Small("внутр. ${f1(Core.pipeById(WP.sys.riserPipe)?.idmm)} мм — она же всасывающая", modifier = m.padding(top = 18.dp)) },
                    { m -> NumField("Насос над землёй, м", w.pumpElev, { v -> up { it.copy(pumpElev = v) } }, m, hint = "в кессоне — минус") },
                    { m -> NumField("Высота над морем, м", w.alt, { v -> up { it.copy(alt = v) } }, m, hint = "по GPS с «Где я»") },
                    { m -> NumField("Трасса до насоса, м", WP.sys.suctLen, { v -> sys { it.copy(suctLen = v) } }, m, hint = "от иглы до входа насоса; 0 — на игле") },
                    { m -> Select("Труба трассы до насоса", listOf("" to "как игла") + pipeOptions(), WP.sys.suctPipe ?: "", { v -> sys { it.copy(suctPipe = v.ifBlank { null }) } }, m) },
                )
            } else {
                FieldGrid(
                    { m -> NumField("Статика, м", w.static, { v -> up { it.copy(static = v) } }, m, hint = "до воды без откачки") },
                    { m -> NumField("Динамика, м", w.dyn, { v -> up { it.copy(dyn = v) } }, m, hint = "при откачке") },
                    { m -> NumField("…при расходе, м³/ч", w.dynQ, { v -> app.updateWell { it.copy(refId = null, well = it.well.copy(dynQ = v)) } }, m, hint = ref?.let { "опорный замер ${DATE_FMT.format(Date(it.ts))}" } ?: "с каким дебитом мерили") },
                    { m -> NumField(when (inst) { "surface" -> "Глубина забора, м"; "ejector" -> "Глубина эжектора, м"; else -> "Глубина насоса, м" }, w.pumpDepth, { v -> up { it.copy(pumpDepth = v) } }, m,
                        hint = when (inst) { "surface" -> "низ всасывающей трубы"; "ejector" -> "где висит эжектор"; else -> null }) },
                    { m -> NumField("Запас над насосом, м", w.minAbove, { v -> up { it.copy(minAbove = v) } }, m, hint = "запас от сухого хода, м") },
                    { m -> NumField("Глубина скважины, м", w.depth, { v -> up { it.copy(depth = v) } }, m) },
                    { m -> NumField("Внутр. Ø колонны, мм", w.casingID, { v -> up { it.copy(casingID = v) } }, m, hint = "125×7,5 → 110") },
                    { m -> NumField("Наруж. Ø труб в колонне", w.riserOD, { v -> up { it.copy(riserOD = v) } }, m, hint = "мм, вычитается из запаса") },
                    { m -> if (inst == "sub") NumField("Мин. зазор насоса, мм", w.minGap, { v -> up { it.copy(minGap = v) } }, m, hint = "на сторону")
                           else NumField("Насос над землёй, м", w.pumpElev, { v -> up { it.copy(pumpElev = v) } }, m, hint = "в кессоне — минус") },
                    { m -> if (inst == "surface") NumField("Высота над морем, м", w.alt, { v -> up { it.copy(alt = v) } }, m, hint = "по GPS с «Где я»") else Spacer(m) },
                )
                if (inst == "surface") FieldGrid(
                    { m -> NumField("Трасса до насоса, м", WP.sys.suctLen, { v -> sys { it.copy(suctLen = v) } }, m, hint = "всасывающая от оголовка до насоса") },
                    { m -> Select("Труба трассы до насоса", listOf("" to "как вертикаль") + pipeOptions(), WP.sys.suctPipe ?: "", { v -> sys { it.copy(suctPipe = v.ifBlank { null }) } }, m) },
                )
                OutlinedButton(enabled = app.measLive.isNotEmpty(), modifier = Modifier.padding(top = 6.dp), onClick = {
                    val m = ref ?: app.measLive.firstOrNull { it.wellId == WP.id } ?: app.measLive.first()
                    app.updateWell { it.copy(refId = m.id, well = it.well.copy(dynQ = Math.round(m.q * 1000) / 1000.0, dyn = m.dynLevel ?: it.well.dyn)) }
                    Toast.makeText(ctx, "Расход ${f2(m.q)} м³/ч из замера ${DATE_FMT.format(Date(m.ts))}", Toast.LENGTH_SHORT).show()
                }) { Text("Взять расход из опорного замера") }
            }
            if (WP.legacy != null && ab) Small("Переведена в формат 1.5: прежние значения (динамика, глубина забора, Ø колонны, длина всасывающей) сохранены и в экспорте JSON.", color = Q.muted, modifier = Modifier.padding(top = 4.dp))
        }
        GeoCard(app)
        RegimeCard(app)
        MapCard(app)
        if (Core.isAbyss(w)) AbyssModelCard(app) else QCard("Модель: ${WP.name}") {
            if (wm.badDepth) Note(NoteKind.CRIT, "Насос выше статики с учётом запаса — проверь глубину насоса и мин. столб.")
            val pp = Core.wellPoints(WP, app.measLive) { app.pumpById(it) }
            if (!wm.ok) {
                Note(NoteKind.INFO, "Для модели нужны статика и хотя бы один замер расхода с уровнем: динамика с уровнемера или давление на манометре (для погружного с выбранным насосом). Либо «динамика при расходе» в форме выше.")
            } else {
                KV(listOf(
                    f2(wm.q) to "удельный дебит при ${f2(wm.testQ)} м³/ч, м³/ч на 1 м",
                    f1(wm.sAt(wm.testQ)) to "понижение при ${f2(wm.testQ)} м³/ч, м",
                    f2(wm.Qsafe) to "безопасный дебит, м³/ч" + if (wm.capped) " (ограничен)" else "",
                    f1(wm.limitLevel) to "предельный уровень, м",
                ))
                Small(if (wm.kind == "step")
                    "Понижение s = ${fx(wm.B, 3)}·Q + ${fx(wm.C, 3)}·Q² по ${wm.n} замерам: первая часть — пласт (линейно), вторая — вход в скважину (растёт квадратично). При ${f2(wm.testQ)} м³/ч вход съедает ${f0(wm.C * wm.testQ / (wm.B + wm.C * wm.testQ) * 100)}% понижения."
                    else "Прямая по одному расходу (${f2(wm.testQ)} м³/ч): на больших расходах понижение растёт быстрее — поэтому безопасный дебит ограничен 1,5× от замера. Для честной кривой — 2–3 замера на разных расходах.",
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
                if (wm.capped) Note(NoteKind.WARN, "По модели вышло бы ${f2(wm.QsafeRaw)} м³/ч, но это экстраполяция далеко за замеры — взято ${f2(wm.Qvalid)} (${if (wm.kind == "step") "2" else "1,5"}× от ${f2(wm.testQ)}).")
                if (tq != null && tq > 0) {
                    val lv = Core.levelAt(wm, w, tq)
                    val over = wm.Qsafe != null && tq > wm.Qsafe
                    Note(if (over) NoteKind.CRIT else NoteKind.OK, "При ${f1(tq)} м³/ч уровень ≈ ${f1(lv)} м" + if (over) " — ниже допустимого (${f1(wm.limitLevel)} м) или за пределом проверенного" else ", над насосом ${f1((w.pumpDepth ?: 0.0) - lv)} м")
                    if (over && (wm.QsafeRaw ?: 0.0) > tq) Note(NoteKind.WARN, "${f1(tq)} м³/ч дальше проверенных замерами расходов — уровень посчитан экстраполяцией.")
                    else if (over) Note(NoteKind.WARN, "Запас в колонне уйдёт за ${Core.fmtHours(Core.drainHours(wm, tq))} непрерывной работы на ${f1(tq)} м³/ч")
                }
            }
            if (pp.first.isNotEmpty() || pp.second.isNotEmpty()) {
                H3("Замеры для модели")
                QTable(listOf("дата", "м³/ч", "динамика", "понижение", "откуда"),
                    pp.first.sortedBy { it.q }.map { p -> listOf(p.ts?.let { DATE_FMT.format(Date(it)) } ?: "—", f2(p.q), f1(p.dyn), f1(p.s), p.src) },
                    listOf(1.1f, 0.7f, 0.8f, 0.8f, 1.5f))
                pp.second.forEach { r -> Note(NoteKind.WARN, "Замер ${DATE_FMT.format(Date(r.m.ts))} (${f2(r.m.q)} м³/ч) не взят: ${r.why}") }
            }
            if (Core.installOf(w) == "sub") {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Как снять кривую скважины, не вынимая насос") }
                if (open) Small("Прикрывая кран на изливе, сделай 3–4 ступени расхода (полностью открыт → наполовину → почти закрыт). На каждой подожди 20–30 мин, сними расход ведром и давление на манометре — впиши его в замер («Давление на манометре» и где он стоит). Динамика посчитается по кривой выбранного насоса.\n" +
                    "SQE с CU 301 держит постоянное давление, снижая обороты, — тогда кривая неизвестна. Годятся только ступени, где давление ниже уставки CU 301 (насос на полных оборотах): открой кран(ы) шире. Уставку впиши в «Уставка пост. давления» — точки на уставке отбросятся сами. Либо переведи насос на постоянную кривую через Grundfos GO.\n" +
                    "Точность ±3–5 м по уровню (кривая насоса, износ, напряжение) — для понижения в десятки метров хватает.", color = MaterialTheme.colorScheme.onSurface)
            }
            wm.reserveL?.let { res ->
                H3("Запас в колонне")
                Small("От статики ${f1(w.static)} м до предельного ${f1(wm.limitLevel)} м: ${f0(res)} л (${f1((wm.area ?: 0.0) * 1000)} л на метр). Это то, что насос выберет сверх притока, прежде чем дойти до запаса.", color = MaterialTheme.colorScheme.onSurface)
                if (w.depth != null && w.static != null && w.pumpDepth != null)
                    Small("Столб воды в скважине ${f1(w.depth - w.static)} м, под насосом ${f1(w.depth - w.pumpDepth)} м.")
            }
            if (wm.ok && wm.reserveL != null && wm.Qsafe != null) {
                val qs = listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0).filter { it > wm.Qsafe }
                if (qs.isNotEmpty()) {
                    H3("Время до предела при переборе")
                    QTable(listOf("подача, м³/ч", "до предела"), qs.map { q -> listOf(f0(q), Core.fmtHours(Core.drainHours(wm, q)) + if (q > (wm.Qvalid ?: Double.MAX_VALUE)) " (вне замеров)" else "") })
                }
            }
        }
    }
}

/** название/заметка: свой текст, сохраняем на каждый ввод; при смене скважины поля перезаполняются */
@Composable
private fun NameNoteFields(app: AppState) {
    val WP = app.W
    var name by remember(WP.id) { mutableStateOf(WP.name) }
    var note by remember(WP.id) { mutableStateOf(WP.note) }
    LaunchedEffect(WP.name) { if (WP.name != name) name = WP.name }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextFieldQ("Название", name, { t -> name = t; if (t.isNotBlank()) app.updateWell { it.copy(name = t) } }, Modifier.weight(1f), placeholder = "Дача, у бани…")
        TextFieldQ("Заметка", note, { t -> note = t; app.updateWell { it.copy(note = t) } }, Modifier.weight(1f), placeholder = "бурили 2026, глина")
    }
}

@Composable
private fun RegimeCard(app: AppState) {
    val WP = app.W
    val p = WP.pumpId?.let { app.pumpById(it) }
    QCard(if (p == null) "Режим насоса" else "Режим: ${p.name}") {
        if (p == null) { Small("Выбери «Стоит насос» выше (или «в скважину» в подборе на вкладке «Насос») — посчитаю подачу, давления, уровень в скважине" + if (Core.installOf(WP.well) == "surface") " и запас по всасыванию." else "."); return@QCard }
        val e = Core.evaluatePump(p, WP); val rv = Core.regimeView(e, p, WP); val r = WP.relay
        Badge(NoteKind.INFO, Core.TYPE_NAMES[Core.pumpType(p)] ?: "")
        Note(when (rv.kind) { "ok" -> NoteKind.OK; "crit" -> NoteKind.CRIT; else -> NoteKind.WARN }, rv.verdict)
        Small("Реле давления ${f1(r.on)}/${f1(r.off)} бар: насос включается, когда давление упало до ${f1(r.on)}, и выключается, когда поднялось до ${f1(r.off)}. Подача в этих точках — рабочий диапазон.", color = Q.muted, modifier = Modifier.padding(vertical = 4.dp))
        QTable(null, rv.rows, listOf(1.2f, 1f))
        Core.cavNote(app.measLive.filter { it.wellId == WP.id })?.let { (k, t) -> Note(when (k) { "ok" -> NoteKind.OK; "crit" -> NoteKind.CRIT; else -> NoteKind.WARN }, t) }
        if (p.approx) Note(NoteKind.WARN, "Кривая насоса приблизительная (по Hmax/Qmax из паспорта) — точность режима ~5–10%, у отдельных моделей хуже")
        // вердикт уже сказал главное — не дублируем теми же предупреждениями
        val dup = Regex("не выключится|Не даёт .* в этой системе|только .* бар \\(нужно")
        e.warn.filterNot { dup.containsMatchIn(it) }.forEach { Note(NoteKind.WARN, it) }
        e.ok.forEach { Note(NoteKind.OK, it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapCard(app: AppState, siteMode: Boolean = false) {
    val ctx = LocalContext.current
    val WP = app.W
    val prefs = remember { ctx.getSharedPreferences("qdebit_map", android.content.Context.MODE_PRIVATE) }
    var srcId by remember { mutableStateOf(normMapSource(prefs.getString("src", null))) }
    var full by remember { mutableStateOf(false) }
    var showDist by remember { mutableStateOf(prefs.getBoolean("dist", true)) }
    val setDist: (Boolean) -> Unit = { v -> showDist = v; prefs.edit().putBoolean("dist", v).apply() }
    var coordText by remember(WP.id) { mutableStateOf(WP.lat?.let { "${WP.lat}, ${WP.lon}" } ?: "") }
    LaunchedEffect(WP.lat, WP.lon) { coordText = WP.lat?.let { "${WP.lat}, ${WP.lon}" } ?: "" }
    val pts = app.liveWells.filter { it.lat != null && it.lon != null }.map { w ->
        val last = app.measLive.firstOrNull { it.wellId == w.id && it.q > 0 }
        MapPoint(w.id, w.name, w.lat!!, w.lon!!, last?.let { "${f2(it.q)} м³/ч" } ?: "нет замеров", w.id == WP.id)
    }
    // где открыть карту: активная скважина → любая скважина → последняя позиция телефона → Подмосковье целиком
    // «Участок»: все скважины в кадре — центр и масштаб по их разбросу
    fun allView(): Triple<Double, Double, Double>? {
        if (pts.isEmpty()) return null
        val la = pts.map { it.lat }; val lo = pts.map { it.lon }
        val cLat = (la.max() + la.min()) / 2; val cLon = (lo.max() + lo.min()) / 2
        val span = maxOf(40.0, pts.maxOf { a -> pts.maxOf { b -> Core.distM(a.lat, a.lon, b.lat, b.lon) } })
        val z = Math.log(156543.03 * Math.cos(Math.toRadians(cLat)) / (span * 1.6 / 900.0)) / Math.log(2.0)
        return Triple(cLat, cLon, z.coerceIn(12.0, 19.0))
    }
    fun startView(): Triple<Double, Double, Double> =
        (if (siteMode) allView() else null)
            ?: WP.lat?.let { Triple(it, WP.lon!!, 18.0) } ?: pts.firstOrNull()?.let { Triple(it.lat, it.lon, 17.0) }
            ?: lastKnownLocation(ctx)?.let { Triple(it.latitude, it.longitude, 17.0) } ?: Triple(55.75, 37.62, 9.0)
    QCard(if (siteMode) "План участка" else "Карта участка") {
        // превью: жесты не принимает, страницу листать не мешает; тап — полноэкранная карта
        val preview = remember { SiteMapState() }
        val sv = remember(WP.id) { startView() }
        LaunchedEffect(WP.id, WP.lat, WP.lon) { if (!siteMode) WP.lat?.let { preview.jumpTo(it, WP.lon!!) } }
        val ph = if (siteMode) 280.dp else 200.dp
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(ph).clip(RoundedCornerShape(12.dp))) {
            SiteMap(preview, pts, sv.first to sv.second, sv.third, srcId, interactive = false, crossLabel = null,
                modifier = Modifier.fillMaxWidth().height(ph), onTapWell = {}, showDist = showDist)
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize().clickable { full = true })
            Text("⤢ Открыть карту", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.Button(onClick = { full = true }) { Text("⤢ Открыть карту") }
            if (pts.size >= 2) Chip("📏 Расстояния на карте", showDist) { setDist(!showDist) }
        }
        // режим подложки — здесь, в развёрнутой карте только карта и две кнопки
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MAP_SOURCES.forEach { m -> Chip(m.label, m.id == srcId) { srcId = m.id; prefs.edit().putString("src", m.id).apply() } }
        }
        Small(if (siteMode) "Режим: ${MAP_SOURCES.firstOrNull { it.id == srcId }?.label}. Тап — на весь экран. Только просмотр — точки задаются на вкладке «Скважина»." + (if (pts.size < app.liveWells.size) " Без координат: ${app.liveWells.size - pts.size} скв." else "")
              else if (WP.lat == null) "«${WP.name}» ещё не на карте — открой карту и поставь её перекрестием." else "Тап по карте — на весь экран: наведи перекрестие на скважину и нажми кнопку с булавкой «+». Тап по другой точке — выбрать ту скважину.",
            color = Q.muted, modifier = Modifier.padding(top = 6.dp))
        if (!siteMode) TextFieldQ("Координаты «${WP.name}» (широта, долгота)", coordText, { t ->
            coordText = t
            if (t.isBlank()) app.updateWell { it.copy(lat = null, lon = null) }
            else Core.parseLatLon(t)?.let { c -> if (c.lat != WP.lat || c.lon != WP.lon) app.updateWell { it.copy(lat = c.lat, lon = c.lon) } }
        }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = "55.751244, 37.618423 — можно вставить из любых карт")
        if (pts.size > 1 && !siteMode) {
            H3("Расстояния")
            QTable(listOf("") + pts.map { it.name }, pts.map { a -> listOf(a.name) + pts.map { b -> if (a.id == b.id) "—" else Core.fmtDist(Core.distM(a.lat, a.lon, b.lat, b.lon)) } })
        }
    }
    if (full) FullMapDialog(app, pts, startView(), srcId, readOnly = siteMode, showDist = showDist, onClose = { full = false })
}

/** Полноэкранная карта: сверху название и «Закрыть», снизу две кнопки — «моё положение» и «поставить точку». Режимы — в карточке. */
@Composable
private fun FullMapDialog(app: AppState, pts: List<MapPoint>, sv: Triple<Double, Double, Double>, srcId: String, readOnly: Boolean = false,
                          showDist: Boolean = true, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val WP = app.W
    val st = remember { SiteMapState() }
    val gps = remember { GpsAverager(ctx) }
    val start = remember { sv }
    DisposableEffect(Unit) { onDispose { gps.stop() } }
    // итог GPS (точность или ошибка) — коротким тостом, без панелей поверх карты
    LaunchedEffect(gps.running) { if (!gps.running && gps.status.isNotEmpty()) Toast.makeText(ctx, gps.status, Toast.LENGTH_SHORT).show() }
    // «моё положение»: только переносит карту к телефону и рисует синюю точку; скважину ставишь сам
    val askGps = rememberLocationPermission {
        gps.start(quick = true) { lat, lon, acc, alt, _ ->
            st.showMe(lat, lon, acc)
            st.flyTo(lat, lon, 18.0)
            // высота над морем для NPSH поверхностных — если в скважине ещё значение по умолчанию (только при правке скважины)
            if (!readOnly && alt != null && WP.well.alt == 150.0) app.updateWell { x -> x.copy(well = x.well.copy(alt = Math.round(alt).toDouble())) }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // перекрестие без подписи (crossLabel = "") — только при правке скважины
            SiteMap(st, pts, start.first to start.second, start.third, srcId, interactive = true, crossLabel = if (readOnly) null else "",
                modifier = Modifier.fillMaxSize(), onTapWell = { if (!readOnly) app.setActiveWell(it) }, showDist = showDist)
            // верх: название скважины и «Закрыть»
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (readOnly) "Участок" else WP.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).background(panel, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("✕ Закрыть", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp).background(panel, RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // низ: две круглые кнопки
            Row(Modifier.align(Alignment.BottomEnd)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
                .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FloatingActionButton(onClick = { if (gps.running) gps.stop() else askGps() },
                    containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
                    if (gps.running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(org.qdebit.android.R.drawable.ic_my_location), contentDescription = "Моё положение")
                }
                if (!readOnly) androidx.compose.material3.FloatingActionButton(onClick = {
                    val c = st.center() ?: return@FloatingActionButton
                    app.updateWell { it.copy(lat = Math.round(c.first * 1e7) / 1e7, lon = Math.round(c.second * 1e7) / 1e7) }
                    Toast.makeText(ctx, "«${WP.name}» поставлена в перекрестие", Toast.LENGTH_SHORT).show()
                }, containerColor = MaterialTheme.colorScheme.primary, contentColor = androidx.compose.ui.graphics.Color.White) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(org.qdebit.android.R.drawable.ic_add_location), contentDescription = "Поставить «${WP.name}» в перекрестие")
                }
            }
        }
    }
}

/** Абиссинка: насос сверху тянет со статики; трение только от зеркала до насоса + трасса */
@Composable
private fun AbyssModelCard(app: AppState) {
    val WP = app.W; val w = WP.well; val wm = Core.wellModel(w)
    val tq = WP.target.q?.takeIf { it > 0 } ?: 1.0
    val s0 = Core.suctionAt(0.0, WP.sys, w, wm); val s1 = Core.suctionAt(tq, WP.sys, w, wm)
    QCard("Модель: ${WP.name}") {
        KV(listOf(
            f1(s0.lift) to "подъём от статики до насоса, м",
            f1(s1.loss) to "потери на всасывании при ${f1(tq)} м³/ч, м",
            f1(s1.total) to "высота всасывания при ${f1(tq)} м³/ч, м",
            f1(s1.npsha) to "NPSHa (запас до вскипания), м",
        ))
        val idmm = if (WP.sys.riserPipe == "custom") WP.sys.riserID else Core.pipeById(WP.sys.riserPipe)?.idmm
        val lv = maxOf(0.0, (w.static ?: 0.0) + (w.pumpElev ?: 0.0))
        Small("Игла внутр. ${f1(idmm)} мм. Насос тянет со статики; трение — на ${f1(lv)} м трубы от зеркала до насоса" +
            ((WP.sys.suctLen ?: 0.0).takeIf { it > 0 }?.let { " + ${f1(it)} м трассы" } ?: "") + " (ниже зеркала воду подпирает пласт).", modifier = Modifier.padding(top = 6.dp))
        if (s1.total > 8) Note(NoteKind.CRIT, "Высота всасывания ${f1(s1.total)} м — больше практического предела ~8 м для поверхностного насоса: будет срыв/кавитация. Насос ниже (кессон), трасса короче/толще.")
        else if (s1.total > 7) Note(NoteKind.WARN, "Высота всасывания ${f1(s1.total)} м — на грани (7–8 м): возможна кавитация.")
        val cn = Core.cavNote(app.measLive.filter { it.wellId == WP.id })
        if (cn != null) Note(when (cn.first) { "ok" -> NoteKind.OK; "crit" -> NoteKind.CRIT; else -> NoteKind.WARN }, cn.second)
        else Note(NoteKind.INFO, "Предел иглы виден только по замерам: при замере ведром отмечай кавитацию (шум/пузыри) — здесь появится, с какого расхода она начинается.")
    }
}

/** Геология и отметка устья: водоносный, кровля, горизонт, своя отметка (иначе — по рельефу), слои разреза */
@Composable
private fun GeoCard(app: AppState) {
    val WP = app.W; val w = WP.well
    fun up(f: (Well) -> Well) = app.updateWell { it.copy(well = f(it.well)) }
    val ab = Core.isAbyss(w); val dug = w.kind == "dug"
    val aq = Core.aquiferOf(w)
    QCard("Геология и отметка устья") {
        FieldGrid(
            { m -> NumField("Отметка устья, м абс.", w.elev, { v -> up { it.copy(elev = v) } }, m,
                hint = w.elevDem?.let { "по рельефу ${f1(it)} м (±2–5); своя — нивелир/лазер" } ?: if (WP.lat == null) "задай координаты — подтяну по рельефу" else "своя (нивелир/лазер)",
                placeholder = w.elevDem?.let { f1(it) }) },
            { m -> if (!ab && !dug) Select("Водоносный", listOf("" to "— не знаю —", "sand" to "песок", "limestone" to "известняк (артезианка)"), w.aquifer ?: "", { v -> up { it.copy(aquifer = v.ifBlank { null }) } }, m)
                   else Small(if (ab) "водоносный: песок" else "колодец", modifier = m.padding(top = 18.dp)) },
            { m -> if (!dug) NumField(when { ab || aq == "sand" -> "Песок с глубины, м"; aq == "limestone" -> "Известняк с глубины, м"; else -> "Кровля водоносного, м" }, w.aqTop, { v -> up { it.copy(aqTop = v) } }, m, hint = "от устья") else Spacer(m) },
            { m -> if (!dug) Select("Горизонт", listOf<Pair<Int?, String>>(null to "—", 1 to "1-й", 2 to "2-й", 3 to "3-й"), w.horizon, { v -> up { it.copy(horizon = v) } }, m) else Spacer(m) },
        )
        if (app.elevStatus.isNotEmpty()) Small(app.elevStatus, color = Q.muted)
        H3("Разрез (журнал бурения) — необязательно")
        if (w.layers.isEmpty()) Small("Слоёв нет. Если есть паспорт скважины или журнал бурения — добавь слои, они появятся на профиле-разрезе во вкладке «Участок».", color = Q.muted)
        w.layers.forEachIndexed { i, l ->
            fun setL(f: (org.qdebit.android.Layer) -> org.qdebit.android.Layer) = up { x -> x.copy(layers = x.layers.mapIndexed { j, y -> if (j == i) f(y) else y }) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                NumField("от, м", l.from, { v -> setL { it.copy(from = v) } }, Modifier.weight(0.8f))
                NumField("до, м", l.to, { v -> setL { it.copy(to = v) } }, Modifier.weight(0.8f))
                Select("порода", Core.ROCKS.map { it.id to it.name }, l.rock, { v -> setL { it.copy(rock = v) } }, Modifier.weight(1.4f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("вода", fontSize = 11.sp, color = Q.secondary)
                    androidx.compose.material3.Checkbox(checked = l.water, onCheckedChange = { c -> setL { it.copy(water = c) } })
                }
                TextButton(onClick = { up { x -> x.copy(layers = x.layers.filterIndexed { j, _ -> j != i }) } }) { Text("✕", color = Q.crit) }
            }
        }
        OutlinedButton(onClick = {
            up { x -> x.copy(layers = x.layers + org.qdebit.android.Layer(from = x.layers.lastOrNull()?.to ?: if (x.layers.isEmpty()) 0.0 else null, rock = if (x.layers.isEmpty()) "soil" else "sand")) }
        }, modifier = Modifier.padding(top = 4.dp)) { Text("+ слой") }
    }
}
