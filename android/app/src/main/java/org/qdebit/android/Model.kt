package org.qdebit.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Схема данных 1:1 с веб-версией (QDebit.html): экспорт/импорт JSON совместимы в обе стороны.

@Serializable
data class Run(val v: Double, val t: Double)

@Serializable
data class Target(val q: Double? = 5.0, val p: Double? = 3.5)

@Serializable
data class Relay(val on: Double? = 3.5, val off: Double? = 4.0)

@Serializable
data class Well(
    val kind: String = "borehole",          // borehole | abyssinian | dug
    val install: String = "sub",            // sub (погружной) | surface (поверхностный, всасывает) | ejector (выносной эжектор)
    val static: Double? = 35.0,
    val dyn: Double? = 50.0,
    val dynQ: Double? = null,
    val pumpDepth: Double? = 75.0,
    val minAbove: Double? = 5.0,
    val casingID: Double? = 110.0,
    val riserOD: Double? = 32.0,
    val depth: Double? = 190.0,
    val minGap: Double? = 8.0,
    val pumpElev: Double? = 0.0,            // насос над землёй, м (кессон — минус)
    val alt: Double? = 150.0,               // высота над морем — для атмосферного давления
    // ---- геология и отметка (1.6.0) ----
    val aquifer: String? = null,            // sand | limestone (обсадная); абиссинка — всегда песок
    val aqTop: Double? = null,              // глубина кровли водоносного (песка/известняка), м от устья
    val horizon: Int? = null,               // водоносный горизонт 1/2/3
    val elev: Double? = null,               // своя отметка устья, м абс. (нивелир/лазер)
    val elevDem: Double? = null,            // отметка по модели рельефа (по координатам)
    val elevDemLat: Double? = null,
    val elevDemLon: Double? = null,
    val layers: List<Layer> = emptyList(),  // разрез / журнал бурения
    // ---- модель по замерам (1.7.0): понижение = fitB·Q + fitC·Q² ----
    val cpSet: Double? = null,              // уставка постоянного давления (SQE / частотник), бар
    val fitB: Double? = null,
    val fitC: Double? = null,
    val fitN: Int? = null,
    val fitQmax: Double? = null,
    val fitKind: String? = null,            // step — по ступеням; one — прямая
)

@Serializable
data class Layer(val from: Double? = null, val to: Double? = null, val rock: String = "sand", val water: Boolean = false)

@Serializable
data class Sys(
    val dz: Double? = 0.0,
    val riserPipe: String = "pe32-16",
    val riserLen: Double? = null,
    val horizPipe: String = "pe32-16",
    val horizLen: Double? = 20.0,
    val localPct: Double? = 10.0,
    val tempC: Double? = 8.0,
    val riserID: Double? = null,
    val horizID: Double? = null,
    val suctLen: Double? = 0.0,             // всасывающая трасса от скважины до поверхностного насоса, м
    val suctPipe: String? = null,           // её труба; null — как вертикаль
    val suctID: Double? = null,
)

@Serializable
data class Pump(
    val id: String,
    val name: String,
    val diam: Double? = null,
    val powerKW: Double? = null,
    val currentA: Double? = null,
    val minQ: Double? = null,
    val points: List<List<Double>> = emptyList(),
    val note: String? = null,
    val src: String? = null,
    val catalog: Boolean = false,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val type: String = "sub",               // sub | surface | ejector
    val suction: Double? = null,            // паспортная глубина всасывания, м (поверхностные)
    val curves: List<Curve>? = null,        // эжекторные: кривые «напор на выходе» по глубинам всасывания
    val approx: Boolean = false,            // кривая построена по Hmax/Qmax паспорта (не таблица)
)

/** насос из внешнего JSON (каталог от Perplexity/паспорта) — поля как в веб-версии, id не обязателен */
@Serializable
data class ImportPump(
    val name: String = "", val type: String? = null, val points: List<List<Double>>? = null, val curves: List<Curve>? = null,
    val suction: Double? = null, val diam: Double? = null, val powerKW: Double? = null, val currentA: Double? = null,
    val minQ: Double? = null, val note: String? = null, val src: String? = null,
    // формат «большого ТЗ» (Perplexity)
    val quality: String? = null, val hmax: Double? = null, val qmax: Double? = null, val qnom: Double? = null, val hnom: Double? = null,
    @kotlinx.serialization.SerialName("h_at_qmax") val hAtQmax: Double? = null,
    @kotlinx.serialization.SerialName("diam_mm") val diamMm: Double? = null,
    @kotlinx.serialization.SerialName("power_kw") val powerKw2: Double? = null,
    @kotlinx.serialization.SerialName("current_a") val currentA2: Double? = null,
    @kotlinx.serialization.SerialName("suction_m") val suctionM: Double? = null,
    @kotlinx.serialization.SerialName("min_q_m3h") val minQ2: Double? = null,
)

