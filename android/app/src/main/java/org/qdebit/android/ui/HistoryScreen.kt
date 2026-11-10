package org.qdebit.android.ui

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import org.qdebit.android.Core.fv
import org.qdebit.android.Measurement
import org.qdebit.android.recomputed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val DATE_FMT = SimpleDateFormat("dd.MM.yy HH:mm", Locale.forLanguageTag("ru"))

/** onExport(kind): "csv" | "json"; onImport(): открыть файл */
@Composable
fun HistoryScreen(app: AppState, onExport: (String) -> Unit, onImport: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }        // all | none | <wellId>
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    if (filter != "all" && filter != "none" && app.wellById(filter) == null) filter = "all"
    val all = app.measLive
    val list = all.filter { m ->
        when (filter) { "all" -> true; "none" -> app.wellById(m.wellId) == null; else -> m.wellId == filter }
    }
    val fw = app.wellById(filter)
    fun cnt(id: String) = all.count { if (id == "none") app.wellById(it.wellId) == null else it.wellId == id }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        QCard("Дебит по замерам" + (fw?.let { " · ${it.name}" } ?: "")) { HistoryChart(list, fw?.target?.q) }
        QCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Замеры", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Small(if (list.size != all.size) "  · ${list.size} из ${all.size}" else if (all.isNotEmpty()) "  · ${all.size}" else "")
            }
            Select("Скважина", listOf("all" to "все скважины (${all.size})", "none" to "без привязки (${cnt("none")})") +
                app.liveWells.map { it.id to "${it.name} (${cnt(it.id)})" }, filter, { filter = it; editId = null }, Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onExport("csv") }, enabled = all.isNotEmpty()) { Text("CSV") }
                OutlinedButton(onClick = { onExport("json") }) { Text("Экспорт JSON") }
                OutlinedButton(onClick = onImport) { Text("Импорт") }
            }
            if (list.isEmpty()) Small(if (all.isEmpty()) "Пока пусто. Сохрани замер на вкладке «Замер»." else "Под фильтр ничего не попало.")
            list.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MeasRow(app, m, editId == m.id, onEdit = { editId = if (editId == m.id) null else m.id })
            }
        }
    }
}

@Composable
private fun MeasRow(app: AppState, m: Measurement, editing: Boolean, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var arm by remember { mutableStateOf(false) }
    val mw = app.wellById(m.wellId)
    val tq = mw?.target?.q
    val V = m.runs.sumOf { it.v }; val T = m.runs.sumOf { it.t }
    val meta = listOfNotNull(
        "${m.runs.size} вёдер, ${fv(V)} л за ${Core.fmtDur(T)}",
        m.cv?.takeIf { it > 0 }?.let { "разброс ${f1(it * 100)}%" },
        m.err?.takeIf { it > 0 }?.let { "±${f1(it * 100)}%" },
        m.dynLevel?.let { "дин. ${f1(it)} м" },
        m.cav?.let { "кавитация: ${Core.CAV_NAMES[it]}" },
        m.press?.let { "манометр ${f1(it)} бар (${if (m.pressAt == "head") "оголовок" else "дом"})" },
        m.pumpingMin?.let { "откачка ${f0(it)} мин" },
        m.src,
    ).joinToString(" · ")
    Column(Modifier.padding(vertical = 10.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f2(m.q), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Small(" м³/ч  ${f1(m.q * 1000 / 60)} л/мин  ")
                    if (tq != null && tq > 0) Badge(if (m.q >= tq) NoteKind.OK else NoteKind.WARN, "${f0(m.q / tq * 100)}%")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Small(DATE_FMT.format(Date(m.ts)) + " · " + (mw?.let { "🕳 ${it.name}" } ?: "без привязки"))
                    if (mw?.refId == m.id) { Spacer(Modifier.width(6.dp)); Badge(NoteKind.OK, "опорный") }
                }
                Small(meta, color = Q.muted)
                m.note?.let { Text(it, fontSize = 13.sp) }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onEdit) { Text(if (editing) "свернуть" else "править") }
                TextButton(onClick = {
                    val w = app.makeReference(m)
                    Toast.makeText(ctx, "«${w.name}»: ${f2(m.q)} м³/ч" + (m.dynLevel?.let { " при уровне ${f1(it)} м" } ?: " (уровень не указан — проверь «Скважину»)") +
                        (if (mw == null) " — замер без привязки, взял активную" else ""), Toast.LENGTH_LONG).show()
                }) { Text("опорный") }
                TextButton(onClick = {
                    app.loadSeries(m.runs); if (mw != null) app.updateLocal { it.copy(measWell = mw.id) }; app.tab = 0
                    Toast.makeText(ctx, "Серия загружена — можно добавить вёдра", Toast.LENGTH_SHORT).show()
                }) { Text("в серию") }
                TextButton(onClick = {
                    if (!arm) { arm = true; scope.launch { delay(2500); arm = false }; return@TextButton }
                    app.deleteMeasurement(m.id); Toast.makeText(ctx, "Удалено", Toast.LENGTH_SHORT).show()
                }) { Text(if (arm) "точно?" else "удалить", color = Q.crit) }
            }
        }
        if (editing) MeasEditor(app, m, onDone = onEdit)
    }
}

