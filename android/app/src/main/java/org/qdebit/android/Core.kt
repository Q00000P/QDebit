package org.qdebit.android

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// Порт core.js 1:1. Единицы: Q — м³/ч, H — м вод. ст., P — бар, D — мм, L — м, V — л, t — с.
object Core {
    const val M_PER_BAR = 10.197
    private const val G = 9.80665

    // ---------- замер ----------
    data class Units(val lps: Double) {
        val lpm get() = lps * 60
        val m3h get() = lps * 3.6
        val m3d get() = lps * 86.4
        val secPerM3 get() = if (lps > 0) 1000 / lps else Double.POSITIVE_INFINITY
    }

    data class RunStat(val v: Double, val t: Double, val u: Units, val dev: Double)
    data class Stats(
        val n: Int, val V: Double, val T: Double, val avg: Units, val mean: Units,
        val min: Units, val max: Units, val cv: Double, val errRun: List<Double>, val errSeries: Double,
        val perRun: List<RunStat>,
    )

    fun seriesStats(runs: List<Run>, reaction: Double = 0.3): Stats? {
        val rs = runs.filter { it.v > 0 && it.t > 0 }
        if (rs.isEmpty()) return null
        val V = rs.sumOf { it.v }
        val T = rs.sumOf { it.t }
        val rates = rs.map { it.v / it.t }
        val mean = rates.average()
        val sd = if (rates.size > 1) sqrt(rates.sumOf { (it - mean).pow(2) } / (rates.size - 1)) else 0.0
        val errRun = rs.map { reaction * sqrt(2.0) / it.t }
        val errSeries = reaction * sqrt(2.0) * sqrt(rs.size.toDouble()) / T
        return Stats(
            rs.size, V, T, Units(V / T), Units(mean), Units(rates.min()), Units(rates.max()),
            if (mean > 0) sd / mean else 0.0, errRun, errSeries,
            rs.mapIndexed { i, r -> RunStat(r.v, r.t, Units(rates[i]), if (mean != 0.0) rates[i] / mean - 1 else 0.0) },
        )
    }

    // ---------- трубы ----------
    data class Pipe(val id: String, val name: String, val od: Double, val wall: Double, val pn: Int?, val rough: Double = 0.007) {
        val idmm: Double = Math.round((od - 2 * wall) * 10) / 10.0
    }

    // ПЭ100 ГОСТ 18599 (SDR17 = PN10, SDR11 = PN16) + сталь ВГП ГОСТ 3262 (абиссинки, всасывание), шероховатость б/у стали 0,15 мм
    val PIPES = listOf(
        Pipe("pe25-16", "ПНД 25×2,3 PN16", 25.0, 2.3, 16),
        Pipe("pe32-10", "ПНД 32×2,0 PN10", 32.0, 2.0, 10),
        Pipe("pe32-16", "ПНД 32×3,0 PN16", 32.0, 3.0, 16),
        Pipe("pe40-10", "ПНД 40×2,4 PN10", 40.0, 2.4, 10),
        Pipe("pe40-16", "ПНД 40×3,7 PN16", 40.0, 3.7, 16),
        Pipe("pe50-10", "ПНД 50×3,0 PN10", 50.0, 3.0, 10),
        Pipe("pe50-16", "ПНД 50×4,6 PN16", 50.0, 4.6, 16),
        Pipe("st20", "ВГП ¾″ (Ду20)", 26.8, 2.8, null, 0.15),
        Pipe("st25", "ВГП 1″ (Ду25)", 33.5, 3.2, null, 0.15),
        Pipe("st32", "ВГП 1¼″ (Ду32)", 42.3, 3.2, null, 0.15),
        Pipe("st40", "ВГП 1½″ (Ду40)", 48.0, 3.5, null, 0.15),
        Pipe("st50", "ВГП 2″ (Ду50)", 60.0, 3.5, null, 0.15),
    )

    fun pipeById(id: String?) = PIPES.firstOrNull { it.id == id }
    fun nu(T: Double) = 1.792e-6 / (1 + 0.0337 * T + 0.000221 * T * T)

    data class Fr(val v: Double = 0.0, val re: Double = 0.0, val f: Double = 0.0, val perM: Double = 0.0, val hf: Double = 0.0)

    fun friction(Q: Double, Dmm: Double?, L: Double?, roughMm: Double = 0.007, T: Double = 8.0): Fr {
        if (!(Q > 0) || Dmm == null || !(Dmm > 0) || L == null || !(L > 0)) return Fr()
        val D = Dmm / 1000
        val A = PI * D * D / 4
        val v = (Q / 3600) / A
        val re = v * D / nu(T)
        val eps = roughMm / 1000
        fun sj(r: Double) = 0.25 / log10(eps / (3.7 * D) + 5.74 / r.pow(0.9)).pow(2)
        val f = when {
            re < 2300 -> 64 / re
            re > 4000 -> sj(re)
            else -> { val k = (re - 2300) / 1700; 64.0 / 2300 * (1 - k) + sj(4000.0) * k }
        }
        val perM = f * v * v / (2 * G * D)
        return Fr(v, re, f, perM, perM * L)
    }

    // ---------- скважина ----------
    /**
     * Модель скважины: понижение s(Q) = B·Q + C·Q² (Джейкоб): B·Q — потери в пласте (линейные),
     * C·Q² — потери на входе в скважину (фильтр, трещины, колонна), растут квадратично.
     * B, C подбираются по замерам (fitWell) и хранятся в well.fitB/fitC; одна точка — только B (прямая).
     * Без подбора — прямая по «динамика при расходе» из формы.
     */
    data class WellModel(
        val ok: Boolean, val static: Double?, val pumpDepth: Double?, val q: Double = 0.0,
        val testQ: Double = 0.0, val testDyn: Double = 0.0, val limitLevel: Double? = null,
        val maxDrawdown: Double? = null, val badDepth: Boolean = false, val Qsafe: Double? = null,
        val area: Double? = null, val reserveL: Double? = null,
        val B: Double = 0.0, val C: Double = 0.0, val kind: String = "one", val n: Int = 0,
        val QsafeRaw: Double? = null, val Qvalid: Double? = null, val capped: Boolean = false,
    ) { fun sAt(Q: Double) = B * Q + C * Q * Q }