@Serializable
data class Curve(val depth: Double? = null, val points: List<List<Double>> = emptyList())

/** Профиль скважины: параметры скважины + своя система (трубы, давление, реле, насосы на графике). */
@Serializable
data class WellProfile(
    val id: String,
    val name: String = "Скважина",
    val note: String = "",
    val well: Well = Well(),
    val sys: Sys = Sys(),
    val target: Target = Target(),
    val relay: Relay = Relay(),
    val chart: List<String?> = listOf("cat-eco-4-142", null, null),
    val refId: String? = null,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val pumpId: String? = null,             // насос, который стоит/планируется в этой скважине
    val lat: Double? = null,
    val lon: Double? = null,
    val fmt: Int = 0,                       // версия формата ввода; 3 — абиссинка по-новому (1.5.0)
    val legacy: kotlinx.serialization.json.JsonObject? = null,   // well/sys до перевода в новый формат — ничего не теряем
)

fun newId(prefix: String = ""): String =
    prefix + java.lang.Long.toString(System.currentTimeMillis(), 36) + Integer.toString((Math.random() * 1679616).toInt(), 36)

fun newWell(name: String, base: WellProfile? = null): WellProfile =
    (base ?: WellProfile(id = "")).copy(id = newId("w-"), name = name, refId = null, updatedAt = System.currentTimeMillis(), deleted = false, fmt = 3)

/**
 * Перевод скважины в формат 1.5.0 (однократно, fmt < 3). Для абиссинки:
 * • глубина иглы = прежняя «глубина забора», если своя не задана;
 * • прежняя длина всасывающей сверх глубины забора — это была горизонталь до насоса → «всасывающая трасса»;
 * • труба иглы: если трубу не меняли (ВГП 1¼″ по умолчанию), а «внутр. Ø колонны» правили — берём трубу по этому Ø;
 * • прежние well/sys целиком сохраняются в legacy; динамика, глубина насоса, Ø колонны и т.п. остаются в данных (просто скрыты).
 */
