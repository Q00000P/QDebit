package org.qdebit.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.qdebit.android.AppState
import org.qdebit.android.Core
import org.qdebit.android.Core.f0
import org.qdebit.android.Core.f1
import org.qdebit.android.Core.f2
import org.qdebit.android.Pump
import org.qdebit.android.WellProfile
import org.qdebit.android.Sys

internal fun pipeOptions() = Core.PIPES.map { it.id to "${it.name} (внутр. ${f1(it.idmm)})" } + ("custom" to "своя…")

@Composable
private fun Swatch(c: Color) = Box(Modifier.width(12.dp).height(3.dp).background(c, RoundedCornerShape(2.dp)))

@Composable
fun PumpScreen(app: AppState) {
    val S = app.W
    val inst = Core.installOf(S.well)
    fun sys(f: (Sys) -> Sys) = app.updateWell { it.copy(sys = f(it.sys)) }
    val editor = remember { PumpEditorState() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        QCard("Потребность и система") {
            val ab = Core.isAbyss(S.well)
            val riserCell: (@Composable (Modifier) -> Unit)? = if (ab) null else ({ m ->
                Select(when (inst) { "surface" -> "Всасывающая труба"; "ejector" -> "Трубы к эжектору (в паспорте)"; else -> "Напорная в скважине" }, pipeOptions(), S.sys.riserPipe, { v -> sys { it.copy(riserPipe = v) } }, m) })
            val riserLenCell: (@Composable (Modifier) -> Unit)? = if (ab) null else ({ m ->
                NumField(if (inst == "surface") "Длина всасывающей, м" else "Длина в скважине, м", S.sys.riserLen, { v -> sys { it.copy(riserLen = v) } }, m, placeholder = S.well.pumpDepth?.let { numText(it) }, hint = if (inst == "surface") "пусто = глубина забора; трасса до насоса — на «Скважине»" else "пусто = глубина насоса") })
            val cells = listOfNotNull<@Composable (Modifier) -> Unit>(
                { m -> NumField("Нужный расход, м³/ч", S.target.q, { v -> app.updateWell { it.copy(target = it.target.copy(q = v)) } }, m) },
                { m -> NumField("Нужное давление, бар", S.target.p, { v -> app.updateWell { it.copy(target = it.target.copy(p = v)) } }, m, hint = "у потребителя при этом расходе") },
                { m -> NumField("Реле: включение, бар", S.relay.on, { v -> app.updateWell { it.copy(relay = it.relay.copy(on = v)) } }, m) },
                { m -> NumField("Реле: отключение, бар", S.relay.off, { v -> app.updateWell { it.copy(relay = it.relay.copy(off = v)) } }, m) },
                { m -> NumField("Высота потребителя, м", S.sys.dz, { v -> sys { it.copy(dz = v) } }, m, hint = "над оголовком (минус — ниже)") },
                { m -> NumField("Местные потери, %", S.sys.localPct, { v -> sys { it.copy(localPct = v) } }, m, hint = "отводы, клапан, фитинги") },
                // у абиссинки труба иглы и трасса до насоса — на вкладке «Скважина»
                riserCell, riserLenCell,
                { m -> Select("Труба трассы", pipeOptions(), S.sys.horizPipe, { v -> sys { it.copy(horizPipe = v) } }, m) },
                { m -> NumField("Длина трассы, м", S.sys.horizLen, { v -> sys { it.copy(horizLen = v) } }, m, hint = if (inst == "surface") "напорная: от насоса до потребителя" else "от оголовка до потребителя") },
                { m -> NumField("Температура воды, °C", S.sys.tempC, { v -> sys { it.copy(tempC = v) } }, m) },
            )
            FieldGrid(*cells.toTypedArray())
            if ((S.sys.riserPipe == "custom" && !ab) || S.sys.horizPipe == "custom") FieldGrid(
                { m -> if (S.sys.riserPipe == "custom" && !ab) NumField("Внутр. Ø в скважине, мм", S.sys.riserID, { v -> sys { it.copy(riserID = v) } }, m) else Spacer(m) },
                { m -> if (S.sys.horizPipe == "custom") NumField("Внутр. Ø трассы, мм", S.sys.horizID, { v -> sys { it.copy(horizID = v) } }, m) else Spacer(m) },
            )
        }
        Small("🕳 ${S.name} — у каждой скважины своя система и свои насосы на графике", color = Q.muted, modifier = Modifier.padding(bottom = 6.dp))
        NeedCard(S)
        CompareCard(app)
        RankCard(app, editor)
        EditorCard(app, editor)
    }
}

