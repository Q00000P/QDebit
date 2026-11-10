package org.qdebit.android.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.qdebit.android.AppState
import org.qdebit.android.BuildConfig
import org.qdebit.android.Core
import org.qdebit.android.Core.f0
import org.qdebit.android.Core.f1
import org.qdebit.android.Core.f2
import org.qdebit.android.Core.fv
import org.qdebit.android.Measurement

private fun parseTime(s: String): Double? {
    val t = s.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split(':').map { it.toDoubleOrNull() ?: return null }
    return parts.fold(0.0) { a, x -> a * 60 + x }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeasureScreen(app: AppState, keys: SharedFlow<Unit>) {
    val S = app.s
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var t0 by remember { mutableLongStateOf(0L) }          // elapsedRealtimeNanos старта
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var status by remember { mutableStateOf(if (app.series.isNotEmpty()) "Восстановлена серия: ${app.series.size} вёдер" else "Готов. Кнопки громкости — старт/стоп") }
    var editPresets by remember { mutableStateOf(false) }
    var resetArm by remember { mutableStateOf(false) }

    val vol = S.volume
    fun start() {
        if (vol == null || vol <= 0) { Toast.makeText(ctx, "Укажи объём ёмкости", Toast.LENGTH_SHORT).show(); return }
        t0 = SystemClock.elapsedRealtimeNanos(); running = true
        status = "● Ведро №${app.series.size + 1}, ${fv(vol)} л — стоп, когда наполнится"
    }
    fun stop() {
        if (!running) return
        running = false
        val t = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        elapsed = t
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (t < 0.5) { status = "Слишком коротко — не засчитано"; return }
        app.addRun(vol ?: return, t)
        val r = app.series.last()
        status = "Ведро №${app.series.size}: ${f2(Core.Units(r.v / r.t).m3h)} м³/ч"
    }
    fun toggle() = if (running) stop() else start()

    LaunchedEffect(running) {
        while (running) { withFrameNanos { }; elapsed = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9 }
    }
    val onKey by rememberUpdatedState<() -> Unit> { toggle() }
    LaunchedEffect(Unit) { keys.collect { onKey() } }
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    val st = Core.seriesStats(app.series, S.reaction)
    val BW = app.wellById(S.measWell)                 // привязка замера (null = просто померить)
    val wm = BW?.let { Core.wellModel(it.well) }
    val tq = BW?.target?.q

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        // ---- ёмкость
        QCard("Ёмкость") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                S.presets.forEachIndexed { i, p ->
                    Chip("${fv(p)} л${if (editPresets) "  ✕" else ""}", on = p == vol) {
                        if (running) return@Chip
                        if (editPresets) app.updatePrefs { it.copy(presets = it.presets.filterIndexed { j, _ -> j != i }) }
                        else app.updateLocal { it.copy(volume = p) }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField("Объём, л", vol, { v -> if (v != null && v > 0) app.updateLocal { it.copy(volume = v) } }, Modifier.weight(1f), enabled = !running)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val v = vol ?: return@OutlinedButton
                    if (v !in S.presets) app.updatePrefs { it.copy(presets = (it.presets + v).sorted()) }
                }) { Text("+ пресет") }
                TextButton(onClick = { editPresets = !editPresets }) { Text(if (editPresets) "готово" else "изм.") }
            }
        }
        // ---- таймер
        QCard {
            Text(Core.fmtDur(elapsed), fontSize = 64.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
                color = if (running) Q.good else androidx.compose.material3.MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
            Small(status, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            Button(
                onClick = { toggle() },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Q.stop else Q.start),
            ) { Text(if (running) "■ СТОП" else if (app.series.isEmpty()) "▶ СТАРТ" else "▶ СТАРТ (след. ведро)", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.undoRun() }, enabled = app.series.isNotEmpty() && !running) { Text("↶ последнее") }
                OutlinedButton(onClick = {
                    if (running) return@OutlinedButton
                    if (app.series.isNotEmpty() && !resetArm) { resetArm = true; scope.launch { delay(2500); resetArm = false }; return@OutlinedButton }
                    resetArm = false; app.resetSeries(); elapsed = 0.0; status = "Новая серия"
                }) { Text(if (resetArm) "точно?" else "↻ новая серия") }
                Spacer(Modifier.weight(1f))
                if (app.series.isNotEmpty()) Small("${app.series.size} шт")
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Small("Кнопки громкости = старт/стоп", modifier = Modifier.weight(1f))
                Switch(checked = S.volKeys, onCheckedChange = { v -> app.updateLocal { it.copy(volKeys = v) } })
            }
            ManualEntry(app, vol)
        }
        // ---- результат
        QCard((if (st != null && st.n > 1) "Результат — среднее по ${st.n} вёдрам" else "Результат") + (BW?.let { " · ${it.name}" } ?: "")) {
            if (st == null) {
                Small("Наполни ведро: старт → стоп. Каждое следующее ведро добавляется в серию, считается среднее.", modifier = Modifier.padding(vertical = 14.dp))
            } else {
                val a = st.avg
                Hero(f2(a.m3h), "м³/ч")
                KV(listOf(f1(a.lpm) to "л/мин", f2(a.lps) to "л/с", f1(a.m3d) to "м³/сут", Core.fmtDur(a.secPerM3).substringBefore('.') to "на 1 м³"))
                Small("Всего ${fv(st.V)} л за ${Core.fmtDur(st.T)} · точность по реакции ±${S.reaction} с: ±${f1(st.errSeries * 100)}% (±${f2(a.m3h * st.errSeries)} м³/ч)", modifier = Modifier.padding(top = 8.dp))
                if (tq != null && tq > 0) {
                    val pct = a.m3h / tq
                    Small("От потребности ${f1(tq)} м³/ч: ${f0(pct * 100)}%", modifier = Modifier.padding(top = 8.dp), weight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { pct.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                val k = st.perRun.indices.minBy { st.perRun[it].t }
                if (st.perRun[k].t < 15) Note(NoteKind.WARN, "Ведро №${k + 1}: ${f1(st.perRun[k].t)} с — коротко, ошибка реакции ±${f0(st.errRun[k] * 100)}%. Лучше ёмкость, что наполняется ≥ 20–30 с.")
                if (st.n >= 2 && st.cv > 0.05) Note(NoteKind.WARN, "Разброс между вёдрами ${f1(st.cv * 100)}% — дебит ещё «гуляет» (уровень не установился?) или неточно засекал. Сделай ещё пару.")
                if (st.n >= 3 && st.cv <= 0.03) Note(NoteKind.OK, "Серия стабильна: разброс ${f1(st.cv * 100)}%")
                if (st.n == 1) Note(NoteKind.INFO, "Одно ведро — для надёжности сделай 3+, среднее посчитается само.")
                if (wm != null && wm.ok) wm.Qsafe?.let { qs -> Note(if (a.m3h > qs) NoteKind.CRIT else NoteKind.OK, "Безопасный дебит «${BW.name}» по модели: ${f2(qs)} м³/ч") }
                H3("Вёдра — ✕ убрать неудачное")
                RunsTable(app.series, st, enabled = !running) { i ->
                    val r = app.series[i]; app.removeRun(i)
                    Toast.makeText(ctx, "Убрано ведро №${i + 1}: ${fv(r.v)} л за ${Core.fmtDur(r.t)}", Toast.LENGTH_SHORT).show()
                }
                if (st.n > 1) {
                    Spacer(Modifier.height(8.dp))
                    KV(listOf(f2(st.min.m3h) to "мин, м³/ч", f2(st.max.m3h) to "макс, м³/ч", f2(st.mean.m3h) to "среднее скоростей", f1(st.cv * 100) + "%" to "разброс (CV)"))
                }
            }
        }
        SaveCard(app, st)
        Small("QDebit v${BuildConfig.VERSION_NAME}", color = Q.muted, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}

@Composable
private fun ManualEntry(app: AppState, vol: Double?) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var mv by remember { mutableStateOf("") }
    var mt by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Ручной ввод (засекал другим секундомером)") }
    if (open) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFieldQ("Объём, л", mv, { mv = it }, Modifier.weight(1f), placeholder = vol?.let { fv(it) }, kb = androidx.compose.ui.text.input.KeyboardType.Decimal)
        TextFieldQ("Время", mt, { mt = it }, Modifier.weight(1.2f), placeholder = "12.4 / 1:05.3")
        Button(onClick = {
            val v = parseNum(mv) ?: vol; val t = parseTime(mt)
            if (v == null || v <= 0 || t == null || t <= 0) { Toast.makeText(ctx, "Нужны объём и время", Toast.LENGTH_SHORT).show(); return@Button }
            app.addRun(v, t); mt = ""
            Toast.makeText(ctx, "Добавлено", Toast.LENGTH_SHORT).show()
        }) { Text("+") }
    }
}

@Composable
private fun SaveCard(app: AppState, st: Core.Stats?) {
    val ctx = LocalContext.current
    var dyn by remember { mutableStateOf("") }
    var cav by remember { mutableStateOf<Int?>(null) }
    var press by remember { mutableStateOf("") }
    var pressAt by remember { mutableStateOf("house") }
    var min by remember { mutableStateOf("") }
    var src by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    QCard("Сохранить замер") {
        Select("Привязать к скважине", listOf<Pair<String?, String>>(null to "— без привязки (просто померить) —") + app.liveWells.map { it.id to it.name },
            app.s.measWell, { v -> app.updateLocal { it.copy(measWell = v) } }, Modifier.fillMaxWidth())
        Small("Без привязки — кран, родник, чужая скважина. Привязку можно поменять потом в «Истории».", color = Q.muted, modifier = Modifier.padding(bottom = 6.dp))
        // кавитацию отмечают у поверхностных насосов (абиссинка, высокая статика); динамики у абиссинки нет
        val bw = app.wellById(app.s.measWell)
        val showCav = bw != null && Core.installOf(bw.well) != "sub"
        val showDyn = !(bw != null && Core.isAbyss(bw.well))
        FieldGrid(
            { m -> if (showDyn) TextFieldQ("Дин. уровень, м", dyn, { dyn = it }, m, kb = androidx.compose.ui.text.input.KeyboardType.Decimal)
                   else Select("Кавитация", listOf<Pair<Int?, String>>(null to "— не отмечал —") + Core.CAV_NAMES.entries.map { it.key to it.value }, cav, { cav = it }, m) },
            { m -> TextFieldQ("Откачка, мин", min, { min = it }, m, kb = androidx.compose.ui.text.input.KeyboardType.Decimal) },
        )
        // погружной: давление на манометре во время замера → динамика по кривой насоса (без уровнемера)
        val showP = bw != null && Core.installOf(bw.well) == "sub"
        if (showP) FieldGrid(
            { m -> TextFieldQ("Давление на манометре, бар", press, { press = it }, m, kb = androidx.compose.ui.text.input.KeyboardType.Decimal) },
            { m -> Select("Манометр стоит", listOf("house" to "в доме / у реле", "head" to "у оголовка"), pressAt, { pressAt = it }, m) },
        )
        if (showP) Small("Динамика посчитается по кривой насоса скважины. SQE: годится, только если давление ниже уставки CU 301.", color = Q.muted)
        if (showCav && showDyn) Select("Кавитация (шум/пузыри в насосе)", listOf<Pair<Int?, String>>(null to "— не отмечал —") + Core.CAV_NAMES.entries.map { it.key to it.value }, cav, { cav = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
        TextFieldQ("Насос / кран", src, { src = it }, Modifier.fillMaxWidth(), placeholder = "ECO 4-142, кран открыт")
        TextFieldQ("Заметка", note, { note = it }, Modifier.fillMaxWidth(), placeholder = "после 30 мин откачки, вода чистая")
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = st != null, onClick = {
                val s = st ?: return@Button
                val m = Measurement(
                    id = org.qdebit.android.newId(),
                    ts = System.currentTimeMillis(), runs = app.series,
                    q = Math.round(s.avg.m3h * 10000) / 10000.0, cv = Math.round(s.cv * 10000) / 10000.0, err = Math.round(s.errSeries * 10000) / 10000.0,
                    dynLevel = if (showDyn) parseNum(dyn) else null, cav = if (showCav) cav else null,
                    press = if (showP) parseNum(press) else null, pressAt = if (showP && parseNum(press) != null) pressAt else null, pumpingMin = parseNum(min), src = src.trim().ifEmpty { null }, note = note.trim().ifEmpty { null },
                    app = "android-" + BuildConfig.VERSION_NAME, wellId = app.s.measWell,
                )
                app.addMeasurement(m); dyn = ""; min = ""; note = ""; cav = null; press = ""
                hint = "Серия осталась на экране — «новая серия», чтобы начать заново"
                Toast.makeText(ctx, "Сохранено: ${f2(m.q)} м³/ч" + (app.wellById(m.wellId)?.let { " → ${it.name}" } ?: " (без привязки)"), Toast.LENGTH_SHORT).show()
            }) { Text("💾 Сохранить в историю") }
        }
        if (hint.isNotEmpty()) Small(hint, color = Q.muted, modifier = Modifier.padding(top = 6.dp))
    }
}


/** таблица вёдер с ✕ на каждой строке (в серии и в правке сохранённого замера) */
@Composable
fun RunsTable(runs: List<org.qdebit.android.Run>, st: Core.Stats?, enabled: Boolean = true, onRemove: (Int) -> Unit) {
    val mean = st?.mean?.lps ?: 0.0
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("#" to 0.5f, "V, л" to 0.8f, "t" to 1.2f, "м³/ч" to 1f, "откл." to 1f).forEach { (h, w) ->
                Text(h, fontSize = 11.5.sp, color = Q.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(w), textAlign = if (h == "#") TextAlign.Start else TextAlign.End)
            }
            Spacer(Modifier.width(44.dp))
        }
        runs.forEachIndexed { i, r ->
            val lps = r.v / r.t
            val dev = if (runs.size > 1 && mean > 0) (if (lps / mean - 1 >= 0) "+" else "") + f1((lps / mean - 1) * 100) + "%" else "—"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 13.5.sp, modifier = Modifier.weight(0.5f))
                Text(fv(r.v), fontSize = 13.5.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text(Core.fmtDur(r.t), fontSize = 13.5.sp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
                Text(f2(Core.Units(lps).m3h), fontSize = 13.5.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(dev, fontSize = 13.5.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                TextButton(onClick = { onRemove(i) }, enabled = enabled, modifier = Modifier.width(44.dp)) { Text("✕", color = Q.crit) }
            }
            androidx.compose.material3.HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