    fun wellModel(w: Well): WellModel {
        val ab = isAbyss(w)
        val st = w.static; val dyn = if (ab) null else w.dyn; val dq = if (ab) null else w.dynQ; val hp = if (ab) null else w.pumpDepth
        val minA = w.minAbove ?: 0.0
        var ok = false; var B = 0.0; var C = 0.0; var kind = "one"; var n = 0; var testQ = 0.0
        val fb = w.fitB
        if (!ab && st != null && fb != null && fb > 0) {
            B = fb; C = maxOf(0.0, w.fitC ?: 0.0); kind = w.fitKind ?: if (C > 0) "step" else "one"; n = w.fitN ?: 1; testQ = w.fitQmax ?: dq ?: 1.0; ok = true
        } else if (st != null && dyn != null && dq != null && dyn > st && dq > 0) {
            B = (dyn - st) / dq; C = 0.0; kind = "one"; n = 1; testQ = dq; ok = true
        }
        val sT = B * testQ + C * testQ * testQ
        var limit: Double? = null; var mdd: Double? = null; var bad = false; var qs: Double? = null; var area: Double? = null; var res: Double? = null
        var raw: Double? = null; var valid: Double? = null; var capped = false
        if (st != null && hp != null) {
            limit = hp - minA
            mdd = limit - st
            if (mdd <= 0) bad = true
            if (ok && mdd > 0) {
                raw = if (C > 0) (-B + sqrt(B * B + 4 * C * mdd)) / (2 * C) else mdd / B
                // экстраполяция честна недалеко от замеров: прямая по одной точке — до 1,5×, парабола по ступеням — до 2×
                valid = testQ * (if (kind == "step") 2.0 else 1.5)
                capped = raw > valid
                qs = min(raw, valid)
            }
            val D = w.casingID; val pod = w.riserOD ?: 0.0
            if (D != null && D > 0 && mdd > 0) {
                area = PI * ((D / 1000).pow(2) - (pod / 1000).pow(2)) / 4
                res = area * mdd * 1000
            }
        }
        return WellModel(ok, st, hp, if (ok && sT > 0) testQ / sT else 0.0, testQ, (st ?: 0.0) + sT, limit, mdd, bad, qs, area, res,
            B, C, kind, n, raw, valid, capped)
    }

    fun levelAt(wm: WellModel, w: Well, Q: Double): Double {
        // абиссинка: труба от фильтра до насоса целиком в воде — поднимаем всегда со статики
        if (isAbyss(w)) return w.static ?: 0.0
        if (wm.ok) return wm.static!! + wm.sAt(Q)
        if (Q <= 0 && w.static != null) return w.static
        return w.dyn ?: w.static ?: 0.0
    }

    /** приток из пласта при понижении s (обратная к s(Q)) */
    fun inflowAt(wm: WellModel, s: Double): Double =
        if (s <= 0) 0.0 else if (wm.C > 0) (-wm.B + sqrt(wm.B * wm.B + 4 * wm.C * s)) / (2 * wm.C) else s / wm.B

    /** часы до предельного уровня при подаче Qp: A·ds/dt = Qp − приток(s); +∞ — не осушит; null — нет данных */
    fun drainHours(wm: WellModel, Qp: Double): Double? {
        val area = wm.area ?: return null
        val mdd = wm.maxDrawdown ?: return null
        if (!wm.ok || mdd <= 0 || !(Qp > 0)) return null
        if (Qp <= inflowAt(wm, mdd)) return Double.POSITIVE_INFINITY
        val N = 400; val h = mdd / N; var t = 0.0
        for (i in 0 until N) { val sm = (i + 0.5) * h; t += area * h / (Qp - inflowAt(wm, sm)) }
        return t
    }

    /** словами: откуда модель скважины и безопасный дебит */
    fun wellModelText(wm: WellModel, w: Well): String {
        if (!wm.ok) return ""
        val minA = w.minAbove ?: 0.0
        val how = if (wm.kind == "step")
            "по ${wm.n} замерам на разных расходах: понижение = ${fx(wm.B, 3)}·Q + ${fx(wm.C, 3)}·Q² (вторая часть — потери на входе в скважину, растут быстрее расхода)"
        else "по одному расходу: ${f2(wm.testQ)} м³/ч при понижении ${f1(wm.sAt(wm.testQ))} м = ${f2(wm.q)} м³/ч на 1 м (прямая — на больших расходах завышает)"
        var t = " Безопасный дебит ${f2(wm.Qsafe)} м³/ч — $how; уровню можно опуститься на ${f1(wm.maxDrawdown)} м (насос на ${f1(wm.pumpDepth)} м − запас ${f1(minA)} м − статика ${f1(wm.static)})."
        if (wm.capped) t += " По модели вышло бы ${f2(wm.QsafeRaw)}, но дальше ${if (wm.kind == "step") "2" else "1,5"}× от максимального расхода замеров (${f2(wm.testQ)}) экстраполяция нечестная — взято ${f2(wm.Qvalid)}. " +
            (if (wm.kind == "step") "Чтобы поднять предел — замер на большем расходе." else "Для честного предела — 2–3 замера на разных расходах (прикрывая кран): уровнемером или по манометру.")
        return t
    }

    /** динамика по манометру: напор насоса по кривой − давление − трение (до манометра); только погружной с кривой */
    fun dynFromPressure(wp: WellProfile, pump: Pump?, Q: Double, P: Double?, at: String?): Double? {
        val w = wp.well
        if (pump == null || installOf(w) != "sub" || !(Q > 0) || P == null) return null
        val H = pumpH(pump, Q) ?: return null
        val sh = systemHead(Q, P, wp.sys, w, WellModel(false, null, null))
        val need = if (at == "head") sh.hp + sh.hfRiser + sh.localRiser else sh.total - sh.level
        return H - need
    }

    data class WPoint(val q: Double, val s: Double, val dyn: Double, val src: String, val ts: Long?, val id: String?)
    data class WReject(val m: Measurement, val why: String)
    /** точки «расход → понижение» для подбора модели: замеры (уровнемер или манометр) + форма */
    fun wellPoints(wp: WellProfile, meas: List<Measurement>, pumpById: (String) -> Pump?): Pair<List<WPoint>, List<WReject>> {
        val w = wp.well; val st = w.static; val pts = ArrayList<WPoint>(); val rej = ArrayList<WReject>()
        if (st == null || isAbyss(w)) return pts to rej
        val pump = wp.pumpId?.let { pumpById(it) }; val cp = w.cpSet
        for (m in meas) {
            if (m.deleted || m.wellId != wp.id || !(m.q > 0)) continue
            var dyn = m.dynLevel; var src = "уровнемер"
            if (dyn == null && m.press != null) {
                if (cp != null && m.press >= cp - 0.2) { rej += WReject(m, "давление ${f1(m.press)} бар — насос держал уставку ${f1(cp)} бар (обороты снижены), кривая не годится"); continue }
                dyn = dynFromPressure(wp, pump, m.q, m.press, m.pressAt)
                src = "манометр ${f1(m.press)} бар (${if (m.pressAt == "head") "оголовок" else "дом"})"
                if (dyn == null) { rej += WReject(m, if (pump != null) "нет кривой насоса или насос не погружной" else "у скважины не выбран насос — по манометру не посчитать"); continue }
            }
            if (dyn == null) continue
            val sdd = dyn - st
            if (sdd <= 0.05) { rej += WReject(m, "динамика ${f1(dyn)} м не ниже статики ${f1(st)} — проверь статику или давление"); continue }
            pts += WPoint(m.q, sdd, dyn, src, m.ts, m.id)
        }
        val dq = w.dynQ; val dyn = w.dyn
        if (dq != null && dq > 0 && dyn != null && dyn > st && pts.none { abs(it.q - dq) < 0.005 && abs(it.dyn - dyn) < 0.05 })
            pts += WPoint(dq, dyn - st, dyn, "форма", null, null)
        return pts to rej
    }