@Composable
private fun NeedCard(S: WellProfile) {
    val dp = Core.designPoint(S); val sh = dp.sh; val P = S.target.p ?: 0.0
    QCard("Требование к насосу") {
        Hero(f0(sh.total), "м напора при ${f2(dp.Q)} м³/ч")
        if (dp.limited) Note(NoteKind.WARN, "Потребность ${f1(dp.Qtarget)} м³/ч больше безопасного дебита ${f2(dp.wm.Qsafe)} — расчёт на ${f2(dp.Q)} м³/ч. Полив на ${f1(dp.Qtarget)} — только через накопительную ёмкость.")
        QTable(null, listOf(
            listOf("Подъём до уровня воды", "${f1(sh.level)} м", if (dp.wm.ok) "по модели" else "фикс. динамика"),
            listOf("Высота потребителя", "${f1(sh.dz)} м", ""),
            listOf("Давление ${f1(P)} бар", "${f1(sh.hp)} м", ""),
            listOf(if (Core.installOf(S.well) == "surface") "Трение во всасывающей" else "Трение в скважине", "${f1(sh.hfRiser)} м", "v ${f2(sh.vRiser)} м/с"),
            listOf("Трение трассы", "${f1(sh.hfHoriz)} м", "v ${f2(sh.vHoriz)} м/с"),
            listOf("Местные потери", "${f1(sh.local)} м", "${f0(S.sys.localPct)}%"),
            listOf("Итого", "${f1(sh.total)} м", ""),
        ), listOf(1.8f, 1f, 1.1f))
        val hoff = Core.systemHead(0.05, S.relay.off ?: 0.0, S.sys, S.well, dp.wm).total
        Small("Чтобы реле отключилось на ${f1(S.relay.off)} бар, насос должен давать ≥ ${f0(hoff)} м на малом расходе.", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
        val vmax = maxOf(sh.vRiser, sh.vHoriz)
        if (vmax > 2) Note(NoteKind.WARN, "Скорость ${f1(vmax)} м/с — трубы узкие для ${f2(dp.Q)} м³/ч, трение съедает ${f0(sh.hfRiser + sh.hfHoriz + sh.local)} м напора (норма до 1,5–2 м/с).")
        val inst = Core.installOf(S.well)
        if (inst == "surface") {
            val su = Core.suctionAt(dp.Q, S.sys, S.well, dp.wm)
            H3("Всасывание при ${f2(dp.Q)} м³/ч")
            Small("Геометрическая высота (уровень ${f1(sh.level)} м + насос над землёй ${f1(S.well.pumpElev ?: 0.0)} м): ${f1(su.lift)} м + потери во всасывающей ${f1(su.loss)} м = ${f1(su.total)} м. NPSHa ${f1(su.npsha)} м (атмосфера ${f2(Core.hAtm(S.well.alt))} м).", color = MaterialTheme.colorScheme.onSurface)
            Note(if (su.total > 8) NoteKind.CRIT else if (su.total > 7) NoteKind.WARN else NoteKind.OK,
                "Обычные самовсасывающие по паспорту берут 8–9 м; реально с потерями — меньше. " + when { su.total > 8 -> "Здесь больше — нужен выносной эжектор или погружной."; su.total > 7 -> "На грани — проверь паспортную глубину всасывания."; else -> "Запас есть." })
        }
        if (inst == "ejector") {
            val eh = (sh.dz - (S.well.pumpElev ?: 0.0)) + sh.hp + sh.hfHoriz + sh.localHoriz
            Note(NoteKind.INFO, "Выносной эжектор: нужен напор на выходе насоса ${f0(eh)} м при глубине всасывания ≈ ${f1(sh.level + (S.well.pumpElev ?: 0.0))} м. Паспорт даёт кривые по глубинам — сравниваем с ними; график в этой системе отсчёта.")
        }
        H3("Если сменить трубу (скважина + трасса)")
        val alts = Core.pipeAlternatives(S)
        QTable(listOf("труба", "Ø вн.", "v, м/с", "потери", "напор"), alts.map { a ->
            listOf(a.pipe.name, f1(a.pipe.idmm), f2(a.sh.vRiser), "${f1(a.hf)} м", "${f0(a.sh.total)} м")
        }, listOf(1.7f, 0.8f, 0.8f, 1f, 1f)) { alts[it].current }
    }
}

@Composable
private fun CompareCard(app: AppState) {
    val S = app.W
    val slots = S.chart.map { app.pumpById(it) }
    QCard("Насосы на графике") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Swatch(MaterialTheme.colorScheme.onSurface); Small("система при ${f1(S.target.p)} бар")
            Swatch(Q.muted); Small("реле ${f1(S.relay.on)}–${f1(S.relay.off)}")
        }
        slots.forEachIndexed { i, p -> if (p != null) Row(verticalAlignment = Alignment.CenterVertically) { Swatch(Q.series[i]); Small("  " + p.name) } }
        QHChart(S, slots, Modifier.padding(top = 6.dp))
        Small("Тапни/веди по графику — значения в точке.", color = Q.muted)
        val ev = slots.mapIndexedNotNull { i, p -> p?.let { i to Core.evaluatePump(it, S) } }
        if (ev.isEmpty()) { Small("Отметь насосы в «Подборе», чтобы сравнить."); return@QCard }
        val wmOk = Core.wellModel(S.well).ok
        val rows = ArrayList<Pair<String, (Core.Eval) -> String>>()
        rows += "Давление при ${f1(S.target.q)} м³/ч" to { e: Core.Eval -> e.PatNeed?.let { "${f2(it)} бар" } ?: "—" }
        rows += "Расход при ${f1(S.target.p)} бар" to { e: Core.Eval -> e.Qp?.let { "${f2(it)} м³/ч" } ?: "не держит" }
        rows += "На включении ${f1(S.relay.on)}" to { e: Core.Eval -> e.Qon?.let { "${f2(it)} м³/ч" } ?: "не держит" }
        rows += "На отключении ${f1(S.relay.off)}" to { e: Core.Eval -> e.Qoff?.takeIf { it > 0.05 }?.let { "${f2(it)} м³/ч" } ?: "не отключит!" }
        rows += "Излив без давления" to { e: Core.Eval -> e.Qfree?.let { "${f2(it)} м³/ч" } ?: "—" }
        rows += "Напор при Q=0" to { e: Core.Eval -> "${f0(e.H0)} м" }
        rows += "Макс. P у потребителя" to { e: Core.Eval -> "${f1(e.Pshut)} бар" }
        rows += "…на дне трубы" to { e: Core.Eval -> "${f1(e.PshutBottom)} бар" + if (e.pipePN != null && (e.PshutBottom ?: 0.0) > e.pipePN) " >PN${e.pipePN}" else "" }
        if (wmOk) rows += "До предела на включении" to { e: Core.Eval -> e.drainOn?.let { Core.fmtHours(it) } ?: "тянет" }
        rows += "Зазор в колонне" to { e: Core.Eval -> e.gap?.let { "${f1(it)} мм" } ?: "—" }
        rows += "Мощность P1" to { e: Core.Eval -> e.pump.powerKW?.let { "${f2(it)} кВт" + (e.pump.currentA?.let { a -> ", ${f1(a)} А" } ?: "") } ?: "—" }
        rows += "Гидр. мощн. / КПД" to { e: Core.Eval -> e.Phyd?.let { "${f2(it)} кВт" + (e.eta?.let { x -> " / ${f0(x * 100)}%" } ?: "") } ?: "—" }
        rows += "Итог" to { e: Core.Eval -> if (e.fits) "✓ подходит" else if (e.fitsCore) "! с оговорками" else "✕ нет" }
        Column(Modifier.padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Spacer(Modifier.weight(1.5f))
                ev.forEach { (i, e) -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { Swatch(Q.series[i]); Text(" " + e.pump.name.removePrefix("UNIPUMP "), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) } }
            }
            rows.forEach { (name, fn) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(name, fontSize = 12.5.sp, color = Q.secondary, modifier = Modifier.weight(1.5f))
                    ev.forEach { (_, e) ->
                        val v = fn(e)
                        val col = when { v.startsWith("✓") || v == "тянет" -> Q.good; v.startsWith("✕") || v.contains("не ") -> Q.crit; v.startsWith("!") || v.contains(">PN") -> Q.warn; else -> MaterialTheme.colorScheme.onSurface }
                        Text(v, fontSize = 12.5.sp, color = col, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            ev.forEach { (_, e) ->
                if (e.warn.isNotEmpty()) {
                    Text(e.pump.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    e.warn.forEach { Note(NoteKind.WARN, it) }
                }
            }
        }
    }
}

class PumpEditorState {
    var id by mutableStateOf("")
    var name by mutableStateOf("")
    var diam by mutableStateOf("")
    var pow by mutableStateOf("")
    var cur by mutableStateOf("")
    var minQ by mutableStateOf("")
    var pts by mutableStateOf("")
    var lpm by mutableStateOf(false)
    var note by mutableStateOf("")
    var type by mutableStateOf("sub")
    var suction by mutableStateOf("")
    var requestFocus by mutableStateOf(0)

    fun fill(p: Pump) {
        id = if (p.catalog) "" else p.id
        name = if (p.catalog) p.name + " (копия)" else p.name
        diam = numText(p.diam); pow = numText(p.powerKW); cur = numText(p.currentA); minQ = numText(p.minQ)
        pts = Core.curvesToText(p); lpm = false; type = Core.pumpType(p); suction = numText(p.suction)
        note = p.note ?: p.src ?: ""
        requestFocus++
    }
    fun clear() { id = ""; name = ""; diam = ""; pow = ""; cur = ""; minQ = ""; pts = ""; lpm = false; note = ""; suction = "" }
    fun read(): Pump {
        val withDepth = Core.parseCurves(pts, lpm).filter { it.depth != null }
        return Pump(
            id = id.ifEmpty { "u-" + java.lang.Long.toString(System.currentTimeMillis(), 36) }, name = name.trim(),
            diam = parseNum(diam), powerKW = parseNum(pow), currentA = parseNum(cur), minQ = parseNum(minQ),
            points = if (withDepth.isNotEmpty()) withDepth.first().points else Core.parsePoints(pts, lpm), note = note.trim().ifEmpty { null },
            type = type, suction = parseNum(suction), curves = if (type == "ejector" && withDepth.isNotEmpty()) withDepth else null,
        )
    }
}

@Composable
private fun RankCard(app: AppState, editor: PumpEditorState) {
    val ctx = LocalContext.current
    val S = app.W
    var fitDiam by rememberSaveable { mutableStateOf(false) }
    var onlyType by rememberSaveable { mutableStateOf(true) }
    var rq by rememberSaveable { mutableStateOf("") }
    var onlyFit by rememberSaveable { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    val D = S.well.casingID; val gapMin = S.well.minGap ?: 8.0
    // сначала дешёвые фильтры, потом расчёт; результат кэшируется — каталог на сотни моделей
    val pumps = app.allPumps()
    val ranked = androidx.compose.runtime.remember(S, pumps, onlyType, fitDiam, rq) {
        var pool = pumps
        if (onlyType) pool = pool.filter { Core.pumpType(it) == Core.installOf(S.well) }
        if (fitDiam && D != null) pool = pool.filter { it.diam == null || (D - it.diam) / 2 >= gapMin }
        if (rq.isNotBlank()) pool = pool.filter { Core.matchName(it.name, rq) }
        Core.rankPumps(pool, S)
    }
    var list = ranked
    if (onlyFit) list = list.filter { it.fitsCore }
    val total = list.size
    if (!all && rq.isBlank()) list = list.filterIndexed { i, e -> i < 8 || e.pump.id in S.chart }
    val tq = S.target.q
    QCard("Подбор") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(fitDiam, { fitDiam = it }); Small("влезает в колонну", modifier = Modifier.weight(1f))
            Checkbox(onlyFit, { onlyFit = it }); Small("только подходящие", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyType, { onlyType = it }); Small("только тип установки: ${Core.INSTALL_NAMES[Core.installOf(S.well)]}", modifier = Modifier.weight(1f))
        }
        TextFieldQ("Поиск: 4-142, водомет 55, jsw, dab s4…", rq, { rq = it }, Modifier.fillMaxWidth())
        Small("Каталог + твои насосы, по запасу давления в расчётной точке. ☐ — на график (до 3).", color = Q.muted)
        if (list.isEmpty()) Small("Под фильтр ничего не попало.", modifier = Modifier.padding(top = 8.dp))
        list.forEach { e -> RankRow(app, e, tq, editor) }
        if (total > list.size) TextButton(onClick = { all = true }) { Text("Показать все ($total)") }
        else if (all && total > 8) TextButton(onClick = { all = false }) { Text("Свернуть") }
    }
}

@Composable
private fun RankRow(app: AppState, e: Core.Eval, tq: Double?, editor: PumpEditorState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var arm by remember { mutableStateOf(false) }
    var showWarn by remember { mutableStateOf(false) }
    val p = e.pump; val S = app.W
    val slot = S.chart.indexOf(p.id)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = slot >= 0, onCheckedChange = { on ->
                if (on) {
                    val free = S.chart.indexOf(null)
                    if (free < 0) { Toast.makeText(ctx, "На графике максимум 3 насоса — сними один", Toast.LENGTH_SHORT).show(); return@Checkbox }
                    app.updateWell { it.copy(chart = it.chart.toMutableList().also { c -> c[free] = p.id }) }
                } else app.updateWell { it.copy(chart = it.chart.map { c -> if (c == p.id) null else c }) }
            })
            if (slot >= 0) { Swatch(Q.series[slot]); Spacer(Modifier.width(6.dp)) }
            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (p.approx) { Badge(NoteKind.WARN, "≈ кривая"); Spacer(Modifier.width(4.dp)) }
            Badge(if (e.fits) NoteKind.OK else if (e.fitsCore) NoteKind.WARN else NoteKind.CRIT, if (e.fits) "подходит" else if (e.fitsCore) "с оговорками" else "нет")
        }
        val facts = listOfNotNull(
            if (e.PatNeed != null) "${f2(e.PatNeed)} бар при ${f1(tq)} м³/ч" else "не даёт ${f1(tq)} м³/ч",
            if (e.Qoff != null && e.Qoff > 0.05) "цикл ${f2(e.Qoff)}–${f2(e.Qon)} м³/ч" else "реле не отключит",
            e.QsuctMax?.let { "всасывание до ${f2(it)} м³/ч" }, Core.TYPE_NAMES[Core.pumpType(p)],
            "H₀ ${f0(e.H0)} м", p.diam?.let { "Ø${f0(it)}" }, p.powerKW?.let { "${f2(it)} кВт" },
        ).joinToString(" · ")
        Small(facts, modifier = Modifier.padding(start = 4.dp))
        (p.note ?: p.src)?.let { Small(it, color = Q.muted, modifier = Modifier.padding(start = 4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.warn.isNotEmpty()) TextButton(onClick = { showWarn = !showWarn }) { Text("${if (showWarn) "▾" else "▸"} ${e.warn.size} замеч.", fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            val installed = app.W.pumpId == p.id
            TextButton(onClick = {
                app.updateWell { w -> w.copy(pumpId = if (installed) null else p.id,
                    chart = if (!installed && p.id !in w.chart && w.chart.indexOf(null) >= 0) w.chart.toMutableList().also { it[w.chart.indexOf(null)] = p.id } else w.chart) }
                Toast.makeText(ctx, if (installed) "Насос убран из скважины" else "«${p.name}» стоит в «${app.W.name}» — режим на вкладке «Скважина»", Toast.LENGTH_SHORT).show()
            }) { Text(if (installed) "★ стоит" else "в скважину", fontSize = 13.sp) }
            if (p.catalog) TextButton(onClick = { editor.fill(p) }) { Text("копия", fontSize = 13.sp) }
            else {
                TextButton(onClick = { editor.fill(p) }) { Text("правка", fontSize = 13.sp) }
                TextButton(onClick = {
                    if (!arm) { arm = true; scope.launch { delay(2500); arm = false }; return@TextButton }
                    app.deletePump(p.id)
                    Toast.makeText(ctx, "Насос удалён", Toast.LENGTH_SHORT).show()
                }) { Text(if (arm) "точно?" else "удалить", color = Q.crit, fontSize = 13.sp) }
            }
        }
        if (showWarn) e.warn.forEach { Note(NoteKind.WARN, it) }
    }
}

@Composable
private fun EditorCard(app: AppState, ed: PumpEditorState) {
    val ctx = LocalContext.current
    var catOpen by remember { mutableStateOf(false) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val (n, skip) = app.importPumps(text)
            Toast.makeText(ctx, "Добавлено насосов: $n" + if (skip > 0) ", пропущено $skip (без точек или уже есть)" else "", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(ctx, "Не читается: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    QCard(if (ed.id.isEmpty()) "Свой насос" else "Свой насос — правка") {
        TextFieldQ("Название", ed.name, { ed.name = it }, Modifier.fillMaxWidth(), placeholder = "Aquario ASP3E-95-75")
        FieldGrid(
            { m -> Select("Тип", Core.TYPE_NAMES.entries.map { it.key to it.value }, ed.type, { ed.type = it }, m) },
            { m -> TextFieldQ("Глубина всасывания, м", ed.suction, { ed.suction = it }, m, kb = KeyboardType.Decimal) },
        )
        FieldGrid(
            { m -> TextFieldQ("Ø, мм", ed.diam, { ed.diam = it }, m, kb = KeyboardType.Decimal) },
            { m -> TextFieldQ("Мощность P1, кВт", ed.pow, { ed.pow = it }, m, kb = KeyboardType.Decimal) },
            { m -> TextFieldQ("Ток, А", ed.cur, { ed.cur = it }, m, kb = KeyboardType.Decimal) },
            { m -> TextFieldQ("Мин. расход, м³/ч", ed.minQ, { ed.minQ = it }, m, kb = KeyboardType.Decimal) },
        )
        TextFieldQ("Точки «Q  H» — по строке, с графика паспорта", ed.pts, { ed.pts = it }, Modifier.fillMaxWidth(), placeholder = "0 105\n1 100\n2 92\n3 80\n4 62\n5 38", singleLine = false, minLines = 5)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ed.lpm, { ed.lpm = it }); Small("Q в л/мин")
        }
        Small("Эжекторный: кривая на каждую глубину всасывания — строка «@10», под ней «Q H на выходе», потом «@20» и т.д.", color = Q.muted)
        TextFieldQ("Источник / примечание", ed.note, { ed.note = it }, Modifier.fillMaxWidth(), placeholder = "паспорт, рис. 3")
        val prev = Core.parsePoints(ed.pts, ed.lpm)
        val prevCurves = Core.parseCurves(ed.pts, ed.lpm).filter { it.depth != null }
        if (prevCurves.isNotEmpty()) Small("Кривых по глубине: ${prevCurves.size} (" + prevCurves.joinToString(", ") { "${f0(it.depth)} м: ${it.points.size} т." } + ")", color = Q.muted, modifier = Modifier.padding(top = 4.dp))
        else Small(
            if (prev.isEmpty()) "Введи хотя бы 3 точки: Q=0, середина, край кривой."
            else "Точек: ${prev.size} · Q до ${f2(prev.last()[0])} м³/ч · H₀ ${f0(prev.first()[1])} м" + if (prev.first()[0] != 0.0) " · нет точки Q=0 — H₀ возьмётся из первой" else "",
            color = Q.muted, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val p = ed.read()
                if (p.name.isEmpty()) { Toast.makeText(ctx, "Нужно название", Toast.LENGTH_SHORT).show(); return@Button }
                if (p.points.size < 2 && p.curves.isNullOrEmpty()) { Toast.makeText(ctx, "Нужно минимум 2 точки Q H", Toast.LENGTH_SHORT).show(); return@Button }
                app.savePump(p)
                ed.id = p.id
                Toast.makeText(ctx, "Насос сохранён", Toast.LENGTH_SHORT).show()
            }) { Text("Сохранить") }
            OutlinedButton(onClick = { ed.clear() }) { Text("Очистить") }
        }
        PumpPickerField("", org.qdebit.android.Catalog.ALL, null, { it?.let { ed.fill(it) } }, Modifier.fillMaxWidth().padding(top = 6.dp),
            allowClear = false, buttonText = "Скопировать из каталога…")
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.padding(top = 6.dp)) { Text("Импорт насосов JSON") }
        Small("JSON-массив: name, type (sub/surface/ejector), points [[Q м³/ч, H м]…], suction, diam, powerKW; эжекторные — curves [{depth, points}].", color = Q.muted)
    }
}
