package org.qdebit.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Синк через InfluxDB — без своего сервера.
 *
 * qdebit_doc — хранилище документов: теги kind (well|meas|pump|prefs), id; поля json (весь объект), updated, deleted.
 *   Время точки = updatedAt, поэтому last() по (kind,id) = последняя версия (LWW). Удаление — надгробие deleted=true.
 * qdebit_flow — для Grafana: время = момент замера; теги id, well (id|none), well_name; поля q (м³/ч), lpm, lps, n, cv, err, dyn, pump_min.
 *
 * Режимы:
 *   v1 — POST /write?db=…&precision=ms, GET /query (InfluxQL), Basic-авторизация. Это InfluxDB 1.x (аддон HA),
 *        а также v1-совместимый API в 2.x (нужен DBRP-маппинг) и 3.x Core.
 *   v2 — POST /api/v2/write?org=…&bucket=…&precision=ms, POST /api/v2/query (Flux), заголовок Authorization: Token …
 */
class InfluxSync(private val app: AppState) {
    data class Doc(val kind: String, val id: String, val json: String)
    class HttpErr(val code: Int, msg: String) : Exception(msg)

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------- расписание ----------
    /** после каждой правки — через 4 с (сгребает пачку правок в один синк) */
    fun schedule() {
        val c = app.sync
        if (!c.enabled || !c.auto || c.url.isBlank()) return
        synchronized(this) { pending?.cancel(false); pending = exec.schedule(Runnable { doSync(full = false) }, 4, TimeUnit.SECONDS) }
    }
    fun syncNow(full: Boolean = false) { exec.execute { doSync(full) } }
    fun test() { exec.execute { runTest() } }

    private fun log(vararg lines: String) {
        val stamp = tf.format(Date())
        app.onMain { app.syncLog = (lines.map { "$stamp  $it" } + app.syncLog).take(60) }
    }

    // ---------- основной цикл: push изменённого → pull всего → слияние ----------
    private fun doSync(full: Boolean) {
        val c = app.sync
        if (c.url.isBlank()) { log("✕ не задан адрес InfluxDB"); return }
        app.onMain { app.syncBusy = true }
        val t0 = System.currentTimeMillis()
        try {
            // снимок данных — в главном потоке не нужен: читаем неизменяемые списки
            val since = if (full) -1L else c.lastPushAt
            val s = app.s
            val docs = ArrayList<Pair<Doc, Long>>()
            s.wells.filter { it.updatedAt > since }.forEach { docs += Doc("well", it.id, QJson.encodeToString(WellProfile.serializer(), it)) to it.updatedAt }
            s.pumps.filter { it.updatedAt > since }.forEach { docs += Doc("pump", it.id, QJson.encodeToString(Pump.serializer(), it)) to it.updatedAt }
            app.meas.filter { it.updatedAt > since }.forEach { docs += Doc("meas", it.id, QJson.encodeToString(Measurement.serializer(), it)) to it.updatedAt }
            if (s.prefsUpdatedAt > since && s.prefsUpdatedAt > 0)
                docs += Doc("prefs", "global", QJson.encodeToString(Prefs.serializer(), Prefs(s.presets, s.reaction, s.prefsUpdatedAt))) to s.prefsUpdatedAt

            var maxPushed = c.lastPushAt
            if (docs.isNotEmpty()) {
                val lines = docs.map { (d, upd) -> docLine(d, upd, deleted = d.json.contains("\"deleted\":true")) }
                // точки для Grafana: сначала стираем старые точки изменённых/удалённых замеров (могла смениться скважина)
                val changedMeas = app.meas.filter { it.updatedAt > since }
                // полная отправка: одна чистка всего qdebit_flow; обычная — только правленые/удалённые замеры
                val toDelete = if (full) emptyList() else changedMeas.filter { it.deleted || it.updatedAt - it.ts > 5_000 }
                var delErr = 0
                if (full) runCatching { deleteFlow(c, null) }.onFailure { delErr++ }
                for (m in toDelete) runCatching { deleteFlow(c, m.id) }.onFailure { delErr++ }
                val flow = changedMeas.filter { !it.deleted && it.q > 0 }.map { flowLine(it) }
                (lines + flow).chunked(500).forEach { write(c, it.joinToString("\n")) }
                maxPushed = maxOf(maxPushed, docs.maxOf { it.second })
                val byKind = docs.groupingBy { it.first.kind }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
                log("↑ отправлено${if (full) " (всё)" else ""}: $byKind; точек для Grafana: ${flow.size}" +
                    (if (toDelete.isNotEmpty()) "; перезаписано старых: ${toDelete.size}" else "") +
                    (if (delErr > 0) "; ошибок удаления: $delErr (сервер не умеет DELETE? старые точки в Grafana могут задвоиться)" else ""))
            }
            val remote = pull(c)
            val got = app.applyRemoteBlocking(remote)
            app.updateSyncBlocking { it.copy(lastPushAt = maxPushed, lastSyncAt = System.currentTimeMillis()) }
            val dt = System.currentTimeMillis() - t0
            log("↓ на сервере документов: ${remote.size}; принято: " + (if (got.isEmpty()) "ничего нового" else got.entries.joinToString(", ") { "${it.key} ${it.value}" }) + " · ${dt} мс")
            app.onMain { app.syncOk = true }
        } catch (e: HttpErr) {
            log("✕ HTTP ${e.code}: ${e.message}" + hint(e.code))
            app.onMain { app.syncOk = false }
        } catch (e: Exception) {
            log("✕ ${e.javaClass.simpleName}: ${e.message ?: ""}" + netHint(e))
            app.onMain { app.syncOk = false }
        } finally {
            app.onMain { app.syncBusy = false }
        }
    }