fun WellProfile.upgraded(): WellProfile {
    if (fmt >= 3) return this
    if (well.kind != "abyssinian") return copy(fmt = 3)
    var w = well; var sy = sys
    if (w.depth == null && w.pumpDepth != null) w = w.copy(depth = w.pumpDepth)
    val rl = sys.riserLen; val pd = well.pumpDepth
    if (rl != null && pd != null && rl > pd + 0.05 && (sy.suctLen ?: 0.0) == 0.0) sy = sy.copy(suctLen = Math.round((rl - pd) * 100) / 100.0)
    val cid = well.casingID
    if (sys.riserPipe == "st32" && cid != null && Math.abs(cid - 35.9) > 0.05) {
        val match = Core.PIPES.firstOrNull { Math.abs(it.idmm - cid) <= 0.3 }
        sy = if (match != null) sy.copy(riserPipe = match.id) else sy.copy(riserPipe = "custom", riserID = cid)
    }
    val leg = kotlinx.serialization.json.buildJsonObject {
        put("well", QJson.encodeToJsonElement(Well.serializer(), well))
        put("sys", QJson.encodeToJsonElement(Sys.serializer(), sys))
        put("at", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
    }
    return copy(well = w, sys = sy, fmt = 3, legacy = leg, updatedAt = System.currentTimeMillis())
}

@Serializable
data class Settings(
    val v: Int = 2,
    val presets: List<Double> = listOf(5.0, 10.0, 12.0, 20.0, 50.0, 100.0, 200.0),
    val volume: Double? = 10.0,
    val reaction: Double = 0.3,
    val pumps: List<Pump> = emptyList(),
    val wells: List<WellProfile> = emptyList(),
    val activeWell: String? = null,
    val measWell: String? = null,          // привязка нового замера; null = без привязки
    val volKeys: Boolean = true,           // только Android: кнопки громкости = старт/стоп
    val prefsUpdatedAt: Long = 0,          // для синка пресетов/реакции
    val profile: List<String> = emptyList(), // скважины профиля-разреза на «Участке», по порядку
    // ---- v1 (одна скважина в корне) — читаются только при миграции ----
    val well: Well? = null,
    val sys: Sys? = null,
    val target: Target? = null,
    val relay: Relay? = null,
    val chart: List<String?>? = null,
    val refId: String? = null,
)

/** v1 → v2 и нормализация: всегда есть хотя бы одна живая скважина, активная — живая. */
fun migrate(x: Settings): Settings {
    var s = x
    if (s.wells.isEmpty()) {
        val w = WellProfile(
            id = newId("w-"), name = "Скважина 1",
            well = s.well ?: Well(), sys = s.sys ?: Sys(), target = s.target ?: Target(), relay = s.relay ?: Relay(),
            chart = s.chart ?: listOf("cat-eco-4-142", null, null), refId = s.refId, updatedAt = System.currentTimeMillis(),
        )
        s = s.copy(wells = listOf(w), activeWell = w.id, measWell = null)
    }
    s = s.copy(v = 2, well = null, sys = null, target = null, relay = null, chart = null, refId = null,
        wells = s.wells.map { it.copy(chart = (it.chart + listOf(null, null, null)).take(3)).upgraded() })
    if (s.wells.none { !it.deleted }) s = s.copy(wells = s.wells + newWell("Скважина 1"))
    val live = s.wells.filter { !it.deleted }
    if (live.none { it.id == s.activeWell }) s = s.copy(activeWell = live.first().id)
    if (s.measWell != null && live.none { it.id == s.measWell }) s = s.copy(measWell = null)
    return s
}

@Serializable
data class Measurement(
    val id: String,
    val ts: Long,
    val runs: List<Run>,
    val q: Double,
    val cv: Double? = null,
    val err: Double? = null,
    val dynLevel: Double? = null,
    val pumpingMin: Double? = null,
    val cav: Int? = null,                   // кавитация при замере: 0 нет, 1 лёгкая, 2 сильная
    val press: Double? = null,              // давление на манометре во время замера, бар
    val pressAt: String? = null,            // house — в доме у реле; head — у оголовка
    val src: String? = null,
    val note: String? = null,
    val app: String? = null,
    val wellId: String? = null,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

fun Measurement.recomputed(reaction: Double): Measurement {
    val st = Core.seriesStats(runs, reaction) ?: return copy(q = 0.0, cv = null, err = null)
    return copy(q = Math.round(st.avg.m3h * 10000) / 10000.0, cv = Math.round(st.cv * 10000) / 10000.0, err = Math.round(st.errSeries * 10000) / 10000.0)
}

@Serializable
data class Backup(
    val app: String = "QDebit",
    val v: String = "",
    val exportedAt: String = "",
    val settings: Settings? = null,
    val measurements: List<Measurement> = emptyList(),
)

/** Настройки синка — только локально, в экспорт и в облако не уходят (пароль/токен!). */
@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val auto: Boolean = true,
    val mode: String = "v1",               // v1: /write + InfluxQL (1.x, а также v1-совместимый API 2.x/3.x) · v2: /api/v2 + Flux
    val url: String = "",
    val db: String = "homeassistant",
    val user: String = "",
    val password: String = "",
    val org: String = "",
    val bucket: String = "",
    val token: String = "",
    val lastPushAt: Long = 0,
    val lastSyncAt: Long = 0,
)

// Локальный файл приложения
@Serializable
data class Saved(
    val settings: Settings = Settings(),
    val measurements: List<Measurement> = emptyList(),
    val series: List<Run> = emptyList(),
    val sync: SyncConfig = SyncConfig(),
)

val QJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    prettyPrint = false
}

// ---------- каталог: только паспортные таблицы H(Q) — общий с вебом web/catalog.json → CatalogData.kt ----------
object Catalog {
    val ALL: List<Pump> by lazy {
        runCatching { QJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Pump.serializer()), CATALOG_JSON) }
            .getOrElse { emptyList() }
            .map { it.copy(catalog = true) }
    }
}
