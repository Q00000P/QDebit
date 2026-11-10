package org.qdebit.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/** Всё состояние приложения. Хранение — один JSON-файл в filesDir, запись атомарно (tmp + rename) в фоне.
 *  Удаления — «надгробия» (deleted=true + updatedAt), чтобы синк разнёс их по устройствам. */
class AppState(context: Context) {
    private val file = File(context.filesDir, "qdebit.json")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var s by mutableStateOf(migrate(Settings()))
        private set
    /** все замеры, включая надгробия; в UI — [measLive] */
    var meas by mutableStateOf(listOf<Measurement>())
        private set
    var series by mutableStateOf(listOf<Run>())
        private set
    var sync by mutableStateOf(SyncConfig())
        private set
    var tab by mutableIntStateOf(0)

    // ---- статус синка (для экрана и значка в шапке) ----
    var syncBusy by mutableStateOf(false)
    var syncOk by mutableStateOf<Boolean?>(null)
    var syncLog by mutableStateOf(listOf<String>())

    val engine = InfluxSync(this)

    init {
        runCatching {
            if (file.exists()) {
                val d = QJson.decodeFromString(Saved.serializer(), file.readText())
                s = migrate(d.settings); meas = d.measurements.sortedByDescending { it.ts }; series = d.series; sync = d.sync
            }
        }
        main.post { if (refreshFits()) persist(); ensureElevations() }
    }

    private fun persist(changed: Boolean = true) {
        val snap = Saved(s, meas, series, sync)
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(QJson.encodeToString(Saved.serializer(), snap))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
        if (changed) engine.schedule()
    }

    fun onMain(f: () -> Unit) { main.post { f() } }
    private fun now() = System.currentTimeMillis()

    // ---- глобальные настройки (пресеты, реакция) ----
    fun update(f: (Settings) -> Settings) { s = migrate(f(s)); persist() }
    fun updatePrefs(f: (Settings) -> Settings) { s = migrate(f(s)).copy(prefsUpdatedAt = now()); persist() }
    /** локальные мелочи без синка (громкость, последний выбор привязки) */
    fun updateLocal(f: (Settings) -> Settings) { s = migrate(f(s)); persist(changed = false) }

    // ---- скважины ----
    val liveWells: List<WellProfile> get() = s.wells.filter { !it.deleted }
    fun wellById(id: String?): WellProfile? = id?.let { i -> s.wells.firstOrNull { it.id == i && !it.deleted } }
    /** активная скважина — для вкладок «Скважина» и «Насос» */
    val W: WellProfile get() = wellById(s.activeWell) ?: liveWells.first()

    fun updateWell(f: (WellProfile) -> WellProfile) = updateWellById(W.id, f)
    fun updateWellById(id: String, f: (WellProfile) -> WellProfile) {
        s = s.copy(wells = s.wells.map { if (it.id == id) f(it).copy(updatedAt = now()) else it }); refreshFits(); persist()
        ensureElevations()
    }