    private fun runTest() {
        val c = app.sync
        app.onMain { app.syncBusy = true }
        try {
            val base = c.url.trimEnd('/')
            val ping = http("GET", "$base/ping", null, emptyMap(), c, auth = false)
            val ver = ping.headers["X-Influxdb-Version"] ?: ping.headers["x-influxdb-version"]
            val build = ping.headers["X-Influxdb-Build"] ?: ping.headers["x-influxdb-build"]
            log("✓ /ping: HTTP ${ping.code}, версия сервера: ${ver ?: "не сообщил"}${build?.let { " ($it)" } ?: ""}" +
                (ver?.let { v -> when { v.startsWith("1.") -> " → это 1.x, режим v1" ; v.startsWith("2.") -> " → это 2.x: режим v2 (или v1 при DBRP-маппинге)"; v.startsWith("3") -> " → это 3.x: режим v1"; else -> "" } } ?: ""))
            val docs = pull(c)
            log("✓ чтение: документов QDebit на сервере ${docs.size}" + if (docs.isEmpty()) " (пусто — нормально для первого раза)" else "")
            write(c, "qdebit_ping,device=android ok=1i ${System.currentTimeMillis()}")
            log("✓ запись работает — можно включать синк")
            app.onMain { app.syncOk = true }
        } catch (e: HttpErr) {
            log("✕ HTTP ${e.code}: ${e.message}" + hint(e.code)); app.onMain { app.syncOk = false }
        } catch (e: Exception) {
            log("✕ ${e.javaClass.simpleName}: ${e.message ?: ""}" + netHint(e)); app.onMain { app.syncOk = false }
        } finally { app.onMain { app.syncBusy = false } }
    }

    private fun hint(code: Int) = when (code) {
        401, 403 -> " — неверный логин/пароль/токен или нет прав на базу"
        404 -> " — не тот режим (v1/v2) или нет базы/бакета; для 2.x в режиме v1 нужен DBRP-маппинг"
        400 -> " — сервер не понял запрос (часто: не та база/бакет/org)"
        else -> ""
    }
    private fun netHint(e: Exception) = when (e) {
        is java.net.UnknownHostException -> " — имя не резолвится (снаружи дома нужен VPN или KeenDNS)"
        is java.net.ConnectException -> " — порт закрыт/сервис не слушает (8086?), или телефон не в домашней сети/VPN"
        is java.net.SocketTimeoutException -> " — таймаут: адрес недоступен из текущей сети"
        is javax.net.ssl.SSLException -> " — проблема TLS: для http://… укажи http, для самоподписанного https не подойдёт"
        else -> ""
    }