    data class Fit(val B: Double, val C: Double, val kind: String, val n: Int, val Qmax: Double)
    /** подбор B, C по точкам: s/Q = B + C·Q; одна точка или один расход — прямая */
    fun fitWell(pts: List<WPoint>): Fit? {
        val P = pts.filter { it.q > 0 && it.s > 0 }
        if (P.isEmpty()) return null
        val qmax = P.maxOf { it.q }; val qmin = P.minOf { it.q }
        fun lin() = Fit(P.sumOf { it.s } / P.sumOf { it.q }, 0.0, "one", P.size, qmax)
        if (P.size < 2 || qmax / qmin < 1.25) return lin()
        val xs = P.map { it.q }; val ys = P.map { it.s / it.q }; val n = P.size
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        val C = if (sxx > 0) sxy / sxx else 0.0; val B = my - C * mx
        if (C < 0 || B <= 0) return lin()
        return Fit(B, C, "step", n, qmax)
    }

    // ---------- система ----------
    // Установка (well.install): sub — погружной; surface — поверхностный (труба «в скважине» = всасывающая);
    // ejector — поверхностный с выносным эжектором (кривые паспорта по глубине всасывания).
    data class SysHead(
        val Q: Double, val level: Double, val dz: Double, val hp: Double, val hfRiser: Double,
        val hfHoriz: Double, val local: Double, val localRiser: Double, val localHoriz: Double, val vRiser: Double, val vHoriz: Double,
    ) { val total get() = level + dz + hp + hfRiser + hfHoriz + local }

    fun pipeOf(id: String, custom: Double?): Pair<Double?, Double> =
        if (id == "custom") custom to 0.007 else pipeById(id)?.let { it.idmm to it.rough } ?: (null to 0.007)

    fun isAbyss(w: Well) = w.kind == "abyssinian"
    fun installOf(w: Well) = if (isAbyss(w)) "surface" else w.install.ifBlank { "sub" }
    fun pumpType(p: Pump) = p.type.ifBlank { "sub" }
    val TYPE_NAMES = mapOf("sub" to "погружной", "surface" to "поверхностный / станция", "ejector" to "с выносным эжектором")
    val INSTALL_NAMES = mapOf("sub" to "погружной в скважине", "surface" to "поверхностный (всасывает)", "ejector" to "поверхностный + выносной эжектор")

    fun systemHead(Q: Double, P: Double, s: Sys, w: Well, wm: WellModel): SysHead {
        val T = s.tempC ?: 8.0
        val (rD, rR) = pipeOf(s.riserPipe, s.riserID)
        val (hD, hR) = pipeOf(s.horizPipe, s.horizID)
        // абиссинка: ниже зеркала воду подпирает пласт — трение только от зеркала до насоса (статика + отметка насоса)
        val lr = if (isAbyss(w)) maxOf(0.0, (w.static ?: 0.0) + (w.pumpElev ?: 0.0)) else (s.riserLen ?: w.pumpDepth ?: 0.0)
        val lh = s.horizLen ?: 0.0
        val fr0 = friction(Q, rD, lr, rR, T)
        // всасывающая трасса от скважины до поверхностного насоса (своя труба или как вертикаль)
        val ls = if (installOf(w) == "surface") (s.suctLen ?: 0.0) else 0.0
        val (sD, sR) = if (!s.suctPipe.isNullOrBlank()) pipeOf(s.suctPipe, s.suctID) else rD to rR
        val fs = if (ls > 0) friction(Q, sD, ls, sR, T) else Fr()
        val fr = fr0.copy(hf = fr0.hf + fs.hf)
        val fh = friction(Q, hD, lh, hR, T)
        val k = (s.localPct ?: 0.0) / 100
        return SysHead(Q, levelAt(wm, w, Q), s.dz ?: 0.0, P * M_PER_BAR, fr.hf, fh.hf, (fr.hf + fh.hf) * k, fr.hf * k, fh.hf * k, fr.v, fh.v)
    }

    // высота устья над морем: своя отметка (нивелир) → модель рельефа → «высота над морем» из формы
    fun groundZ(w: Well): Double? = w.elev ?: w.elevDem
    fun effAlt(w: Well): Double? = groundZ(w) ?: w.alt

    // ---------- геология и перепады между скважинами ----------
    data class Rock(val id: String, val name: String, val color: Long)
    val ROCKS = listOf(
        Rock("soil", "почва / насыпь", 0xFF8D6E63), Rock("loam", "суглинок", 0xFFC8A165), Rock("clay", "глина", 0xFFA1887F),
        Rock("sandyloam", "супесь", 0xFFE2C98F), Rock("sand", "песок", 0xFFF2D77A), Rock("gravel", "гравий / галька", 0xFFBDB3A0),
        Rock("limestone", "известняк", 0xFFB0BEC5), Rock("marl", "мергель", 0xFFCFD8DC), Rock("other", "другое", 0xFF9E9E9E),
    )
    fun rock(id: String?) = ROCKS.firstOrNull { it.id == id } ?: ROCKS.last()
    val AQ_NAMES = mapOf("sand" to "песок", "limestone" to "известняк (артезианка)")
    fun aquiferOf(w: Well): String? = if (isAbyss(w)) "sand" else if (w.kind == "dug") null else w.aquifer?.ifBlank { null }