    // ---- отметки устья по модели рельефа (по координатам скважин) ----
    var elevStatus by mutableStateOf("")
    private val elevExec = Executors.newSingleThreadExecutor()
    @Volatile private var elevBusy = false
    private var elevFailAt = 0L
    /** подтянуть отметку рельефа для скважин с координатами, у которых её нет или координаты сдвинулись */
    fun ensureElevations(force: Boolean = false) {
        if (elevBusy || (!force && now() - elevFailAt < 5 * 60_000)) return
        val need = s.wells.filter { !it.deleted && it.lat != null && it.lon != null &&
            (it.well.elevDem == null || it.well.elevDemLat != it.lat || it.well.elevDemLon != it.lon) }
        if (need.isEmpty()) return
        elevBusy = true; elevStatus = "рельеф: запрашиваю ${need.size}…"
        elevExec.execute {
            val r = runCatching { Elevation.fetch(need.map { it.lat!! to it.lon!! }) }
            main.post {
                elevBusy = false
                val res = r.getOrNull()
                if (res == null) { elevFailAt = now(); elevStatus = "рельеф: нет связи (${r.exceptionOrNull()?.message ?: "ошибка"}) — повторю позже"; return@post }
                elevStatus = "рельеф: ${res.second}"
                val byId = need.mapIndexed { i, w -> w.id to Triple(res.first.getOrNull(i), w.lat, w.lon) }.toMap()
                s = s.copy(wells = s.wells.map { x ->
                    val t = byId[x.id]
                    if (t?.first != null && x.lat == t.second && x.lon == t.third)
                        x.copy(well = x.well.copy(elevDem = Math.round(t.first!! * 10) / 10.0, elevDemLat = t.second, elevDemLon = t.third), updatedAt = now())
                    else x
                }); persist()
            }
        }
    }
    fun setActiveWell(id: String) { if (wellById(id) != null) { s = s.copy(activeWell = id); persist(changed = false) } }
    fun addWell(dupOf: WellProfile? = null): WellProfile {
        val w = if (dupOf != null) newWell(dupOf.name + " (копия)", dupOf) else newWell("Скважина ${liveWells.size + 1}")
        s = s.copy(wells = s.wells + w, activeWell = w.id); persist(); return w
    }
    /** надгробие; замеры этой скважины становятся «без привязки» (wellId остаётся — вернётся, если скважину восстановят синком) */
    fun deleteWell(id: String): Int {
        if (liveWells.size < 2) return -1
        val n = measLive.count { it.wellId == id }
        s = migrate(s.copy(
            wells = s.wells.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it },
            measWell = if (s.measWell == id) null else s.measWell,
        ))
        persist(); return n
    }

    // ---- серия ----
    fun addRun(v: Double, t: Double) { series = series + Run(v, Math.round(t * 100) / 100.0); persist(false) }
    fun removeRun(i: Int) { if (i in series.indices) { series = series.filterIndexed { j, _ -> j != i }; persist(false) } }
    fun undoRun() { if (series.isNotEmpty()) { series = series.dropLast(1); persist(false) } }
    fun resetSeries() { series = emptyList(); persist(false) }
    fun loadSeries(r: List<Run>) { series = r; persist(false) }

    // ---- замеры ----
    val measLive: List<Measurement> get() = meas.filter { !it.deleted }
    fun addMeasurement(m: Measurement) { meas = (listOf(m.copy(updatedAt = now())) + meas).sortedByDescending { it.ts }; refreshFits(); persist() }
    fun updateMeasurement(m: Measurement) { meas = meas.map { if (it.id == m.id) m.copy(updatedAt = now()) else it }; refreshFits(); persist() }

    /** модель скважины по замерам (уровнемер / манометр): B, C → well.fit*; меняем только если изменилось */
    fun refreshFits(): Boolean {
        var ch = false
        val ws = s.wells.map { wp ->
            if (wp.deleted) return@map wp
            val f = Core.fitWell(Core.wellPoints(wp, meas) { pumpById(it) }.first)
            fun r(x: Double, d: Int) = Math.round(x * Math.pow(10.0, d.toDouble())) / Math.pow(10.0, d.toDouble())
            val nw = wp.well.copy(fitB = f?.let { r(it.B, 6) }, fitC = f?.let { r(it.C, 6) }, fitN = f?.n, fitQmax = f?.let { r(it.Qmax, 4) }, fitKind = f?.kind)
            if (nw != wp.well) { ch = true; wp.copy(well = nw, updatedAt = now()) } else wp
        }
        if (ch) s = s.copy(wells = ws)
        return ch
    }
    fun deleteMeasurement(id: String) {
        meas = meas.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it }
        s = s.copy(wells = s.wells.map { if (it.refId == id) it.copy(refId = null, updatedAt = now()) else it })
        refreshFits()
        persist()
    }
    /** сделать замер опорным: для его скважины, либо (без привязки) для активной */
    fun makeReference(m: Measurement): WellProfile {
        val w = wellById(m.wellId) ?: W
        updateWellById(w.id) { it.copy(refId = m.id, well = it.well.copy(dynQ = Math.round(m.q * 1000) / 1000.0, dyn = m.dynLevel ?: it.well.dyn)) }
        return wellById(w.id)!!
    }

    // ---- насосы ----
    val livePumps: List<Pump> get() = s.pumps.filter { !it.deleted }
    fun allPumps(): List<Pump> = livePumps + Catalog.ALL
    fun pumpById(id: String?): Pump? = id?.let { i -> allPumps().firstOrNull { it.id == i } }
    fun savePump(p: Pump) {
        val pp = p.copy(updatedAt = now())
        val i = s.pumps.indexOfFirst { it.id == p.id }
        s = if (i >= 0) s.copy(pumps = s.pumps.toMutableList().also { it[i] = pp })
        else s.copy(pumps = listOf(pp) + s.pumps, wells = s.wells.map { w ->
            if (w.id != W.id) w else { val free = w.chart.indexOf(null); if (free >= 0) w.copy(chart = w.chart.toMutableList().also { it[free] = pp.id }, updatedAt = now()) else w }
        })
        persist()
    }
    fun deletePump(id: String) {
        s = s.copy(
            pumps = s.pumps.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it },
            wells = s.wells.map { w -> if (id in w.chart) w.copy(chart = w.chart.map { if (it == id) null else it }, updatedAt = now()) else w },
        )
        persist()
    }

    /** импорт массива насосов (или {"pumps":[…]}) — добавляются как свои; дубли по имени пропускаются */
    fun importPumps(text: String): Pair<Int, Int> {
        val root = QJson.parseToJsonElement(text)
        val arr = (root as? kotlinx.serialization.json.JsonArray)
            ?: ((root as? kotlinx.serialization.json.JsonObject)?.let { it["pumps"] ?: it["catalog"] } as? kotlinx.serialization.json.JsonArray)
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        var n = 0; var skip = 0
        val names = livePumps.map { it.name }.toMutableSet()
        val add = ArrayList<Pump>()
        for (el in arr) {
            val x = runCatching { QJson.decodeFromJsonElement(ImportPump.serializer(), el) }.getOrNull()
            if (x == null || x.name.isBlank()) { skip++; continue }
            val type = x.type?.takeIf { it in listOf("sub", "surface", "ejector") } ?: "sub"
            var pts = (x.points?.takeIf { it.size >= 2 } ?: x.curves?.firstOrNull()?.points ?: emptyList()).sortedBy { it.firstOrNull() ?: 0.0 }
            var note = listOfNotNull(x.note, x.src).joinToString(" · ").ifEmpty { null }
            var approx = false
            if (pts.size < 2 && x.curves.isNullOrEmpty()) {
                val ap = Core.approxCurve(x.hmax, x.qmax, type, x.qnom, x.hnom, x.hAtQmax)
                if (ap == null) { skip++; continue }
                pts = ap.first; approx = true; note = ap.second + (note?.let { ". $it" } ?: "")
            } else if (x.quality == "graph") note = "снято с графика. " + (note ?: "")
            fun mono(p: List<List<Double>>) = p.zipWithNext().all { (a0, b0) -> b0[1] <= a0[1] }
            if (!mono(pts) || !(x.curves ?: emptyList()).all { mono(it.points.sortedBy { p -> p[0] }) }) { skip++; continue }
            if (x.name in names || Catalog.ALL.any { Core.normName(it.name) == Core.normName(x.name) }) { skip++; continue }
            add += Pump(id = newId("u-"), name = x.name, type = type, points = pts, curves = x.curves, suction = x.suction ?: x.suctionM,
                diam = x.diam ?: x.diamMm, powerKW = x.powerKW ?: x.powerKw2, currentA = x.currentA ?: x.currentA2, minQ = x.minQ ?: x.minQ2,
                note = note, approx = approx, updatedAt = now())
            names += x.name; n++
        }
        if (add.isNotEmpty()) { s = s.copy(pumps = add + s.pumps); persist() }
        return n to skip
    }

    // ---- синк ----
    fun updateSync(f: (SyncConfig) -> SyncConfig) { sync = f(sync); persist(changed = false) }

    /** слияние документов из Influx: побеждает бОльший updatedAt. Возвращает счётчики принятого. */
    fun applyRemote(docs: List<InfluxSync.Doc>): Map<String, Int> {
        val cnt = HashMap<String, Int>()
        var wells = s.wells; var pumps = s.pumps; var ms = meas; var st = s
        for (d in docs) runCatching {
            when (d.kind) {
                "well" -> {
                    val r = QJson.decodeFromString(WellProfile.serializer(), d.json)
                    val l = wells.firstOrNull { it.id == r.id }
                    if (l == null || r.updatedAt > l.updatedAt) { wells = if (l == null) wells + r else wells.map { if (it.id == r.id) r else it }; cnt.merge("скважин", 1, Int::plus) }
                }
                "pump" -> {
                    val r = QJson.decodeFromString(Pump.serializer(), d.json)
                    val l = pumps.firstOrNull { it.id == r.id }
                    if (l == null || r.updatedAt > l.updatedAt) { pumps = if (l == null) pumps + r else pumps.map { if (it.id == r.id) r else it }; cnt.merge("насосов", 1, Int::plus) }
                }
                "meas" -> {
                    val r = QJson.decodeFromString(Measurement.serializer(), d.json)
                    val l = ms.firstOrNull { it.id == r.id }
                    if (l == null || r.updatedAt > l.updatedAt) { ms = if (l == null) ms + r else ms.map { if (it.id == r.id) r else it }; cnt.merge("замеров", 1, Int::plus) }
                }
                "prefs" -> {
                    val r = QJson.decodeFromString(Prefs.serializer(), d.json)
                    if (r.updatedAt > st.prefsUpdatedAt) { st = st.copy(presets = r.presets, reaction = r.reaction, prefsUpdatedAt = r.updatedAt); cnt.merge("настроек", 1, Int::plus) }
                }
            }
        }
        if (cnt.isNotEmpty()) {
            s = migrate(st.copy(wells = wells, pumps = pumps)); meas = ms.sortedByDescending { it.ts }
            val fitCh = refreshFits()
            persist(changed = fitCh)
        }
        return cnt
    }

    // ---- экспорт / импорт (формат веб-версии) ----
    fun exportJson(): String = QJson.encodeToString(
        Backup.serializer(),
        Backup(v = BuildConfig.VERSION_NAME, exportedAt = Instant.now().toString(), settings = s, measurements = measLive),
    )

    fun exportCsv(): String {
        val rows = ArrayList<List<String>>()
        rows += listOf("дата", "скважина", "м3/ч", "л/мин", "вёдер", "объём_л", "время_с", "разброс_%", "погрешность_%", "дин_уровень_м", "манометр_бар", "манометр_где", "кавитация", "откачка_мин", "насос", "заметка", "вёдра(V/t)")
        fun f(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x)
        for (m in measLive) {
            val V = m.runs.sumOf { it.v }; val T = m.runs.sumOf { it.t }
            rows += listOf(
                Instant.ofEpochMilli(m.ts).toString(), wellById(m.wellId)?.name ?: "", f(m.q, 3), f(m.q * 1000 / 60, 2), m.runs.size.toString(),
                f(V, 1), f(T, 2), m.cv?.let { f(it * 100, 2) } ?: "", m.err?.let { f(it * 100, 2) } ?: "",
                m.dynLevel?.toString() ?: "", m.press?.toString() ?: "", m.press?.let { if (m.pressAt == "head") "оголовок" else "дом" } ?: "", m.cav?.let { Core.CAV_NAMES[it] } ?: "", m.pumpingMin?.toString() ?: "", m.src ?: "", m.note ?: "",
                m.runs.joinToString(" ") { "${it.v}/${it.t}" },
            )
        }
        return "﻿" + rows.joinToString("\r\n") { r ->
            r.joinToString(";") { c -> if (c.contains(';') || c.contains('"') || c.contains('\n')) "\"" + c.replace("\"", "\"\"") + "\"" else c }
        }
    }

    data class ImportInfo(val backup: Backup, val newMeas: Int, val wells: Int)

    fun parseImport(text: String): ImportInfo {
        val b = QJson.decodeFromString(Backup.serializer(), text)
        val have = meas.map { it.id }.toSet()
        val inc = b.settings?.let { migrate(it) }
        return ImportInfo(b.copy(settings = inc), b.measurements.count { it.id !in have && it.runs.isNotEmpty() }, inc?.wells?.count { !it.deleted } ?: 0)
    }

    fun applyImport(b: Backup, full: Boolean): Int {
        val have = meas.map { it.id }.toSet()
        val add = b.measurements.filter { it.id !in have && it.runs.isNotEmpty() }.map { if (it.updatedAt == 0L) it.copy(updatedAt = it.ts) else it }
        meas = (meas + add).sortedByDescending { it.ts }
        b.settings?.let { inc ->
            s = if (full) migrate(inc.copy(prefsUpdatedAt = now(), wells = inc.wells.map { it.copy(updatedAt = maxOf(it.updatedAt, now())) }))
            else {
                val pids = s.pumps.map { it.id }.toSet(); val wids = s.wells.map { it.id }.toSet()
                migrate(s.copy(
                    pumps = s.pumps + inc.pumps.filter { it.id !in pids && !it.catalog && !it.deleted }.map { it.copy(updatedAt = now()) },
                    wells = s.wells + inc.wells.filter { it.id !in wids && !it.deleted }.map { it.copy(updatedAt = now()) },
                ))
            }
        }
        refreshFits()
        persist()
        return add.size
    }
}

@kotlinx.serialization.Serializable
data class Prefs(val presets: List<Double>, val reaction: Double, val updatedAt: Long)