    // ---------- протокол ----------
    private fun escTag(v: String) = v.ifEmpty { "none" }.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ")
    private fun escStr(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun num(x: Double) = if (x.isFinite()) x.toString() else "0"

    fun docLine(d: Doc, updated: Long, deleted: Boolean) =
        "qdebit_doc,kind=${escTag(d.kind)},id=${escTag(d.id)} json=${escStr(d.json)},updated=${updated}i,deleted=$deleted $updated"

    fun flowLine(m: Measurement): String {
        val w = app.wellById(m.wellId)
        val f = ArrayList<String>()
        f += "q=${num(m.q)}"; f += "lpm=${num(m.q * 1000 / 60)}"; f += "lps=${num(m.q / 3.6)}"; f += "n=${m.runs.size}i"
        m.cv?.let { f += "cv=${num(it * 100)}" }; m.err?.let { f += "err=${num(it * 100)}" }
        m.dynLevel?.let { f += "dyn=${num(it)}" }; m.cav?.let { f += "cav=${it}i" }; m.press?.let { f += "press=${num(it)}" }; m.pumpingMin?.let { f += "pump_min=${num(it)}" }
        m.note?.takeIf { it.isNotBlank() }?.let { f += "note=${escStr(it)}" }
        return "qdebit_flow,id=${escTag(m.id)},well=${escTag(w?.id ?: "none")},well_name=${escTag(w?.name ?: "без привязки")} ${f.joinToString(",")} ${m.ts}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun write(c: SyncConfig, body: String) {
        val base = c.url.trimEnd('/')
        val url = if (c.mode == "v2") "$base/api/v2/write?org=${enc(c.org)}&bucket=${enc(c.bucket)}&precision=ms"
        else "$base/write?db=${enc(c.db)}&precision=ms"
        val r = http("POST", url, body, mapOf("Content-Type" to "text/plain; charset=utf-8"), c)
        if (r.code !in 200..299) throw HttpErr(r.code, r.body.take(300))
    }

    /** id = null — стереть все точки qdebit_flow (перед полной перезаливкой) */
    private fun deleteFlow(c: SyncConfig, id: String?) {
        val base = c.url.trimEnd('/')
        if (c.mode == "v2") {
            val pred = "_measurement=\\\"qdebit_flow\\\"" + (id?.let { " AND id=\\\"${it.replace("\"", "")}\\\"" } ?: "")
            val body = """{"start":"1970-01-01T00:00:00Z","stop":"2200-01-01T00:00:00Z","predicate":"$pred"}"""
            val r = http("POST", "$base/api/v2/delete?org=${enc(c.org)}&bucket=${enc(c.bucket)}", body, mapOf("Content-Type" to "application/json"), c)
            if (r.code !in 200..299) throw HttpErr(r.code, r.body.take(200))
        } else {
            val q = "DELETE FROM \"qdebit_flow\"" + (id?.let { " WHERE \"id\"='${it.replace("'", "")}'" } ?: "")
            val r = http("POST", "$base/query?db=${enc(c.db)}&q=${enc(q)}", "", mapOf("Content-Type" to "application/x-www-form-urlencoded"), c)
            if (r.code !in 200..299 || r.body.contains("\"error\"")) throw HttpErr(r.code, r.body.take(200))
        }
    }

    fun pull(c: SyncConfig): List<Doc> {
        val base = c.url.trimEnd('/')
        return if (c.mode == "v2") {
            val flux = """from(bucket: "${c.bucket.replace("\"", "")}") |> range(start: 0) |> filter(fn: (r) => r._measurement == "qdebit_doc" and r._field == "json") |> group(columns: ["kind", "id"]) |> last() |> keep(columns: ["kind", "id", "_value"])"""
            val r = http("POST", "$base/api/v2/query?org=${enc(c.org)}", flux,
                mapOf("Content-Type" to "application/vnd.flux", "Accept" to "application/csv"), c)
            if (r.code !in 200..299) throw HttpErr(r.code, r.body.take(300))
            parseFluxCsv(r.body)
        } else {
            val q = "SELECT last(\"json\") AS \"json\" FROM \"qdebit_doc\" GROUP BY \"kind\",\"id\""
            val r = http("GET", "$base/query?db=${enc(c.db)}&epoch=ms&q=${enc(q)}", null, emptyMap(), c)
            if (r.code !in 200..299) throw HttpErr(r.code, r.body.take(300))
            parseInfluxQl(r.body)
        }
    }

    companion object {
        fun parseInfluxQl(body: String): List<Doc> {
            val root = QJson.parseToJsonElement(body).jsonObject
            root["error"]?.let { throw HttpErr(200, it.jsonPrimitive.content) }
            val res = (root["results"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
            res["error"]?.let { throw HttpErr(200, it.jsonPrimitive.content) }
            val series = res["series"] as? JsonArray ?: return emptyList()
            return series.mapNotNull { sEl ->
                val so = sEl.jsonObject
                val tags = so["tags"] as? JsonObject ?: return@mapNotNull null
                val cols = so["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
                val row = so["values"]?.jsonArray?.firstOrNull()?.jsonArray ?: return@mapNotNull null
                val ji = cols.indexOf("json"); if (ji < 0) return@mapNotNull null
                val json = row[ji].jsonPrimitive.content
                Doc(tags["kind"]!!.jsonPrimitive.content, tags["id"]!!.jsonPrimitive.content, json)
            }
        }

        /** annotated/plain CSV ответа Flux: несколько таблиц, у каждой своя строка заголовка */
        fun parseFluxCsv(body: String): List<Doc> {
            val out = ArrayList<Doc>()
            var header: List<String>? = null
            for (row in csvRows(body)) {
                if (row.isEmpty() || (row.size == 1 && row[0].isBlank())) { header = null; continue }
                if (row[0].startsWith("#")) { header = null; continue }
                if (header == null) { header = row; continue }
                val h = header
                val k = h.indexOf("kind"); val i = h.indexOf("id"); val v = h.indexOf("_value")
                if (k < 0 || i < 0 || v < 0 || row.size <= maxOf(k, i, v)) continue
                out += Doc(row[k], row[i], row[v])
            }
            return out
        }

        /** RFC 4180: кавычки, "" внутри, переводы строк внутри кавычек */
        fun csvRows(s: String): List<List<String>> {
            val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cur = StringBuilder()
            var q = false; var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (q) {
                    if (ch == '"') { if (i + 1 < s.length && s[i + 1] == '"') { cur.append('"'); i++ } else q = false }
                    else cur.append(ch)
                } else when (ch) {
                    '"' -> q = true
                    ',' -> { row.add(cur.toString()); cur.setLength(0) }
                    '\r' -> {}
                    '\n' -> { row.add(cur.toString()); cur.setLength(0); rows.add(row); row = ArrayList() }
                    else -> cur.append(ch)
                }
                i++
            }
            if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
            return rows
        }
    }

    // ---------- HTTP ----------
    private data class Resp(val code: Int, val body: String, val headers: Map<String, String>)

    private fun http(method: String, url: String, body: String?, headers: Map<String, String>, c: SyncConfig, auth: Boolean = true): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000; conn.readTimeout = 20000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (auth) {
                if (c.mode == "v2") { if (c.token.isNotBlank()) conn.setRequestProperty("Authorization", "Token ${c.token}") }
                else if (c.user.isNotBlank()) conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${c.user}:${c.password}".toByteArray()))
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hs = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return Resp(code, text, hs)
        } finally { conn.disconnect() }
    }
}

/** вызовы из фонового потока синка — переносим в главный и ждём */
fun AppState.applyRemoteBlocking(docs: List<InfluxSync.Doc>): Map<String, Int> {
    val latch = java.util.concurrent.CountDownLatch(1)
    var res: Map<String, Int> = emptyMap()
    onMain { try { res = applyRemote(docs) } finally { latch.countDown() } }
    latch.await(10, TimeUnit.SECONDS)
    return res
}

fun AppState.updateSyncBlocking(f: (SyncConfig) -> SyncConfig) {
    val latch = java.util.concurrent.CountDownLatch(1)
    onMain { try { updateSync(f) } finally { latch.countDown() } }
    latch.await(10, TimeUnit.SECONDS)
}