/** правка сохранённого замера: убрать вёдра, привязка, уровни, заметки — сводка пересчитывается */
@Composable
private fun MeasEditor(app: AppState, m: Measurement, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var runs by remember(m.id) { mutableStateOf(m.runs) }
    var wellId by remember(m.id) { mutableStateOf(m.wellId?.takeIf { app.wellById(it) != null }) }
    var dyn by remember(m.id) { mutableStateOf(numText(m.dynLevel)) }
    var cav by remember(m.id) { mutableStateOf(m.cav) }
    var press by remember(m.id) { mutableStateOf(numText(m.press)) }
    var pressAt by remember(m.id) { mutableStateOf(m.pressAt ?: "house") }
    var min by remember(m.id) { mutableStateOf(numText(m.pumpingMin)) }
    var src by remember(m.id) { mutableStateOf(m.src ?: "") }
    var note by remember(m.id) { mutableStateOf(m.note ?: "") }
    val st = Core.seriesStats(runs, app.s.reaction)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Small("Вёдра (✕ — убрать неудачное, сводка пересчитается)")
        RunsTable(runs, st) { i -> runs = runs.filterIndexed { j, _ -> j != i } }
        Small("Станет: " + (st?.let { "${f2(it.avg.m3h)} м³/ч" + if (it.n > 1) ", разброс ${f1(it.cv * 100)}%" else "" } ?: "— (нет вёдер)"),
            weight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 6.dp))
        Select("Скважина", listOf<Pair<String?, String>>(null to "без привязки") + app.liveWells.map { it.id to it.name }, wellId, { wellId = it }, Modifier.fillMaxWidth())
        FieldGrid(
            { md -> TextFieldQ("Дин. уровень, м", dyn, { dyn = it }, md, kb = KeyboardType.Decimal) },
            { md -> TextFieldQ("Откачка, мин", min, { min = it }, md, kb = KeyboardType.Decimal) },
        )
        FieldGrid(
            { md -> TextFieldQ("Давление, бар", press, { press = it }, md, kb = KeyboardType.Decimal) },
            { md -> Select("Манометр", listOf("house" to "в доме / у реле", "head" to "у оголовка"), pressAt, { pressAt = it }, md) },
        )
        Select("Кавитация", listOf<Pair<Int?, String>>(null to "— не отмечал —") + Core.CAV_NAMES.entries.map { it.key to it.value }, cav, { cav = it }, Modifier.fillMaxWidth().padding(top = 4.dp))
        TextFieldQ("Насос / кран", src, { src = it }, Modifier.fillMaxWidth())
        TextFieldQ("Заметка", note, { note = it }, Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = st != null, onClick = {
                val nm = m.copy(runs = runs, wellId = wellId, dynLevel = parseNum(dyn), cav = cav, press = parseNum(press), pressAt = if (parseNum(press) != null) pressAt else null, pumpingMin = parseNum(min),
                    src = src.trim().ifEmpty { null }, note = note.trim().ifEmpty { null }).recomputed(app.s.reaction)
                app.updateMeasurement(nm); onDone()
                Toast.makeText(ctx, "Замер обновлён: ${f2(nm.q)} м³/ч", Toast.LENGTH_SHORT).show()
            }) { Text("Сохранить") }
            OutlinedButton(onClick = onDone) { Text("Отмена") }
        }
    }
}

@Composable
fun ImportDialog(info: AppState.ImportInfo, onDone: (Boolean?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Импорт") },
        text = {
            Text("Новых замеров: ${info.newMeas} из ${info.backup.measurements.size}" +
                (info.backup.settings?.let { ", скважин в файле: ${info.wells}, своих насосов: ${it.pumps.count { p -> !p.deleted }}" } ?: "") +
                ".\n\n«Добавить» — замеры, скважины и насосы, которых нет. «Заменить» — настройки и база скважин из файла целиком.")
        },
        confirmButton = { TextButton(onClick = { onDone(false) }) { Text("Добавить") } },
        dismissButton = {
            Row {
                if (info.backup.settings != null) TextButton(onClick = { onDone(true) }) { Text("Заменить") }
                TextButton(onClick = { onDone(null) }) { Text("Отмена") }
            }
        },
    )
}