    data class ZInfo(
        val id: String, val name: String, val Z: Double?, val src: String?, val aquifer: String?, val horizon: Int?,
        val static: Double?, val aqTop: Double?, val Zw: Double?, val Ztop: Double?, val depth: Double?, val lat: Double?, val lon: Double?,
    )
    fun zInfo(p: WellProfile): ZInfo {
        val w = p.well; val Z = groundZ(w)
        val src = if (w.elev != null) "нивелир" else if (w.elevDem != null) "рельеф" else null
        return ZInfo(p.id, p.name, Z, src, aquiferOf(w), w.horizon, w.static, w.aqTop,
            if (Z != null && w.static != null) Z - w.static else null, if (Z != null && w.aqTop != null) Z - w.aqTop else null, w.depth, p.lat, p.lon)
    }
    data class WPair(val a: ZInfo, val b: ZInfo, val dist: Double?, val dZ: Double?, val dZw: Double?, val dZtop: Double?, val dStatic: Double?, val gradW: Double?, val sameAq: Boolean,
                     val dTop: Double? = null, val diffHorizon: Boolean = false)
    fun wellPairs(wps: List<WellProfile>): List<WPair> {
        val zs = wps.map { zInfo(it) }; val out = ArrayList<WPair>()
        fun dd(x: Double?, y: Double?) = if (x != null && y != null) y - x else null
        for (i in zs.indices) for (j in i + 1 until zs.size) {
            val a = zs[i]; val b = zs[j]
            val d = if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) distM(a.lat, a.lon, b.lat, b.lon) else null
            val dZw = dd(a.Zw, b.Zw)
            val dh = a.horizon != null && b.horizon != null && a.horizon != b.horizon
            out += WPair(a, b, d, dd(a.Z, b.Z), dZw, dd(a.Ztop, b.Ztop), dd(a.static, b.static),
                if (d != null && d > 0 && dZw != null) dZw / d * 1000 else null, a.aquifer != null && a.aquifer == b.aquifer && !dh,
                dd(a.aqTop, b.aqTop), dh)
        }
        return out
    }

    /** расстояние: до 1000 м — в метрах, дальше — в километрах */
    fun fmtDist(m: Double?): String = when { m == null -> "—"; m < 1000 -> "${f0(m)} м"; else -> "${fx(m / 1000, if (m < 10000) 2 else 1)} км" }
    /** отметка: своя (нивелир) — до сантиметров; по рельефу — до метра (точнее модель не даёт) */
    fun fmtZ(z: Double?, src: String?): String = if (z == null) "—" else if (src == "рельеф") "≈${f0(z)}" else f2(z)

    /** сравнение пары словами: строки [что, A, B, разница] + вывод про уклон зеркала */
    fun pairRows(p: WPair): Pair<List<List<String>>, String> {
        val n = p.b.name; val rel = p.a.src == "рельеф" || p.b.src == "рельеф"; val dp = if (rel) 0 else 2
        fun word(d: Double?, up: String, down: String, digits: Int): String {
            val eps = if (digits > 0) 0.05 else 0.5
            return when {
            d == null -> "—"
            abs(d) < eps -> "одинаково"
            else -> "$n: ${if (d > 0) up else down} на ${fx(abs(d), digits)} м"
            }
        }
        val zsrc = if (rel) "рельеф" else null
        fun aqn(z: ZInfo) = z.aquifer?.let { (AQ_NAMES[it] ?: it) + (z.horizon?.let { h -> ", $h-й горизонт" } ?: "") } ?: "не задан"
        val rows = ArrayList<List<String>>()
        rows += listOf("Водоносный", aqn(p.a), aqn(p.b), if (p.sameAq) "один" else if (p.a.aquifer != null && p.b.aquifer != null) "разные" else "—")
        rows += listOf("Устье, м абс.", fmtZ(p.a.Z, p.a.src), fmtZ(p.b.Z, p.b.src), word(p.dZ, "выше", "ниже", dp))
        rows += listOf("Статика, м от устья", p.a.static?.let { f1(it) } ?: "—", p.b.static?.let { f1(it) } ?: "—", word(p.dStatic, "глубже", "мельче", 1))
        rows += listOf("Зеркало воды, м абс.", fmtZ(p.a.Zw, zsrc), fmtZ(p.b.Zw, zsrc), word(p.dZw, "выше", "ниже", dp))
        if (p.a.aqTop != null || p.b.aqTop != null) {
            rows += listOf("Кровля водоносного, м от устья", p.a.aqTop?.let { f1(it) } ?: "—", p.b.aqTop?.let { f1(it) } ?: "—", word(p.dTop, "глубже", "мельче", 1))
            rows += listOf("Кровля водоносного, м абс.", fmtZ(p.a.Ztop, zsrc), fmtZ(p.b.Ztop, zsrc), word(p.dZtop, "выше", "ниже", dp))
        }
        var flow = when {
            !p.sameAq -> if (p.a.aquifer != null && p.b.aquifer != null) (if (p.diffHorizon) "Разные горизонты" else "Разные водоносные") + " — уровни воды не сравнимы, уклон не считается."
                         else "Уклон считается, когда у обеих задан один и тот же водоносный."
            p.gradW == null -> "Для уклона нужны координаты и отметки обеих скважин."
            abs(p.gradW) < 0.5 -> "Зеркало ровное — заметного потока между ними нет."
            else -> "Уклон зеркала ${f1(abs(p.gradW))}‰ (${fx(abs(p.dZw ?: 0.0), dp)} м на ${fmtDist(p.dist)}) — вода течёт к «${if ((p.dZw ?: 0.0) > 0) p.a.name else p.b.name}»."
        }
        if (rel) flow += " Отметки по рельефу ±2–5 м: перепады меньше ~5 м между ними ненадёжны — для точного сравнения впиши свои отметки устья."
        return rows to flow
    }

    // всасывание поверхностного насоса
    fun hAtm(alt: Double?) = 10.33 * Math.exp(-(alt ?: 150.0) / 8434)
    fun hVap(T: Double) = 0.6113 * Math.exp(17.27 * T / (T + 237.3)) / 9.81
    data class Suction(val lift: Double, val loss: Double, val total: Double, val npsha: Double)

    fun suctionAt(Q: Double, s: Sys, w: Well, wm: WellModel): Suction {
        val sh = systemHead(Q, 0.0, s, w, wm)
        val lift = sh.level + (w.pumpElev ?: 0.0)
        val loss = sh.hfRiser + sh.localRiser
        return Suction(lift, loss, lift + loss, hAtm(effAlt(w)) - hVap(s.tempC ?: 8.0) - lift - loss)
    }

    fun suctionQmax(p: Pump, s: Sys, w: Well, wm: WellModel, qTop: Double): Double {
        val lim = p.suction
        fun ok(Q: Double): Boolean { val x = suctionAt(Q, s, w, wm); return if (lim != null) x.total <= lim else x.npsha >= 0.5 }
        if (!ok(0.0)) return 0.0
        if (ok(qTop)) return qTop
        var a = 0.0; var b = qTop
        repeat(50) { val m = (a + b) / 2; if (ok(m)) a = m else b = m }
        return a
    }

    // ---------- насосы ----------
    private val ptsCache = HashMap<List<List<Double>>, List<Pair<Double, Double>>>()
    private fun norm(points: List<List<Double>>): List<Pair<Double, Double>> = synchronized(ptsCache) {
        ptsCache.getOrPut(points) { points.filter { it.size >= 2 }.map { it[0] to it[1] }.sortedBy { it.first } }
    }
    fun pts(p: Pump) = norm(p.points)

    /** «0:183, 1:183; 2 173»; строки «@20» начинают кривую для глубины всасывания (эжекторные) — они здесь пропускаются */
    fun parsePoints(text: String, lpm: Boolean = false): List<List<Double>> {
        val body = text.lines().filterNot { it.trimStart().startsWith("@") }.joinToString(" ")
        val nums = Regex("-?\\d+(\\.\\d+)?").findAll(body.replace(Regex("(\\d),(\\d)"), "$1.$2")).map { it.value.toDouble() }.toList()
        val out = ArrayList<List<Double>>()
        var i = 0
        while (i + 1 < nums.size) { out.add(listOf(if (lpm) Math.round(nums[i] * 60 / 1000 * 10000) / 10000.0 else nums[i], nums[i + 1])); i += 2 }
        return out.sortedBy { it[0] }
    }

    fun parseCurves(text: String, lpm: Boolean = false): List<Curve> {
        val out = ArrayList<Pair<Double?, ArrayList<List<Double>>>>()
        for (line in text.lines()) {
            val m = Regex("^\\s*@\\s*(\\d+(?:[.,]\\d+)?)").find(line)
            if (m != null) { out += m.groupValues[1].replace(',', '.').toDouble() to ArrayList(); continue }
            val nums = Regex("-?\\d+(\\.\\d+)?").findAll(line.replace(Regex("(\\d),(\\d)"), "$1.$2")).map { it.value.toDouble() }.toList()
            var i = 0
            while (i + 1 < nums.size) {
                if (out.isEmpty()) out += null to ArrayList()
                out.last().second += listOf(if (lpm) Math.round(nums[i] * 60 / 1000 * 10000) / 10000.0 else nums[i], nums[i + 1]); i += 2
            }
        }
        return out.filter { it.second.isNotEmpty() }.map { Curve(it.first, it.second.sortedBy { p -> p[0] }) }.sortedBy { it.depth ?: 0.0 }
    }

    fun curvesToText(p: Pump): String =
        if (!p.curves.isNullOrEmpty()) p.curves.joinToString("\n") { c -> "@${c.depth?.let { numStr(it) } ?: ""}\n" + c.points.joinToString("\n") { "${numStr(it[0])} ${numStr(it[1])}" } }
        else p.points.joinToString("\n") { "${numStr(it[0])} ${numStr(it[1])}" }

    private fun numStr(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun interp(pp: List<Pair<Double, Double>>, Q: Double): Double? {
        if (pp.isEmpty()) return null
        if (Q <= pp[0].first) return pp[0].second
        for (i in 1 until pp.size) if (Q <= pp[i].first) {
            val (q0, h0) = pp[i - 1]; val (q1, h1) = pp[i]
            return h0 + (h1 - h0) * (Q - q0) / (q1 - q0)
        }
        return null
    }

    fun pumpH(p: Pump, Q: Double): Double? =
        if (pumpType(p) == "ejector" && !p.curves.isNullOrEmpty()) interp(norm(p.curves.first().points), Q) else interp(pts(p), Q)

    fun pumpQmax(p: Pump): Double =
        if (pumpType(p) == "ejector" && !p.curves.isNullOrEmpty()) p.curves.maxOf { c -> norm(c.points).lastOrNull()?.first ?: 0.0 }
        else pts(p).lastOrNull()?.first ?: 0.0

    /** эжекторный: напор на выходе при подаче Q и глубине всасывания L — линейно между соседними глубинами */
    fun ejectorH(p: Pump, Q: Double, L: Double): Double? {
        val cs = p.curves.orEmpty().filter { it.depth != null }
        if (cs.isEmpty()) return interp(pts(p), Q)
        val d = cs.map { it.depth!! }
        if (L <= d.first()) return interp(norm(cs.first().points), Q)
        if (L > d.last()) return null
        for (i in 1 until cs.size) if (L <= d[i]) {
            val h0 = interp(norm(cs[i - 1].points), Q) ?: return null
            val h1 = interp(norm(cs[i].points), Q) ?: return null
            return h0 + (h1 - h0) * (L - d[i - 1]) / (d[i] - d[i - 1])
        }
        return null
    }

    private fun isEj(p: Pump, w: Well) = installOf(w) == "ejector" && pumpType(p) == "ejector"
    /** располагаемый напор: полный (погружной/поверхностный) или на выходе насоса для глубины (эжектор) */
    fun availH(p: Pump, Q: Double, s: Sys, w: Well, wm: WellModel): Double? =
        if (isEj(p, w)) ejectorH(p, Q, levelAt(wm, w, Q) + (w.pumpElev ?: 0.0)) else pumpH(p, Q)

    fun reqH(p: Pump, Q: Double, P: Double, s: Sys, w: Well, wm: WellModel): Double {
        val sh = systemHead(Q, P, s, w, wm)
        return if (isEj(p, w)) (sh.dz - (w.pumpElev ?: 0.0)) + sh.hp + sh.hfHoriz + sh.localHoriz else sh.total
    }

    fun flowAtPressure(p: Pump, P: Double, s: Sys, w: Well, wm: WellModel): Double? {
        val qm = pumpQmax(p)
        if (!(qm > 0)) return null
        fun g(Q: Double): Double { val a = availH(p, Q, s, w, wm) ?: return -1e9; return a - reqH(p, Q, P, s, w, wm) }
        if (g(0.0) < 0) return null
        if (g(qm) >= 0) return qm
        var a = 0.0; var b = qm
        repeat(60) { val m = (a + b) / 2; if (g(m) >= 0) a = m else b = m }
        return (a + b) / 2
    }

    fun pressureAtFlow(p: Pump, Q: Double, s: Sys, w: Well, wm: WellModel): Double? {
        val h = availH(p, Q, s, w, wm) ?: return null
        return (h - reqH(p, Q, 0.0, s, w, wm)) / M_PER_BAR
    }

    data class Eval(
        val pump: Pump, val warn: List<String>, val ok: List<String>, val H0: Double?, val Pshut: Double?,
        val PshutHead: Double?, val PshutBottom: Double?, val pipePN: Int?, val Qneed: Double?, val PatNeed: Double?,
        val Qon: Double?, val Qoff: Double?, val Qp: Double?, val Qfree: Double?, val QfreeRaw: Double?, val Qsafe: Double?,
        val drainOn: Double?, val drainFree: Double?, val gap: Double?, val Phyd: Double?, val eta: Double?,
        val excess: Double?, val fits: Boolean, val fitsCore: Boolean,
        val QsuctMax: Double?, val suctAt: Suction?, val suctLimit: Double?, val levelOn: Double?,
    )

    fun evaluatePump(p: Pump, S: WellProfile): Eval {
        val s = S.sys; val w = S.well; val target = S.target; val relay = S.relay
        val wm = wellModel(w)
        val inst = installOf(w); val pt = pumpType(p)
        val warn = ArrayList<String>(); val ok = ArrayList<String>()
        if (pt != inst) warn += "Насос «${TYPE_NAMES[pt]}», а установка скважины — «${INSTALL_NAMES[inst]}»: расчёт условный"
        val H0 = availH(p, 0.0, s, w, wm)
        val st = w.static ?: 0.0; val hp = w.pumpDepth ?: 0.0; val dz = s.dz ?: 0.0
        val req0 = reqH(p, 0.0, 0.0, s, w, wm)
        val Pshut = H0?.let { (it - req0) / M_PER_BAR }
        val PshutHead = H0?.let { (it - req0 + dz) / M_PER_BAR }
        val PshutBottom = if (H0 != null && inst == "sub") (H0 - st + hp) / M_PER_BAR else null
        val pn = listOf(s.riserPipe, s.horizPipe).mapNotNull { pipeById(it)?.pn }.minOrNull()
        val pmax = if (inst == "sub") PshutBottom else PshutHead
        if (pn != null && pmax != null && pmax > pn)
            warn += "На закрытую задвижку труба увидит ${f1(pmax)} бар > PN$pn — нужна защита (реле/датчик давления, предохранительный клапан)"
        val Qneed = target.q
        var PatNeed = Qneed?.let { pressureAtFlow(p, it, s, w, wm) }
        var Qon = flowAtPressure(p, relay.on ?: 0.0, s, w, wm)
        var Qoff = flowAtPressure(p, relay.off ?: 0.0, s, w, wm)
        var Qp = flowAtPressure(p, target.p ?: 0.0, s, w, wm)
        var Qfree = flowAtPressure(p, 0.0, s, w, wm)
        var QfreeRaw: Double? = null
        var QsuctMax: Double? = null; var suctAt: Suction? = null; var suctLimit: Double? = null
        if (inst == "surface") {
            val top = maxOf(pumpQmax(p), Qneed ?: 0.0)
            val s0 = suctionAt(0.0, s, w, wm)
            QsuctMax = suctionQmax(p, s, w, wm, top); suctLimit = p.suction
            val sq = maxOf(Qneed ?: 0.0, Qon ?: 0.0)
            suctAt = suctionAt(sq, s, w, wm)
            val lim = suctLimit?.let { " из ${f1(it)}" } ?: ""
            if (QsuctMax <= 0.01) {
                warn += "Не возьмёт воду: всасывание уже без расхода ${f1(s0.total)} м" + (suctLimit?.let { " > паспортных ${f1(it)} м" } ?: ", NPSH ${f1(s0.npsha)} м") + " — нужен эжектор или погружной"
                QfreeRaw = Qfree; Qon = null; Qoff = null; Qp = null; Qfree = null; PatNeed = null
            } else if (QsuctMax < sq) {
                warn += "Всасывание ограничивает подачу до ${f2(QsuctMax)} м³/ч (на ${f2(sq)} м³/ч: ${f1(suctAt.total)} м$lim, NPSHa ${f1(suctAt.npsha)} м) — дальше срыв/кавитация"
                val qs = QsuctMax
                Qon = Qon?.let { minOf(it, qs) }; Qoff = Qoff?.let { minOf(it, qs) }; Qp = Qp?.let { minOf(it, qs) }
                if (Qfree != null && Qfree > qs) { QfreeRaw = Qfree; Qfree = qs }
                if (Qneed != null && Qneed > qs) PatNeed = null
            } else {
                ok += "Всасывание при ${f2(sq)} м³/ч: ${f1(suctAt.total)} м$lim, NPSHa ${f1(suctAt.npsha)} м; срыв выше ${f2(QsuctMax)} м³/ч"
                if (Qfree != null && Qfree > QsuctMax) { QfreeRaw = Qfree; Qfree = QsuctMax }
            }
            if (suctLimit == null) warn += "В насосе не задана паспортная глубина всасывания — проверка только по NPSH ≥ 0,5 м"
        }
        if (inst == "ejector" && pt == "ejector") {
            val L0 = levelAt(wm, w, 0.0) + (w.pumpElev ?: 0.0)
            val dmax = p.curves.orEmpty().mapNotNull { it.depth }.maxOrNull()
            if (dmax != null && L0 > dmax) warn += "Глубина всасывания ${f1(L0)} м больше паспортных ${f1(dmax)} м"
            val dEj = w.pumpDepth; val Lq = levelAt(wm, w, Qon ?: 0.0)
            if (dEj != null && Lq > dEj - (w.minAbove ?: 0.0)) warn += "При ${f2(Qon)} м³/ч уровень ${f1(Lq)} м опускается к эжектору (${f1(dEj)} м) — срыв"
        }
        if (Qoff == null || Qoff <= 0.05) warn += "Не достигает ${f1(relay.off)} бар отключения реле — насос не выключится"
        val tp = target.p ?: 0.0
        if (PatNeed == null || PatNeed < tp) {
            warn += if (PatNeed == null || PatNeed < 0) "Не даёт ${f1(Qneed)} м³/ч в этой системе"
            else "При ${f1(Qneed)} м³/ч у потребителя только ${f2(PatNeed)} бар (нужно ${f1(tp)})"
        } else ok += "${f1(Qneed)} м³/ч при ${f2(PatNeed)} бар"
        var Qsafe: Double? = null; var drainOn: Double? = null; var drainFree: Double? = null
        if (wm.ok && wm.Qsafe != null && Qon != null) {
            Qsafe = wm.Qsafe
            val src = wellModelText(wm, w)
            if (Qon > wm.Qsafe && wm.capped && Qon <= (wm.QsafeRaw ?: 0.0)) {
                warn += "Скважина не проверена на такой расход: насос в цикле берёт до ${f2(Qon)} м³/ч (сразу после включения реле на ${f1(relay.on)} бар), а замерами подтверждено ${f2(wm.Qsafe)} м³/ч. По модели скважина справилась бы (до ${f2(wm.QsafeRaw)}), но это экстраполяция — сделай замер на расходе около ${f1(Qon)} м³/ч." + src
            } else if (Qon > wm.Qsafe) {
                drainOn = drainHours(wm, Qon)
                warn += "Скважина не успевает: насос в цикле берёт до ${f2(Qon)} м³/ч (сразу после включения реле на ${f1(relay.on)} бар), а без опускания уровня ниже запаса над насосом скважина отдаёт ${f2(wm.Qsafe)} м³/ч — при непрерывной работе уровень дойдёт до запаса за ${fmtHours(drainOn)}." + src
            } else ok += "Скважина справится: насос в цикле берёт максимум ${f2(Qon)} м³/ч (сразу после включения реле на ${f1(relay.on)} бар), а скважина отдаёт без опускания уровня ниже запаса над насосом до ${f2(wm.Qsafe)} м³/ч." + src
            if (Qfree != null && Qfree > wm.Qsafe) drainFree = drainHours(wm, Qfree)
        }
        val levelOn = Qon?.let { levelAt(wm, w, it) }
        var gap: Double? = null
        val D = w.casingID; val d = p.diam
        if (inst == "sub" && D != null && d != null) {
            gap = (D - d) / 2
            if (gap < 0) warn += "Не влезает: Ø${f0(d)} > внутр. Ø${f0(D)}"
            else if (gap < (w.minGap ?: 8.0)) warn += "Зазор ${f1(gap)} мм на сторону — риск заклинивания (плюс кабель и хомуты)"
        }
        if (p.minQ != null && Qoff != null && Qoff < p.minQ) warn += "На отключении ${f2(Qoff)} м³/ч < мин. ${f2(p.minQ)} для охлаждения"
        var Phyd: Double? = null; var eta: Double? = null
        if (Qneed != null && PatNeed != null) {
            availH(p, Qneed, s, w, wm)?.let { h -> Phyd = 1000 * G * (Qneed / 3600) * h / 1000 }
            if (Phyd != null && p.powerKW != null && p.powerKW > 0) eta = Phyd / p.powerKW
        }
        val excess = PatNeed?.let { it - tp }
        val fitsCore = PatNeed != null && PatNeed >= tp && Qoff != null && Qoff > 0.05 && !(gap != null && gap < 0) &&
            !(inst == "surface" && QsuctMax != null && QsuctMax <= 0.01) && pt == inst
        return Eval(p, warn, ok, H0, Pshut, PshutHead, PshutBottom, pn, Qneed, PatNeed, Qon, Qoff, Qp, Qfree, QfreeRaw, Qsafe,
            drainOn, drainFree, gap, Phyd, eta, excess, warn.isEmpty(), fitsCore, QsuctMax, suctAt, suctLimit, levelOn)
    }

    data class Design(val Q: Double, val limited: Boolean, val Qtarget: Double?, val wm: WellModel, val sh: SysHead)

    // ---------- кавитация, отмеченная при замерах (поверхностный насос / абиссинка) ----------
    val CAV_NAMES = mapOf(0 to "нет", 1 to "лёгкая (шум, пузыри)", 2 to "сильная (рвёт струю, срыв)")

    /** по замерам скважины: предел подачи по факту; (kind ok|warn|crit, текст) */
    fun cavNote(meas: List<Measurement>): Pair<String, String>? {
        val ms = meas.filter { !it.deleted && it.q > 0 && it.cav != null }
        if (ms.isEmpty()) return null
        val bad = ms.filter { (it.cav ?: 0) >= 1 }.minByOrNull { it.q }
        val good = ms.filter { it.cav == 0 }.maxByOrNull { it.q }
        val df = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.forLanguageTag("ru"))
        fun d(m: Measurement) = df.format(java.util.Date(m.ts))
        if (bad != null && (good == null || good.q < bad.q)) {
            val name = (CAV_NAMES[bad.cav ?: 1] ?: "").substringBefore(" (")
            return (if ((bad.cav ?: 0) >= 2) "crit" else "warn") to ("По замеру ${d(bad)}: на ${f2(bad.q)} м³/ч уже $name кавитация — это предел иглы со всасыванием" +
                (good?.let { "; без кавитации было ${f2(it.q)} м³/ч (${d(it)})" } ?: "") +
                ". Больше не взять: прикрывай кран/реле ниже или сокращай подсос (трасса, клапан, насос ниже).")
        }
        if (good != null) return "ok" to ("По замеру ${d(good)}: ${f2(good.q)} м³/ч без кавитации" + (bad?.let { "; кавитация была на ${f2(it.q)} м³/ч (${d(it)})" } ?: "") + ".")
        return null
    }

    // ---------- понятное представление режима: вердикт + строки ----------
    data class RegimeView(val kind: String, val verdict: String, val rows: List<List<String>>)

    private fun relayHint(Pshut: Double?): String {
        if (Pshut == null || Pshut < 1.6) return ""
        val off = kotlin.math.floor((Pshut - 0.3) * 10) / 10
        val on = maxOf(1.0, Math.round((off - 0.8) * 10) / 10.0)
        return " С этим насосом реле можно настроить на ~${f1(on)}/${f1(off)} бар."
    }

    fun regimeView(e: Eval, p: Pump, S: WellProfile): RegimeView {
        val w = S.well
        val on = S.relay.on ?: 0.0; val off = S.relay.off ?: 0.0; val q = S.target.q; val tp = S.target.p ?: 0.0
        val inst = installOf(w); val pt = pumpType(p)
        val qOff = e.Qoff
        val v: Pair<String, String> = when {
            pt != inst -> "warn" to "Это ${TYPE_NAMES[pt]} насос, а в скважине стоит «${INSTALL_NAMES[inst]}» — цифры ниже условные."
            inst == "surface" && e.QsuctMax != null && e.QsuctMax <= 0.01 -> "crit" to "Воду не возьмёт: уровень глубже, чем насос может всосать. Нужен эжектор или погружной."
            e.Pshut == null || e.Pshut <= 0 -> "crit" to "Не поднимет воду до крана: напора не хватает даже без расхода."
            e.Qon == null -> "crit" to "Слабый для этой системы: даже при закрытом кране максимум ${f1(e.Pshut)} бар, а реле включает насос на ${f1(on)} бар. До ${f1(on)} бар не дотянет — будет молотить без остановки, напор слабый." + relayHint(e.Pshut)
            qOff == null || qOff <= 0.05 -> "warn" to "Включится на ${f1(on)} бар, но выше ${f1(e.Pshut)} бар не дожмёт — до ${f1(off)} не дойдёт и не выключится." + relayHint(e.Pshut)
            q != null && q > 0 && (e.PatNeed == null || e.PatNeed < tp) -> {
                val pn = e.PatNeed
                "warn" to ("Реле отработает, но ${f1(q)} м³/ч при ${f1(tp)} бар не даст: " +
                    (if (pn == null || pn < 0) "максимум ${f2(e.Qfree)} м³/ч даже на излив." else "на ${f1(q)} м³/ч будет только ${f2(pn)} бар") +
                    (if (e.Qp != null && pn != null && pn >= 0) ", а на ${f1(tp)} бар — ${f2(e.Qp)} м³/ч." else ""))
            }
            else -> {
                val base = (if (q != null && q > 0) "${f1(q)} м³/ч при ${f2(e.PatNeed)} бар" else "на ${f1(on)} бар ${f2(e.Qon)} м³/ч") + ", реле ${f1(on)}/${f1(off)} отрабатывает"
                if (e.warn.isNotEmpty()) "warn" to "По давлению и подаче подходит ($base), но есть замечания ниже." else "ok" to "Подходит: $base."
            }
        }
        fun lpm(x: Double) = "${f2(x)} м³/ч · ${f0(x * 1000 / 60)} л/мин"
        val rows = ArrayList<List<String>>()
        rows += listOf("Подача при ${f1(on)} бар (реле включает)", e.Qon?.let { lpm(it) } ?: "0 — столько не даёт")
        rows += listOf("Подача при ${f1(off)} бар (реле выключает)", qOff?.takeIf { it > 0.05 }?.let { lpm(it) } ?: "0 — до ${f1(off)} бар не дожимает")
        if (q != null && q > 0) rows += listOf("Давление при расходе ${f1(q)} м³/ч", e.PatNeed?.takeIf { it >= 0 }?.let { "${f2(it)} бар" } ?: "${f1(q)} м³/ч не даст")
        rows += listOf("Излив без давления (кран нараспашку)", e.Qfree?.let { lpm(it) + (e.QfreeRaw?.let { r0 -> " (по кривой ${f2(r0)}, режет всасывание)" } ?: "") } ?: "—")
        e.levelOn?.takeIf { !isAbyss(w) }?.let { rows += listOf("Уровень воды в скважине при работе", "${f1(it)} м от земли" + if (wellModel(w).ok) "" else " (динамика из настроек — без замера с расходом)") }
        e.QsuctMax?.let { rows += listOf("Всасывание: срыв выше", if (it > 0.01) "${f2(it)} м³/ч" else "не возьмёт") }
        e.suctAt?.let { rows += listOf("Высота всасывания в работе", "${f1(it.total)} м" + (e.suctLimit?.let { l -> " из ${f1(l)}" } ?: "") + ", NPSHa ${f1(it.npsha)} м") }
        e.drainOn?.let { rows += listOf("Скважину осушит до запаса за", fmtHours(it)) }
        e.Pshut?.let { rows += listOf("Максимум давления в кране (всё закрыто)", "${f1(it)} бар") }
        e.PshutBottom?.let { pb -> rows += listOf("Давление в трубе у насоса (всё закрыто)", "${f1(pb)} бар" + (e.pipePN?.let { pn -> if (pb > pn) " — больше PN$pn трубы!" else " — труба PN$pn выдержит" } ?: "")) }
        p.powerKW?.let { rows += listOf("Потребляемая мощность P1", "${f2(it)} кВт" + (e.eta?.let { x -> " · КПД агрегата ≈ ${f0(x * 100)}%" } ?: "")) }
        return RegimeView(v.first, v.second, rows)
    }

    fun designPoint(S: WellProfile): Design {
        val wm = wellModel(S.well)
        var Q = S.target.q ?: 0.0; var limited = false
        if (wm.ok && wm.Qsafe != null && wm.Qsafe < Q) { Q = wm.Qsafe; limited = true }
        return Design(Q, limited, S.target.q, wm, systemHead(Q, S.target.p ?: 0.0, S.sys, S.well, wm))
    }

    fun rankPumps(pumps: List<Pump>, S: WellProfile): List<Eval> {
        fun grp(e: Eval) = when { e.excess == null -> 2; e.excess < 0 -> 1; else -> 0 }
        return pumps.map { evaluatePump(it, S) }.sortedWith(Comparator { a, b ->
            var c = b.fits.compareTo(a.fits); if (c != 0) return@Comparator c
            c = b.fitsCore.compareTo(a.fitsCore); if (c != 0) return@Comparator c
            c = grp(a).compareTo(grp(b)); if (c != 0) return@Comparator c
            c = when (grp(a)) { 0 -> a.excess!!.compareTo(b.excess!!); 1 -> b.excess!!.compareTo(a.excess!!); else -> 0 }
            if (c != 0) c else a.pump.name.compareTo(b.pump.name)
        })
    }

    data class Alt(val pipe: Pipe, val sh: SysHead, val hf: Double, val current: Boolean)

    fun pipeAlternatives(S: WellProfile): List<Alt> {
        val dp = designPoint(S)
        return PIPES.filter { it.pn != null }.map { p ->
            val s = S.sys.copy(riserPipe = p.id, horizPipe = p.id)
            val sh = systemHead(dp.Q, S.target.p ?: 0.0, s, S.well, dp.wm)
            Alt(p, sh, sh.hfRiser + sh.hfHoriz + sh.local, p.id == S.sys.riserPipe && p.id == S.sys.horizPipe)
        }
    }


    // ---------- аппроксимация по паспортным числам: H = Hmax·(1 − (Q/Qmax)^n); погружные n=2, поверхностные n=1,4 ----------
    fun approxCurve(hmax: Double?, qmax: Double?, type: String, qnom: Double? = null, hnom: Double? = null, hAtQmax: Double? = null): Pair<List<List<Double>>, String>? {
        if (hmax == null || qmax == null || hmax <= 0 || qmax <= 0) return null
        var n = if (type == "sub") 2.0 else 1.4
        var how = "≈ по Hmax ${f0(hmax)} м и Qmax ${f2(qmax)} м³/ч (n=${f1(n)})"
        if (qnom != null && hnom != null && qnom > 0 && qnom < qmax && hnom > 0 && hnom < hmax && (hAtQmax ?: 0.0) == 0.0) {
            n = ln(1 - hnom / hmax) / ln(qnom / qmax); how = "≈ по Hmax ${f0(hmax)}, номиналу ${f2(qnom)}/${f0(hnom)}, Qmax ${f2(qmax)} (n=${f2(n)})"
        }
        val top = hAtQmax ?: 0.0
        return (0..10).map { i -> listOf(Math.round(qmax * i / 10 * 1000) / 1000.0, Math.round((top + (hmax - top) * (1 - (i / 10.0).pow(n))) * 10) / 10.0) } to how
    }

    // ---------- поиск по названию: «4 142», «4-142», «4142», «водомет 55» ----------
    fun normName(t: String) = t.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9.]+"), " ").trim()
    fun matchName(name: String, q: String): Boolean {
        val n = normName(name); val nc = n.replace(" ", "")
        return normName(q).split(' ').filter { it.isNotEmpty() }.all { n.contains(it) || nc.contains(it) }
    }

    // ---------- координаты ----------
    data class LL(val lat: Double, val lon: Double)
    fun parseLatLon(t: String): LL? {
        val n = Regex("-?\\d+(\\.\\d+)?").findAll(t.replace(Regex("(\\d),(\\d)"), "$1.$2")).map { it.value.toDouble() }.toList()
        if (n.size < 2 || kotlin.math.abs(n[0]) > 90 || kotlin.math.abs(n[1]) > 180) return null
        return LL(n[0], n[1])
    }
    fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = PI / 180; val dLat = (bLat - aLat) * r; val dLon = (bLon - aLon) * r
        val h = kotlin.math.sin(dLat / 2).pow(2) + kotlin.math.cos(aLat * r) * kotlin.math.cos(bLat * r) * kotlin.math.sin(dLon / 2).pow(2)
        return 2 * 6371008.8 * kotlin.math.asin(sqrt(h))
    }

    // ---------- формат ----------
    private val RU = Locale.forLanguageTag("ru-RU")
    fun fx(x: Double?, d: Int): String = if (x == null || x.isNaN() || x.isInfinite()) "—" else String.format(RU, "%,.${d}f", x)
    fun f0(x: Double?) = fx(x, 0)
    fun f1(x: Double?) = fx(x, 1)
    fun f2(x: Double?) = fx(x, 2)
    fun fv(x: Double) = if (abs(x % 1.0) < 1e-9) f0(x) else f1(x)   // объёмы: 10 / 12,5

    fun fmtHours(h: Double?): String {
        if (h == null) return "—"
        if (h.isInfinite()) return "никогда"
        val m = h * 60
        return when { m < 1 -> "${f0(m * 60)} с"; m < 90 -> "${f0(m)} мин"; else -> "${f1(h)} ч" }
    }

    fun fmtDur(sec: Double): String {
        if (sec.isInfinite() || sec.isNaN()) return "—"
        val m = (sec / 60).toInt(); val s = sec - m * 60
        if (m >= 60) return String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s.toInt())
        return String.format(Locale.US, "%02d:%04.1f", m, s)
    }

    fun minOrNull(a: Double?, b: Double?) = if (a == null) b else if (b == null) a else min(a, b)
}
