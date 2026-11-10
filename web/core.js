// QDebit core — чистая логика без DOM. Этот файл — эталон для порта на Android (Kotlin).
// Единицы: Q — м³/ч, H — м вод. ст., P — бар, D — мм, L — м, V — л, t — с.
(function (root) {
  'use strict';
  const VERSION = '1.7.1';
  const G = 9.80665;
  const M_PER_BAR = 10.197;           // 1 бар = 10.197 м вод. ст. (пресная вода)

  // ---------- замер ----------
  function runRate(r) { return r.t > 0 ? r.v / r.t : 0; }            // л/с
  function units(lps) {
    return { lps, lpm: lps * 60, m3h: lps * 3.6, m3d: lps * 86.4,
             secPerM3: lps > 0 ? 1000 / lps : Infinity };
  }
  // Сводка по серии: средний дебит = ΣV/Σt (взвешенный по времени — корректнее среднего от скоростей)
  function seriesStats(runs, reactionSec) {
    const rs = (runs || []).filter(r => r && r.v > 0 && r.t > 0);
    if (!rs.length) return null;
    const rx = reactionSec == null ? 0.3 : reactionSec;
    const V = rs.reduce((s, r) => s + r.v, 0);
    const T = rs.reduce((s, r) => s + r.t, 0);
    const rates = rs.map(runRate);
    const mean = rates.reduce((s, x) => s + x, 0) / rates.length;
    const sd = rates.length > 1
      ? Math.sqrt(rates.reduce((s, x) => s + (x - mean) ** 2, 0) / (rates.length - 1)) : 0;
    // погрешность реакции: старт+стоп по ±rx, на серии усредняется как 1/√n
    const errRun = rs.map(r => (rx * Math.SQRT2) / r.t);
    const errSeries = (rx * Math.SQRT2 * Math.sqrt(rs.length)) / T;
    return {
      n: rs.length, V, T,
      avg: units(V / T),
      mean: units(mean),
      min: units(Math.min(...rates)), max: units(Math.max(...rates)),
      cv: mean > 0 ? sd / mean : 0,           // коэффициент вариации
      errRun, errSeries,                        // относительная погрешность (доля)
      perRun: rs.map((r, i) => ({ v: r.v, t: r.t, ...units(rates[i]), dev: mean ? rates[i] / mean - 1 : 0 }))
    };
  }

  // ---------- гидравлика труб ----------
  // ПЭ100 по ГОСТ 18599: SDR17 = PN10, SDR11 = PN16. id = OD − 2·стенка.
  const PIPES = [
    { id: 'pe25-16', name: 'ПНД 25×2,3 PN16', od: 25, wall: 2.3, pn: 16 },
    { id: 'pe32-10', name: 'ПНД 32×2,0 PN10', od: 32, wall: 2.0, pn: 10 },
    { id: 'pe32-16', name: 'ПНД 32×3,0 PN16', od: 32, wall: 3.0, pn: 16 },
    { id: 'pe40-10', name: 'ПНД 40×2,4 PN10', od: 40, wall: 2.4, pn: 10 },
    { id: 'pe40-16', name: 'ПНД 40×3,7 PN16', od: 40, wall: 3.7, pn: 16 },
    { id: 'pe50-10', name: 'ПНД 50×3,0 PN10', od: 50, wall: 3.0, pn: 10 },
    { id: 'pe50-16', name: 'ПНД 50×4,6 PN16', od: 50, wall: 4.6, pn: 16 },
  ].map(p => ({ ...p, idmm: +(p.od - 2 * p.wall).toFixed(1), rough: 0.007 }))
    // сталь ВГП ГОСТ 3262 (обыкновенные) — абиссинки и всасывающие линии; шероховатость б/у трубы 0,15 мм
    .concat([
      { id: 'st20', name: 'ВГП ¾″ (Ду20)', od: 26.8, wall: 2.8 },
      { id: 'st25', name: 'ВГП 1″ (Ду25)', od: 33.5, wall: 3.2 },
      { id: 'st32', name: 'ВГП 1¼″ (Ду32)', od: 42.3, wall: 3.2 },
      { id: 'st40', name: 'ВГП 1½″ (Ду40)', od: 48.0, wall: 3.5 },
      { id: 'st50', name: 'ВГП 2″ (Ду50)', od: 60.0, wall: 3.5 },
    ].map(p => ({ ...p, pn: null, idmm: +(p.od - 2 * p.wall).toFixed(1), rough: 0.15 })));
  function pipeById(id) { return PIPES.find(p => p.id === id) || null; }
  // кинематическая вязкость воды, м²/с (формула Пуазейля)
  function nu(T) { return 1.792e-6 / (1 + 0.0337 * T + 0.000221 * T * T); }
  // потери на трение по Дарси–Вейсбаху; f — Свами–Джейн (турб.), 64/Re (лам.), линейная сшивка 2300…4000
  function friction(Q, Dmm, L, roughMm, T) {
    if (!(Q > 0) || !(Dmm > 0) || !(L > 0)) return { v: 0, re: 0, f: 0, hf: 0, perM: 0 };
    const D = Dmm / 1000, A = Math.PI * D * D / 4;
    const v = (Q / 3600) / A;
    const re = v * D / nu(T == null ? 8 : T);
    const eps = (roughMm == null ? 0.007 : roughMm) / 1000;
    const fl = 64 / re;
    const ft = 0.25 / Math.log10(eps / (3.7 * D) + 5.74 / Math.pow(re, 0.9)) ** 2;
    let f;
    if (re < 2300) f = fl;
    else if (re > 4000) f = ft;
    else { const k = (re - 2300) / 1700; f = 64 / 2300 * (1 - k) + 0.25 / Math.log10(eps / (3.7 * D) + 5.74 / Math.pow(4000, 0.9)) ** 2 * k; }
    const perM = f * v * v / (2 * G * D);
    return { v, re, f, perM, hf: perM * L };
  }

  // ---------- скважина ----------
  // Линейная модель понижения: s = Q / q, q — удельный дебит (м³/ч на 1 м понижения).
  /*
   * Модель скважины: понижение s(Q) = B·Q + C·Q² (Джейкоб): B·Q — потери в пласте (линейные),
   * C·Q² — потери на входе в скважину (фильтр, трещины, колонна), растут квадратично.
   * B, C подбираются по замерам скважины (fitWell) и хранятся в well.fitB/fitC; одна точка — только B (прямая).
   * Без подбора — прямая по «динамика при расходе» из формы.
   */
  function wellModel(w) {
    const o = { ok: false };
    const ab = isAbyss(w);
    const st = num(w.static), dyn = ab ? null : num(w.dyn), dq = ab ? null : num(w.dynQ), hp = ab ? null : num(w.pumpDepth);
    const minA = num(w.minAbove) ?? 0;
    o.static = st; o.pumpDepth = hp; o.abyss = ab;
    if (!ab && st != null && num(w.fitB) > 0) {
      o.B = num(w.fitB); o.C = Math.max(0, num(w.fitC) ?? 0); o.kind = w.fitKind || (o.C > 0 ? 'step' : 'one');
      o.n = num(w.fitN) ?? 1; o.testQ = num(w.fitQmax) ?? (dq || 1);
      o.ok = true;
    } else if (st != null && dyn != null && dq != null && dyn > st && dq > 0) {
      o.B = (dyn - st) / dq; o.C = 0; o.kind = 'one'; o.n = 1; o.testQ = dq;
      o.ok = true;
    }
    if (o.ok) {
      o.sAt = Q => o.B * Q + o.C * Q * Q;
      o.testDyn = st + o.sAt(o.testQ);
      o.q = o.testQ / o.sAt(o.testQ);                 // удельный дебит при расходе замера, м³/ч на 1 м
    }
    if (st != null && hp != null) {
      o.limitLevel = hp - minA;                     // ниже этого уровня опускаться нельзя
      o.maxDrawdown = o.limitLevel - st;
      if (o.maxDrawdown <= 0) o.badDepth = true;
      if (o.ok && o.maxDrawdown > 0) {
        const dd = o.maxDrawdown;
        o.QsafeRaw = o.C > 0 ? (-o.B + Math.sqrt(o.B * o.B + 4 * o.C * dd)) / (2 * o.C) : dd / o.B;
        // экстраполяция честна недалеко от замеров: прямая по одной точке — до 1,5×, парабола по ступеням — до 2× максимального расхода
        o.Qvalid = o.testQ * (o.kind === 'step' ? 2 : 1.5);
        o.capped = o.QsafeRaw > o.Qvalid;
        o.Qsafe = Math.min(o.QsafeRaw, o.Qvalid);
      }
      // запас воды в колонне от статики до предельного уровня (за вычетом напорной трубы)
      const D = num(w.casingID), pod = num(w.riserOD) ?? 0;
      if (D && o.maxDrawdown > 0) {
        o.area = Math.PI * ((D / 1000) ** 2 - (pod / 1000) ** 2) / 4;   // м²
        o.reserveL = o.area * o.maxDrawdown * 1000;
      }
    }
    return o;
  }
  // уровень воды при откачке Q (м от поверхности); без модели — фиксированный известный уровень
  function levelAt(wm, w, Q) {
    // абиссинка: труба от фильтра до насоса целиком под водой (под разрежением) — поднимаем всегда со статики
    if (isAbyss(w)) return num(w.static) ?? 0;
    if (wm.ok) return wm.static + wm.sAt(Q);
    const dyn = num(w.dyn), st = num(w.static);
    if (Q <= 0 && st != null) return st;
    return dyn ?? st ?? 0;
  }
  // приток из пласта при понижении s (обратная к s(Q))
  function inflowAt(wm, s) { return s <= 0 ? 0 : wm.C > 0 ? (-wm.B + Math.sqrt(wm.B * wm.B + 4 * wm.C * s)) / (2 * wm.C) : s / wm.B; }
  // время до срыва (уровень дойдёт до предельного) при подаче Qp: A·ds/dt = Qp − приток(s), часы
  function drainHours(wm, Qp) {
    if (!wm.ok || !wm.area || !(wm.maxDrawdown > 0) || !(Qp > 0)) return null;
    if (Qp <= inflowAt(wm, wm.maxDrawdown)) return Infinity;      // не осушит
    const N = 400, h = wm.maxDrawdown / N; let t = 0;
    for (let i = 0; i < N; i++) { const sm = (i + 0.5) * h; t += wm.area * h / (Qp - inflowAt(wm, sm)); }
    return t;
  }
  // словами: откуда модель скважины и безопасный дебит
  function wellModelText(wm, w) {
    if (!wm.ok) return '';
    const minA = num(w.minAbove) ?? 0;
    const how = wm.kind === 'step'
      ? `по ${wm.n} замерам на разных расходах: понижение = ${fx(wm.B, 3)}·Q + ${fx(wm.C, 3)}·Q² (вторая часть — потери на входе в скважину, растут быстрее расхода)`
      : `по одному расходу: ${f2(wm.testQ)} м³/ч при понижении ${f1(wm.sAt(wm.testQ))} м = ${f2(wm.q)} м³/ч на 1 м (прямая — на больших расходах завышает)`;
    let t = ` Безопасный дебит ${f2(wm.Qsafe)} м³/ч — ${how}; уровню можно опуститься на ${f1(wm.maxDrawdown)} м (насос на ${f1(wm.pumpDepth)} м − запас ${f1(minA)} м − статика ${f1(wm.static)}).`;
    if (wm.capped) t += ` По модели вышло бы ${f2(wm.QsafeRaw)}, но дальше ${wm.kind === 'step' ? '2' : '1,5'}× от максимального расхода замеров (${f2(wm.testQ)}) экстраполяция нечестная — взято ${f2(wm.Qvalid)}. ` +
      (wm.kind === 'step' ? 'Чтобы поднять предел — замер на большем расходе.' : 'Для честного предела — 2–3 замера на разных расходах (прикрывая кран): уровнемером или по манометру.');
    return t;
  }
  // динамика по манометру: напор насоса по кривой − давление − трение (до манометра); только погружной с кривой
  function dynFromPressure(wp, pump, Q, P, at) {
    const w = wp.well, s = wp.sys;
    if (!pump || installOf(w) !== 'sub' || !(Q > 0) || P == null) return null;
    const H = pumpH(pump, Q); if (H == null) return null;
    const sh = systemHead(Q, P, s, w, { ok: false });
    const need = at === 'head' ? sh.hp + sh.hfRiser + sh.localRiser : sh.total - sh.level;
    return H - need;
  }
  // точки «расход → понижение» для подбора модели: форма + замеры (уровнемер или манометр)
  function wellPoints(wp, meas, pumpById) {
    const w = wp.well, st = num(w.static), pts = [], rejected = [];
    if (st == null || isAbyss(w)) return { pts, rejected };
    const pump = wp.pumpId ? pumpById(wp.pumpId) : null, cp = num(w.cpSet);
    for (const m of (meas || [])) {
      if (!m || m.deleted || m.wellId !== wp.id || !(m.q > 0)) continue;
      let dyn = num(m.dynLevel), src = 'уровнемер';
      if (dyn == null && num(m.press) != null) {
        if (cp != null && num(m.press) >= cp - 0.2) { rejected.push({ m, why: `давление ${f1(num(m.press))} бар — насос держал уставку ${f1(cp)} бар (обороты снижены), кривая не годится` }); continue; }
        dyn = dynFromPressure(wp, pump, m.q, num(m.press), m.pressAt);
        src = `манометр ${f1(num(m.press))} бар (${m.pressAt === 'head' ? 'оголовок' : 'дом'})`;
        if (dyn == null) { rejected.push({ m, why: pump ? 'нет кривой насоса или насос не погружной' : 'у скважины не выбран насос — по манометру не посчитать' }); continue; }
      }
      if (dyn == null) continue;
      const sdd = dyn - st;
      if (sdd <= 0.05) { rejected.push({ m, why: `динамика ${f1(dyn)} м не ниже статики ${f1(st)} — проверь статику или давление` }); continue; }
      pts.push({ q: m.q, s: sdd, dyn, src, ts: m.ts, id: m.id, min: num(m.pumpingMin) });
    }
    const dq = num(w.dynQ), dyn = num(w.dyn);
    if (dq > 0 && dyn != null && dyn > st && !pts.some(p => Math.abs(p.q - dq) < 0.005 && Math.abs(p.dyn - dyn) < 0.05))
      pts.push({ q: dq, s: dyn - st, dyn, src: 'форма', ts: null, id: null });
    return { pts, rejected };
  }
  // подбор B, C по точкам: s/Q = B + C·Q (метод Бирсхенка); одна точка или один расход — прямая
  function fitWell(pts) {
    const P = (pts || []).filter(p => p.q > 0 && p.s > 0);
    if (!P.length) return null;
    const qs = P.map(p => p.q), qmax = Math.max(...qs), qmin = Math.min(...qs);
    const lin = () => ({ B: P.reduce((a, p) => a + p.s, 0) / P.reduce((a, p) => a + p.q, 0), C: 0, kind: 'one', n: P.length, Qmax: qmax });
    if (P.length < 2 || qmax / qmin < 1.25) return lin();
    const xs = P.map(p => p.q), ys = P.map(p => p.s / p.q), n = P.length;
    const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
    let sxy = 0, sxx = 0; for (let i = 0; i < n; i++) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) ** 2; }
    let C = sxx > 0 ? sxy / sxx : 0, B = my - C * mx;
    if (C < 0 || B <= 0) return { ...lin(), kind: 'one', note: 'точки не легли на параболу (разброс замеров) — взята прямая' };
    return { B, C, kind: 'step', n, Qmax: qmax };
  }

  // ---------- система ----------
  // Установка насоса (w.install): 'sub' — погружной; 'surface' — поверхностный самовсасывающий / станция
  // (труба «в скважине» = всасывающая); 'ejector' — поверхностный с выносным эжектором (кривые паспорта по глубине всасывания).
  function pipeOf(id, customID) { return id === 'custom' ? { idmm: num(customID), rough: 0.007, pn: null } : pipeById(id); }
  function installOf(w) { return isAbyss(w) ? 'surface' : (w.install || 'sub'); }
  function isAbyss(w) { return !!w && w.kind === 'abyssinian'; }
  // Требуемый напор насоса при подаче Q и давлении P у потребителя (полный напор насоса).
  function systemHead(Q, P, s, w, wm) {
    const T = num(s.tempC) ?? 8;
    const riser = pipeOf(s.riserPipe, s.riserID), horiz = pipeOf(s.horizPipe, s.horizID);
    // абиссинка: ниже зеркала воду подпирает пласт — трение считаем только от зеркала до насоса (статика + отметка насоса)
    const lr = isAbyss(w) ? Math.max(0, (num(w.static) ?? 0) + (num(w.pumpElev) ?? 0)) : (num(s.riserLen) ?? num(w.pumpDepth) ?? 0);
    const lh = num(s.horizLen) ?? 0;
    const fr0 = riser ? friction(Q, riser.idmm, lr, riser.rough, T) : friction(0);
    // всасывающая трасса от скважины до поверхностного насоса (своя труба или как вертикаль)
    const ls = installOf(w) === 'surface' ? (num(s.suctLen) ?? 0) : 0;
    const sp = s.suctPipe ? pipeOf(s.suctPipe, s.suctID) : riser;
    const fs = (sp && ls > 0) ? friction(Q, sp.idmm, ls, sp.rough, T) : friction(0);
    const fr = { ...fr0, hf: fr0.hf + fs.hf };
    const fh = horiz ? friction(Q, horiz.idmm, lh, horiz.rough, T) : friction(0);
    const kLoc = (num(s.localPct) ?? 0) / 100;
    const local = (fr.hf + fh.hf) * kLoc;
    const lvl = levelAt(wm, w, Q);
    const dz = num(s.dz) ?? 0;
    const hp = (P || 0) * M_PER_BAR;
    return { Q, level: lvl, dz, hp, hfRiser: fr.hf, hfHoriz: fh.hf, local, localRiser: fr.hf * kLoc, localHoriz: fh.hf * kLoc,
             vRiser: fr.v, vHoriz: fh.v, total: lvl + dz + hp + fr.hf + fh.hf + local };
  }
  // Всасывание поверхностного насоса: геометрическая высота + потери во всасывающей линии; NPSHa по атмосфере и пару.
  function hAtm(alt) { return 10.33 * Math.exp(-(num(alt) ?? 150) / 8434); }
  function hVap(T) { const t = T ?? 8; return 0.6113 * Math.exp(17.27 * t / (t + 237.3)) / 9.81; }   // м вод. ст.
  function suctionAt(Q, s, w, wm) {
    const sh = systemHead(Q, 0, s, w, wm);
    const e = num(w.pumpElev) ?? 0;
    const lift = sh.level + e;                         // от уровня воды до оси насоса
    const loss = sh.hfRiser + sh.localRiser;
    const npsha = hAtm(effAlt(w)) - hVap(num(s.tempC) ?? 8) - lift - loss;
    return { lift, loss, total: lift + loss, npsha };
  }
  // высота устья над морем: своя отметка (нивелир) → модель рельефа по координатам → «высота над морем» из формы
  function groundZ(w) { return num(w.elev) ?? num(w.elevDem); }
  function effAlt(w) { return groundZ(w) ?? num(w.alt); }

  // ---------- геология и перепады между скважинами ----------
  const ROCKS = [
    ['soil', 'почва / насыпь', '#8d6e63'], ['loam', 'суглинок', '#c8a165'], ['clay', 'глина', '#a1887f'],
    ['sandyloam', 'супесь', '#e2c98f'], ['sand', 'песок', '#f2d77a'], ['gravel', 'гравий / галька', '#bdb3a0'],
    ['limestone', 'известняк', '#b0bec5'], ['marl', 'мергель', '#cfd8dc'], ['other', 'другое', '#9e9e9e'],
  ];
  const ROCK_NAME = Object.fromEntries(ROCKS.map(r => [r[0], r[1]]));
  const ROCK_COLOR = Object.fromEntries(ROCKS.map(r => [r[0], r[2]]));
  const AQ_NAMES = { sand: 'песок', limestone: 'известняк (артезианка)' };
  function aquiferOf(w) { return isAbyss(w) ? 'sand' : (w.kind === 'dug' ? null : (w.aquifer || null)); }
  // абсолютные отметки: устье, статический уровень, кровля водоносного (песок/известняк)
  function zInfo(wp) {
    const w = wp.well, Z = groundZ(w);
    const src = num(w.elev) != null ? 'нивелир' : num(w.elevDem) != null ? 'рельеф' : null;
    const st = num(w.static), top = num(w.aqTop);
    return { id: wp.id, name: wp.name, Z, src, aquifer: aquiferOf(w), horizon: num(w.horizon), static: st, aqTop: top,
      Zw: Z != null && st != null ? Z - st : null, Ztop: Z != null && top != null ? Z - top : null,
      depth: num(w.depth), lat: wp.lat, lon: wp.lon };
  }
  // попарно: расстояние, перепад устья, зеркала, кровли; уклон зеркала ‰
  function wellPairs(wps) {
    const zs = wps.map(zInfo), out = [];
    for (let i = 0; i < zs.length; i++) for (let j = i + 1; j < zs.length; j++) {
      const a = zs[i], b = zs[j];
      const d = a.lat != null && b.lat != null ? distM(a, b) : null;
      const dd = (x, y) => (x != null && y != null ? y - x : null);
      const dZw = dd(a.Zw, b.Zw);
      out.push({ a, b, dist: d, dZ: dd(a.Z, b.Z), dZw, dZtop: dd(a.Ztop, b.Ztop), dStatic: dd(a.static, b.static),
        dTop: dd(a.aqTop, b.aqTop), gradW: d && dZw != null ? dZw / d * 1000 : null,
        diffHorizon: a.horizon != null && b.horizon != null && a.horizon !== b.horizon,
        sameAq: !!(a.aquifer && a.aquifer === b.aquifer && !(a.horizon != null && b.horizon != null && a.horizon !== b.horizon)) });
    }
    return out;
  }
  // расстояние: до 1000 м — в метрах, дальше — в километрах
  function fmtDist(m) { return m == null ? '—' : m < 1000 ? `${f0(m)} м` : `${fx(m / 1000, m < 10000 ? 2 : 1)} км`; }
  // отметка: своя (нивелир) — до сантиметров; по рельефу — до метра (точнее модель не даёт)
  function fmtZ(z, src) { return z == null ? '—' : src === 'рельеф' ? `≈${f0(z)}` : f2(z); }
  // сравнение пары словами: строки [что, A, B, разница]; верх/низ — по абсолютным отметкам, статика — глубже/мельче
  function pairRows(p) {
    const n = p.b.name, rel = p.a.src === 'рельеф' || p.b.src === 'рельеф', dp = rel ? 0 : 2;
    const word = (d, up, down, digits) => d == null ? '—' : Math.abs(d) < (digits ? 0.05 : 0.5) ? 'одинаково' : `${n}: ${d > 0 ? up : down} на ${fx(Math.abs(d), digits)} м`;
    const zf = (z, s) => fmtZ(z, s);
    const rows = [
      ['Устье, м абс.', zf(p.a.Z, p.a.src), zf(p.b.Z, p.b.src), word(p.dZ, 'выше', 'ниже', dp)],
      ['Статика, м от устья', p.a.static != null ? f1(p.a.static) : '—', p.b.static != null ? f1(p.b.static) : '—', word(p.dStatic, 'глубже', 'мельче', 1)],
      ['Зеркало воды, м абс.', zf(p.a.Zw, rel ? 'рельеф' : null), zf(p.b.Zw, rel ? 'рельеф' : null), word(p.dZw, 'выше', 'ниже', dp)],
    ];
    if (p.a.aqTop != null || p.b.aqTop != null) {
      rows.push(['Кровля водоносного, м от устья', p.a.aqTop != null ? f1(p.a.aqTop) : '—', p.b.aqTop != null ? f1(p.b.aqTop) : '—', word(p.dTop, 'глубже', 'мельче', 1)]);
      rows.push(['Кровля водоносного, м абс.', zf(p.a.Ztop, rel ? 'рельеф' : null), zf(p.b.Ztop, rel ? 'рельеф' : null), word(p.dZtop, 'выше', 'ниже', dp)]);
    }
    const aqn = z => z.aquifer ? AQ_NAMES[z.aquifer] + (z.horizon ? `, ${z.horizon}-й горизонт` : '') : 'не задан';
    rows.unshift(['Водоносный', aqn(p.a), aqn(p.b), p.sameAq ? 'один' : (p.a.aquifer && p.b.aquifer ? 'разные' : '—')]);
    let flow;
    if (!p.sameAq) flow = p.a.aquifer && p.b.aquifer ? (p.diffHorizon ? 'Разные горизонты — уровни воды не сравнимы, уклон не считается.' : 'Разные водоносные — уровни воды не сравнимы, уклон не считается.') : 'Уклон считается, когда у обеих задан один и тот же водоносный.';
    else if (p.gradW == null) flow = 'Для уклона нужны координаты и отметки обеих скважин.';
    else if (Math.abs(p.gradW) < 0.5) flow = 'Зеркало ровное — заметного потока между ними нет.';
    else flow = `Уклон зеркала ${f1(Math.abs(p.gradW))}‰ (${fx(Math.abs(p.dZw), dp)} м на ${fmtDist(p.dist)}) — вода течёт к «${p.dZw > 0 ? p.a.name : p.b.name}».`;
    if (rel) flow += ' Отметки по рельефу ±2–5 м: перепады меньше ~5 м между ними ненадёжны — для точного сравнения впиши свои отметки устья.';
    return { rows, flow };
  }

  // Наибольшая подача, при которой всасывание укладывается в паспортную глубину (или NPSHa ≥ 0,5 м без паспорта)
  function suctionQmax(pump, s, w, wm, qTop) {
    const lim = num(pump.suction);
    const ok = Q => { const x = suctionAt(Q, s, w, wm); return lim != null ? x.total <= lim : x.npsha >= 0.5; };
    if (!ok(0)) return 0;
    if (ok(qTop)) return qTop;
    let a = 0, b = qTop;
    for (let i = 0; i < 50; i++) { const m = (a + b) / 2; if (ok(m)) a = m; else b = m; }
    return a;
  }

  // ---------- насосы ----------
  function normPoints(pts) {
    return (pts || []).map(p => [num(p[0]), num(p[1])]).filter(p => p[0] != null && p[1] != null)
      .sort((a, b) => a[0] - b[0]);
  }
  // "0:183, 1:183; 2 173" — пары Q H; строка «@20» начинает кривую для глубины всасывания 20 м (эжекторные)
  function parsePoints(text) {
    const nums = String(text || '').split(/\n/).filter(l => !/^\s*@/.test(l)).join(' ').replace(/,(?=\d)/g, '.').match(/-?\d+(\.\d+)?/g) || [];
    const pts = [];
    for (let i = 0; i + 1 < nums.length; i += 2) pts.push([+nums[i], +nums[i + 1]]);
    return normPoints(pts);
  }
  function parseCurves(text) {
    const out = []; let cur = null;
    for (const line of String(text || '').split(/\n/)) {
      const m = line.match(/^\s*@\s*(\d+(?:[.,]\d+)?)/);
      if (m) { cur = { depth: +m[1].replace(',', '.'), points: [] }; out.push(cur); continue; }
      const nums = line.replace(/,(?=\d)/g, '.').match(/-?\d+(\.\d+)?/g) || [];
      for (let i = 0; i + 1 < nums.length; i += 2) { if (!cur) { cur = { depth: null, points: [] }; out.push(cur); } cur.points.push([+nums[i], +nums[i + 1]]); }
    }
    return out.filter(c => c.points.length).map(c => ({ depth: c.depth, points: normPoints(c.points) })).sort((a, b) => (a.depth ?? 0) - (b.depth ?? 0));
  }
  function curvesToText(p) {
    if (Array.isArray(p.curves) && p.curves.length) return p.curves.map(c => `@${c.depth}\n` + c.points.map(x => `${x[0]} ${x[1]}`).join('\n')).join('\n');
    return (p.points || []).map(x => `${x[0]} ${x[1]}`).join('\n');
  }
  function interp(pts, Q) {
    if (!pts.length) return null;
    if (Q <= pts[0][0]) return pts[0][1];
    for (let i = 1; i < pts.length; i++) if (Q <= pts[i][0]) { const [q0, h0] = pts[i - 1], [q1, h1] = pts[i]; return h0 + (h1 - h0) * (Q - q0) / (q1 - q0); }
    return null;
  }
  const PTS = new WeakMap();   // кэш отсортированных точек (не пачкаем объект — он уходит в JSON)
  function pts(pump) { let p = PTS.get(pump); if (!p) { p = normPoints(pump.points); PTS.set(pump, p); } return p; }
  function pumpType(p) { return p.type || 'sub'; }
  // напор по паспорту без учёта глубины (для погружных/поверхностных; для эжекторных — по самой мелкой кривой)
  function pumpH(pump, Q) {
    if (pumpType(pump) === 'ejector' && pump.curves && pump.curves.length) return interp(normPoints(pump.curves[0].points), Q);
    return interp(pts(pump), Q);
  }
  function pumpQmax(pump) {
    if (pumpType(pump) === 'ejector' && pump.curves && pump.curves.length) return Math.max(...pump.curves.map(c => { const p = normPoints(c.points); return p.length ? p[p.length - 1][0] : 0; }));
    const p = pts(pump); return p.length ? p[p.length - 1][0] : 0;
  }
  // эжекторный: напор на выходе насоса при подаче Q и глубине всасывания L — линейно между кривыми соседних глубин
  function ejectorH(pump, Q, L) {
    const cs = (pump.curves && pump.curves.length) ? pump.curves.filter(c => c.depth != null) : [];
    if (!cs.length) return interp(pts(pump), Q);
    const d = cs.map(c => c.depth);
    if (L <= d[0]) return interp(normPoints(cs[0].points), Q);
    if (L > d[d.length - 1]) return null;                        // глубже паспорта — не возьмёт
    for (let i = 1; i < cs.length; i++) if (L <= d[i]) {
      const h0 = interp(normPoints(cs[i - 1].points), Q), h1 = interp(normPoints(cs[i].points), Q);
      if (h0 == null || h1 == null) return null;
      return h0 + (h1 - h0) * (L - d[i - 1]) / (d[i] - d[i - 1]);
    }
    return null;
  }
  // Располагаемый и требуемый напоры в одной «системе отсчёта»:
  //  sub/surface — полный напор насоса против полного требуемого (уровень + высота + давление + потери);
  //  ejector     — напор на выходе насоса (паспорт для глубины) против нагнетательной стороны (высота над насосом + давление + трасса).
  function availH(pump, Q, s, w, wm) {
    if (installOf(w) === 'ejector' && pumpType(pump) === 'ejector') {
      const L = levelAt(wm, w, Q) + (num(w.pumpElev) ?? 0);
      return ejectorH(pump, Q, L);
    }
    return pumpH(pump, Q);
  }
  function reqH(pump, Q, P, s, w, wm) {
    const sh = systemHead(Q, P, s, w, wm);
    if (installOf(w) === 'ejector' && pumpType(pump) === 'ejector')
      return (sh.dz - (num(w.pumpElev) ?? 0)) + sh.hp + sh.hfHoriz + sh.localHoriz;
    return sh.total;
  }
  function flowAtPressure(pump, P, s, w, wm) {
    const qm = pumpQmax(pump);
    if (!(qm > 0)) return null;
    const g = Q => { const a = availH(pump, Q, s, w, wm); return a == null ? -1e9 : a - reqH(pump, Q, P, s, w, wm); };
    if (g(0) < 0) return null;                       // насос не держит это давление даже без расхода
    if (g(qm) >= 0) return qm;                       // кривая кончилась раньше — упираемся в Qmax паспорта
    let a = 0, b = qm;
    for (let i = 0; i < 60; i++) { const m = (a + b) / 2; if (g(m) >= 0) a = m; else b = m; }
    return (a + b) / 2;
  }
  // давление у потребителя при подаче Q
  function pressureAtFlow(pump, Q, s, w, wm) {
    const h = availH(pump, Q, s, w, wm);
    if (h == null) return null;
    return (h - reqH(pump, Q, 0, s, w, wm)) / M_PER_BAR;
  }

  const TYPE_NAMES = { sub: 'погружной', surface: 'поверхностный / станция', ejector: 'с выносным эжектором' };
  const INSTALL_NAMES = { sub: 'погружной в скважине', surface: 'поверхностный (всасывает)', ejector: 'поверхностный + выносной эжектор' };

  // Полная оценка насоса против системы и скважины
  function evaluatePump(pump, cfg) {
    const { s, w, target, relay } = cfg;
    const wm = wellModel(w);
    const inst = installOf(w), pt = pumpType(pump);
    const r = { id: pump.id, name: pump.name, type: pt, install: inst, warn: [], ok: [] };
    if (pt !== inst) r.warn.push(`Насос «${TYPE_NAMES[pt]}», а установка скважины — «${INSTALL_NAMES[inst]}»: расчёт условный`);
    const H0 = availH(pump, 0, s, w, wm);
    r.H0 = H0;
    const st = num(w.static) ?? 0, hp = num(w.pumpDepth) ?? 0, dz = num(s.dz) ?? 0;
    // закрытая задвижка / реле не отключило
    const req0 = reqH(pump, 0, 0, s, w, wm);
    r.Pshut = H0 != null ? (H0 - req0) / M_PER_BAR : null;
    r.PshutHead = H0 != null ? (H0 - req0 + dz) / M_PER_BAR : null;
    r.PshutBottom = (H0 != null && inst === 'sub') ? (H0 - st + hp) / M_PER_BAR : null;
    const pn = Math.min(...[s.riserPipe, s.horizPipe].map(pipeById).filter(p => p && p.pn).map(p => p.pn));
    r.pipePN = isFinite(pn) ? pn : null;
    const pmax = inst === 'sub' ? r.PshutBottom : r.PshutHead;
    if (r.pipePN && pmax > r.pipePN) r.warn.push(`На закрытую задвижку труба увидит ${f1(pmax)} бар > PN${r.pipePN} — нужна защита (реле/датчик давления, предохранительный клапан)`);
    // рабочие точки по кривой
    r.Qneed = num(target.q);
    r.PatNeed = r.Qneed ? pressureAtFlow(pump, r.Qneed, s, w, wm) : null;
    r.Qon = flowAtPressure(pump, num(relay.on) ?? 0, s, w, wm);
    r.Qoff = flowAtPressure(pump, num(relay.off) ?? 0, s, w, wm);
    r.Qp = flowAtPressure(pump, num(target.p) ?? 0, s, w, wm);
    r.Qfree = flowAtPressure(pump, 0, s, w, wm);    // излив без давления
    // всасывание поверхностного: подача ограничена паспортной глубиной всасывания
    if (inst === 'surface') {
      const top = Math.max(pumpQmax(pump), r.Qneed || 0);
      r.suction0 = suctionAt(0, s, w, wm);
      r.QsuctMax = suctionQmax(pump, s, w, wm, top);
      r.suctLimit = num(pump.suction);
      const sq = Math.max(r.Qneed || 0, r.Qon || 0);
      r.suctAt = suctionAt(sq, s, w, wm);
      if (r.QsuctMax <= 0.01) {
        r.warn.push(`Не возьмёт воду: всасывание уже без расхода ${f1(r.suction0.total)} м` + (r.suctLimit != null ? ` > паспортных ${f1(r.suctLimit)} м` : `, NPSH ${f1(r.suction0.npsha)} м`) + ' — нужен эжектор или погружной');
        for (const k of ['Qon', 'Qoff', 'Qp', 'Qfree', 'PatNeed']) { r[k + 'Raw'] = r[k]; r[k] = null; }
      } else if (r.QsuctMax < sq) {
        r.warn.push(`Всасывание ограничивает подачу до ${f2(r.QsuctMax)} м³/ч (на ${f2(sq)} м³/ч: ${f1(r.suctAt.total)} м${r.suctLimit != null ? ` из ${f1(r.suctLimit)}` : ''}, NPSHa ${f1(r.suctAt.npsha)} м) — дальше срыв/кавитация`);
        for (const k of ['Qon', 'Qoff', 'Qp', 'Qfree']) if (r[k] != null && r[k] > r.QsuctMax) { r[k + 'Raw'] = r[k]; r[k] = r.QsuctMax; }
        if (r.Qneed && r.Qneed > r.QsuctMax) { r.PatNeedRaw = r.PatNeed; r.PatNeed = null; }
      } else {
        r.ok.push(`Всасывание при ${f2(sq)} м³/ч: ${f1(r.suctAt.total)} м` + (r.suctLimit != null ? ` из ${f1(r.suctLimit)}` : '') + `, NPSHa ${f1(r.suctAt.npsha)} м; срыв выше ${f2(r.QsuctMax)} м³/ч`);
        if (r.Qfree != null && r.Qfree > r.QsuctMax) { r.QfreeRaw = r.Qfree; r.Qfree = r.QsuctMax; }
      }
      if (r.suctLimit == null) r.warn.push('В насосе не задана паспортная глубина всасывания — проверка только по NPSH ≥ 0,5 м');
    }
    if (inst === 'ejector' && pt === 'ejector') {
      const L0 = levelAt(wm, w, 0) + (num(w.pumpElev) ?? 0);
      const ds = (pump.curves || []).map(c => c.depth).filter(x => x != null);
      r.ejDepthMax = ds.length ? Math.max(...ds) : null;
      if (r.ejDepthMax != null && L0 > r.ejDepthMax) r.warn.push(`Глубина всасывания ${f1(L0)} м больше паспортных ${f1(r.ejDepthMax)} м`);
      const dEj = num(w.pumpDepth), Lq = levelAt(wm, w, r.Qon || 0);
      if (dEj != null && Lq > dEj - (num(w.minAbove) ?? 0)) r.warn.push(`При ${f2(r.Qon)} м³/ч уровень ${f1(Lq)} м опускается к эжектору (${f1(dEj)} м) — срыв`);
    }
    if (r.Qoff == null || r.Qoff <= 0.05) r.warn.push(`Не достигает ${f1(num(relay.off))} бар отключения реле — насос не выключится`);
    if (r.PatNeed == null || r.PatNeed < (num(target.p) ?? 0)) {
      r.warn.push(r.PatNeed == null || r.PatNeed < 0
        ? `Не даёт ${f1(r.Qneed)} м³/ч в этой системе`
        : `При ${f1(r.Qneed)} м³/ч у потребителя только ${f2(r.PatNeed)} бар (нужно ${f1(num(target.p))})`);
    } else r.ok.push(`${f1(r.Qneed)} м³/ч при ${f2(r.PatNeed)} бар`);
    // против скважины — худший случай: максимальный расход в цикле (при включении реле) и излив
    if (wm.ok && wm.Qsafe != null && r.Qon != null) {
      const qMaxCycle = r.Qon;
      r.Qsafe = wm.Qsafe;
      const src = wellModelText(wm, w);
      if (qMaxCycle > wm.Qsafe && wm.capped && qMaxCycle <= wm.QsafeRaw) {
        r.warn.push(`Скважина не проверена на такой расход: насос в цикле берёт до ${f2(qMaxCycle)} м³/ч (сразу после включения реле на ${f1(num(relay.on))} бар), а замерами подтверждено ${f2(wm.Qsafe)} м³/ч. По модели скважина справилась бы (до ${f2(wm.QsafeRaw)}), но это экстраполяция — сделай замер на расходе около ${f1(qMaxCycle)} м³/ч.` + src);
      } else if (qMaxCycle > wm.Qsafe) {
        const t = drainHours(wm, qMaxCycle);
        r.drainOn = t;
        r.warn.push(`Скважина не успевает: насос в цикле берёт до ${f2(qMaxCycle)} м³/ч (сразу после включения реле на ${f1(num(relay.on))} бар), а без опускания уровня ниже запаса над насосом скважина отдаёт ${f2(wm.Qsafe)} м³/ч — при непрерывной работе уровень дойдёт до запаса за ${fmtHours(t)}.` + src);
      } else r.ok.push(`Скважина справится: насос в цикле берёт максимум ${f2(qMaxCycle)} м³/ч (сразу после включения реле на ${f1(num(relay.on))} бар), а скважина отдаёт без опускания уровня ниже запаса над насосом до ${f2(wm.Qsafe)} м³/ч.` + src);
      if (r.Qfree != null && r.Qfree > wm.Qsafe) r.drainFree = drainHours(wm, r.Qfree);
    }
    // уровень в скважине на включении (что реально будет в колонне)
    r.levelOn = r.Qon != null ? levelAt(wm, w, r.Qon) : null;
    // диаметр в колонне — только для погружных
    const D = num(w.casingID), d = num(pump.diam);
    if (inst === 'sub' && D && d) {
      r.gap = (D - d) / 2;
      if (r.gap < 0) r.warn.push(`Не влезает: Ø${d} > внутр. Ø${D}`);
      else if (r.gap < (num(w.minGap) ?? 8)) r.warn.push(`Зазор ${f1(r.gap)} мм на сторону — риск заклинивания (плюс кабель и хомуты)`);
    }
    if (pump.minQ && r.Qoff != null && r.Qoff < pump.minQ) r.warn.push(`На отключении ${f2(r.Qoff)} м³/ч < мин. ${pump.minQ} для охлаждения`);
    // мощность гидравлическая в рабочей точке потребности
    if (r.Qneed && r.PatNeed != null) {
      const h = availH(pump, r.Qneed, s, w, wm);
      if (h != null) r.Phyd = 1000 * G * (r.Qneed / 3600) * h / 1000;  // кВт
      if (r.Phyd && pump.powerKW) r.eta = r.Phyd / pump.powerKW;
    }
    r.excess = (r.PatNeed != null) ? r.PatNeed - (num(target.p) ?? 0) : null;
    r.fits = r.warn.length === 0;
    r.fitsCore = r.PatNeed != null && r.PatNeed >= (num(target.p) ?? 0) && r.Qoff != null && r.Qoff > 0.05
      && !(r.gap != null && r.gap < 0) && !(inst === 'surface' && r.QsuctMax != null && r.QsuctMax <= 0.01) && pt === inst;
    return r;
  }

  // Кавитация, отмеченная при замере (поверхностный насос/абиссинка): 1 — лёгкая, 2 — сильная
  const CAV_NAMES = { 0: 'нет', 1: 'лёгкая (шум, пузыри)', 2: 'сильная (рвёт струю, срыв)' };
  // по замерам скважины (новые первыми): предел подачи по факту
  function cavNote(meas) {
    const ms = (meas || []).filter(m => m && !m.deleted && m.q > 0 && m.cav != null);
    if (!ms.length) return null;
    const bad = ms.filter(m => m.cav >= 1).sort((a, b) => a.q - b.q)[0];
    const good = ms.filter(m => m.cav === 0).sort((a, b) => b.q - a.q)[0];
    const d = m => new Date(m.ts).toLocaleDateString('ru-RU');
    if (bad && (!good || good.q < bad.q)) return { kind: bad.cav >= 2 ? 'crit' : 'warn', text: `По замеру ${d(bad)}: на ${f2(bad.q)} м³/ч уже ${CAV_NAMES[bad.cav].split(' (')[0]} кавитация — это предел иглы со всасыванием` + (good ? `; без кавитации было ${f2(good.q)} м³/ч (${d(good)})` : '') + '. Больше не взять: прикрывай кран/реле ниже или сокращай подсос (трасса, клапан, насос ниже).' };
    if (good) return { kind: 'ok', text: `По замеру ${d(good)}: ${f2(good.q)} м³/ч без кавитации` + (bad ? `; кавитация была на ${f2(bad.q)} м³/ч (${d(bad)})` : '') + '.' };
    return null;
  }

  // Понятное представление режима насоса: вердикт одной фразой + строки «что / сколько»
  function relayHint(Pshut) {
    if (Pshut == null || Pshut < 1.6) return '';
    const off = Math.floor((Pshut - 0.3) * 10) / 10, on = Math.max(1, Math.round((off - 0.8) * 10) / 10);
    return ` С этим насосом реле можно настроить на ~${f1(on)}/${f1(off)} бар.`;
  }
  function regimeView(e, pump, cfg) {
    const { s, w, target, relay } = cfg;
    const on = num(relay.on) ?? 0, off = num(relay.off) ?? 0, q = num(target.q), tp = num(target.p) ?? 0;
    const inst = installOf(w), pt = pumpType(pump);
    let v;
    if (pt !== inst) v = ['warn', `Это ${TYPE_NAMES[pt]} насос, а в скважине стоит «${INSTALL_NAMES[inst]}» — цифры ниже условные.`];
    else if (inst === 'surface' && e.QsuctMax != null && e.QsuctMax <= 0.01) v = ['crit', 'Воду не возьмёт: уровень глубже, чем насос может всосать. Нужен эжектор или погружной.'];
    else if (e.Pshut == null || e.Pshut <= 0) v = ['crit', 'Не поднимет воду до крана: напора не хватает даже без расхода.'];
    else if (e.Qon == null) v = ['crit', `Слабый для этой системы: даже при закрытом кране максимум ${f1(e.Pshut)} бар, а реле включает насос на ${f1(on)} бар. До ${f1(on)} бар не дотянет — будет молотить без остановки, напор слабый.` + relayHint(e.Pshut)];
    else if (!(e.Qoff > 0.05)) v = ['warn', `Включится на ${f1(on)} бар, но выше ${f1(e.Pshut)} бар не дожмёт — до ${f1(off)} не дойдёт и не выключится.` + relayHint(e.Pshut)];
    else if (q && (e.PatNeed == null || e.PatNeed < tp)) v = ['warn', `Реле отработает, но ${f1(q)} м³/ч при ${f1(tp)} бар не даст: ` + (e.PatNeed == null || e.PatNeed < 0 ? `максимум ${f2(e.Qfree)} м³/ч даже на излив.` : `на ${f1(q)} м³/ч будет только ${f2(e.PatNeed)} бар`) + (e.Qp != null && e.PatNeed != null && e.PatNeed >= 0 ? `, а на ${f1(tp)} бар — ${f2(e.Qp)} м³/ч.` : '')];
    else {
      const base = (q ? `${f1(q)} м³/ч при ${f2(e.PatNeed)} бар` : `на ${f1(on)} бар ${f2(e.Qon)} м³/ч`) + `, реле ${f1(on)}/${f1(off)} отрабатывает`;
      v = e.warn.length ? ['warn', `По давлению и подаче подходит (${base}), но есть замечания ниже.`] : ['ok', `Подходит: ${base}.`];
    }
    const lpm = x => `${f2(x)} м³/ч · ${f0(x * 1000 / 60)} л/мин`;
    const rows = [];
    rows.push([`Подача при ${f1(on)} бар (реле включает)`, e.Qon != null ? lpm(e.Qon) : `0 — столько не даёт`]);
    rows.push([`Подача при ${f1(off)} бар (реле выключает)`, e.Qoff != null && e.Qoff > 0.05 ? lpm(e.Qoff) : `0 — до ${f1(off)} бар не дожимает`]);
    if (q) rows.push([`Давление при расходе ${f1(q)} м³/ч`, e.PatNeed != null && e.PatNeed >= 0 ? `${f2(e.PatNeed)} бар` : `${f1(q)} м³/ч не даст`]);
    rows.push(['Излив без давления (кран нараспашку)', e.Qfree != null ? lpm(e.Qfree) + (e.QfreeRaw ? ` (по кривой ${f2(e.QfreeRaw)}, режет всасывание)` : '') : '—']);
    if (e.levelOn != null && !isAbyss(w)) rows.push(['Уровень воды в скважине при работе', `${f1(e.levelOn)} м от земли` + (wellModel(w).ok ? '' : ' (динамика из настроек — без замера с расходом)')]);
    if (e.QsuctMax != null) rows.push(['Всасывание: срыв выше', e.QsuctMax > 0.01 ? `${f2(e.QsuctMax)} м³/ч` : 'не возьмёт']);
    if (e.suctAt) rows.push(['Высота всасывания в работе', `${f1(e.suctAt.total)} м` + (e.suctLimit != null ? ` из ${f1(e.suctLimit)}` : '') + `, NPSHa ${f1(e.suctAt.npsha)} м`]);
    if (e.drainOn != null) rows.push(['Скважину осушит до запаса за', fmtHours(e.drainOn)]);
    if (e.Pshut != null) rows.push(['Максимум давления в кране (всё закрыто)', `${f1(e.Pshut)} бар`]);
    if (e.PshutBottom != null) rows.push(['Давление в трубе у насоса (всё закрыто)', `${f1(e.PshutBottom)} бар` + (e.pipePN ? (e.PshutBottom > e.pipePN ? ` — больше PN${e.pipePN} трубы!` : ` — труба PN${e.pipePN} выдержит`) : '')]);
    if (pump.powerKW) rows.push(['Потребляемая мощность P1', `${f2(pump.powerKW)} кВт` + (e.eta ? ` · КПД агрегата ≈ ${f0(e.eta * 100)}%` : '')]);
    return { verdict: { kind: v[0], text: v[1] }, rows };
  }

  // Подбор: расчётная точка и ранжирование каталога
  function designPoint(cfg) {
    const wm = wellModel(cfg.w);
    let Q = num(cfg.target.q) ?? 0, limited = false;
    if (wm.ok && wm.Qsafe != null && wm.Qsafe < Q) { Q = wm.Qsafe; limited = true; }
    const sh = systemHead(Q, num(cfg.target.p) ?? 0, cfg.s, cfg.w, wm);
    return { Q, limited, Qtarget: num(cfg.target.q), wm, sh };
  }
  function rankPumps(pumps, cfg) {
    const grp = e => e.excess == null ? 2 : e.excess < 0 ? 1 : 0;
    return pumps.map(p => ({ p, e: evaluatePump(p, cfg) }))
      .sort((a, b) => (b.e.fits - a.e.fits) || (b.e.fitsCore - a.e.fitsCore) || (grp(a.e) - grp(b.e)) ||
        (grp(a.e) === 0 ? a.e.excess - b.e.excess : grp(a.e) === 1 ? b.e.excess - a.e.excess : 0) ||
        a.p.name.localeCompare(b.p.name));
  }
  // требуемый напор при разных трубах (стояк и трасса одной трубой) — сравнение 32/40/50
  function pipeAlternatives(cfg) {
    const dp = designPoint(cfg);
    return PIPES.filter(p => p.pn).map(p => {
      const s = { ...cfg.s, riserPipe: p.id, horizPipe: p.id };
      const sh = systemHead(dp.Q, num(cfg.target.p) ?? 0, s, cfg.w, dp.wm);
      return { pipe: p, sh, hf: sh.hfRiser + sh.hfHoriz + sh.local, current: p.id === cfg.s.riserPipe && p.id === cfg.s.horizPipe };
    });
  }

  // ---------- каталог: только паспортные таблицы H(Q) (catalog.json; в браузерной сборке подставляется build.py) ----------
  const CATALOG_RAW = (typeof QD_CATALOG !== 'undefined') ? QD_CATALOG
    : (typeof require !== 'undefined' ? require('./catalog.json') : []);
  const CATALOG = CATALOG_RAW.map(p => ({ ...p, type: p.type || 'sub', catalog: true }));

  // ---------- настройки по умолчанию ----------
  // v2: база скважин. Профиль скважины = параметры скважины + своя система (трубы, давление, реле, насосы на графике).
  function newId(prefix) { return (prefix || '') + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }
  function wellDefaults() {
    return {
      well: { kind: 'borehole', install: 'sub', static: 35, dyn: 50, dynQ: null, pumpDepth: 75, minAbove: 5, casingID: 110, riserOD: 32, depth: 190, minGap: 8, pumpElev: 0, alt: 150,
              aquifer: null, aqTop: null, horizon: null, elev: null, elevDem: null, elevDemLat: null, elevDemLon: null, layers: [],
              cpSet: null, fitB: null, fitC: null, fitN: null, fitQmax: null, fitKind: null },
      sys: { dz: 0, riserPipe: 'pe32-16', riserLen: null, horizPipe: 'pe32-16', horizLen: 20, localPct: 10, tempC: 8, riserID: null, horizID: null, suctLen: 0, suctPipe: null, suctID: null },
      target: { q: 5, p: 3.5 },
      relay: { on: 3.5, off: 4.0 },
      chart: ['cat-eco-4-142', null, null],
      refId: null,
      pumpId: null,            // насос, который стоит/планируется в этой скважине
      lat: null, lon: null     // координаты точки на участке
    };
  }
  function newWell(name, base) {
    const d = wellDefaults();
    return merge(d, { ...(base || {}), id: newId('w-'), name: name || 'Скважина', note: (base && base.note) || '', updatedAt: Date.now(), fmt: 3 });
  }
  function defaults() {
    const w = newWell('Скважина 1');
    return {
      v: 2,
      presets: [5, 10, 12, 20, 50, 100, 200],
      profile: [],            // скважины для профиля-разреза на «Участке», по порядку
      volume: 10,
      reaction: 0.3,
      pumps: [],                      // свои насосы {id,name,diam,powerKW,points:[[q,h]],note,updatedAt}
      wells: [w],
      activeWell: w.id,               // скважина на вкладках «Скважина» и «Насос»
      measWell: null,                 // привязка нового замера: null = без привязки
      volKeys: true
    };
  }
  // v1 (одна скважина в корне настроек) → v2 (база скважин). Старые замеры остаются без привязки.
  function migrate(raw) {
    if (!raw || typeof raw !== 'object') return defaults();
    if (Array.isArray(raw.wells) && raw.wells.length) {
      const d = defaults();
      const o = merge({ ...d, wells: undefined }, raw);
      o.wells = raw.wells.map(w => upgradeWell(merge(wellDefaults(), w), w));
      if (!o.wells.some(w => w.id === o.activeWell && !w.deleted)) o.activeWell = (o.wells.find(w => !w.deleted) || o.wells[0]).id;
      if (o.measWell && !o.wells.some(w => w.id === o.measWell && !w.deleted)) o.measWell = null;
      o.v = 2;
      return o;
    }
    const w = newWell('Скважина 1', { well: raw.well, sys: raw.sys, target: raw.target, relay: raw.relay, chart: raw.chart, refId: raw.refId });
    const o = merge(defaults(), { presets: raw.presets, volume: raw.volume, reaction: raw.reaction, pumps: raw.pumps, volKeys: raw.volKeys });
    o.wells = [w]; o.activeWell = w.id; o.measWell = null; o.v = 2;
    return o;
  }
  /*
   * Перевод скважины в формат 1.5.0 (однократно, fmt < 3). Для абиссинки:
   * • глубина иглы = прежняя «глубина забора», если своя не задана;
   * • прежняя длина всасывающей сверх глубины забора — это была горизонталь до насоса → «всасывающая трасса»;
   * • труба иглы: если трубу не меняли (ВГП 1¼″ по умолчанию), а «внутр. Ø колонны» правили — берём трубу по этому Ø;
   * • прежние well/sys целиком сохраняются в legacy; динамика, глубина насоса, Ø колонны остаются в данных (просто скрыты).
   */
  function upgradeWell(m, raw) {
    if ((raw && raw.fmt) >= 3) return m;
    m.fmt = 3;
    if (!isAbyss(m.well)) return m;
    const ow = raw.well || {}, os = raw.sys || {};
    m.legacy = { well: JSON.parse(JSON.stringify(ow)), sys: JSON.parse(JSON.stringify(os)), at: Date.now() };
    const pd = num(ow.pumpDepth), rl = num(os.riserLen), cid = num(ow.casingID);
    if (num(ow.depth) == null && pd != null) m.well.depth = pd;
    if (rl != null && pd != null && rl > pd + 0.05 && !num(os.suctLen)) m.sys.suctLen = +(rl - pd).toFixed(2);
    if ((os.riserPipe || 'pe32-16') === 'st32' && cid != null && Math.abs(cid - 35.9) > 0.05) {
      const match = PIPES.find(p => Math.abs(p.idmm - cid) <= 0.3);
      if (match) m.sys.riserPipe = match.id; else { m.sys.riserPipe = 'custom'; m.sys.riserID = cid; }
    }
    m.updatedAt = Date.now();
    return m;
  }
  // пересчёт сводки замера после правки вёдер
  function recomputeMeas(m, reaction) {
    const st = seriesStats(m.runs, reaction);
    if (!st) return { ...m, q: 0, cv: null, err: null };
    return { ...m, q: +st.avg.m3h.toFixed(4), cv: +st.cv.toFixed(4), err: +st.errSeries.toFixed(4) };
  }

  function merge(base, over) {
    if (over == null) return base;
    if (typeof over !== 'object' || Array.isArray(over)) return over;
    const o = Array.isArray(base) ? [] : { ...base };
    for (const k of Object.keys(over)) if (over[k] !== undefined) o[k] = (base && typeof base[k] === 'object' && base[k] && !Array.isArray(base[k])) ? merge(base[k], over[k]) : over[k];
    return o;
  }


  // ---------- внешний JSON насосов (свой формат или формат «большого ТЗ» Perplexity) ----------
  // Аппроксимация по паспортным числам: H = Hmax·(1 − (Q/Qmax)^n); калибровка по табличным кривым каталога:
  // погружные n=2 (ср. ошибка ≈5% Hmax), поверхностные n=1,4 (≈8%); с номинальной точкой n подбирается по ней.
  function approxCurve(hmax, qmax, type, qnom, hnom, hAtQmax) {
    if (!(hmax > 0) || !(qmax > 0)) return null;
    let n = type === 'sub' ? 2 : 1.4, how = `≈ по Hmax ${hmax} м и Qmax ${qmax} м³/ч (n=${n})`;
    if (qnom > 0 && qnom < qmax && hnom > 0 && hnom < hmax && !hAtQmax) { n = Math.log(1 - hnom / hmax) / Math.log(qnom / qmax); how = `≈ по Hmax ${hmax}, номиналу ${qnom}/${hnom}, Qmax ${qmax} (n=${n.toFixed(2)})`; }
    const top = hAtQmax || 0;
    return { points: Array.from({ length: 11 }, (_, i) => [+(qmax * i / 10).toFixed(3), +(top + (hmax - top) * (1 - Math.pow(i / 10, n))).toFixed(1)]), how };
  }
  function fromExternal(x) {
    if (!x || !x.name) return { err: 'нет названия' };
    const type = ['sub', 'surface', 'ejector'].includes(x.type) ? x.type : 'sub';
    const p = { name: String(x.name), type, suction: num(x.suction ?? x.suction_m), diam: num(x.diam ?? x.diam_mm), powerKW: num(x.powerKW ?? x.power_kw),
      currentA: num(x.currentA ?? x.current_a), minQ: num(x.minQ ?? x.min_q_m3h ?? x.minFlowM3h), note: [x.note, x.src].filter(Boolean).join(' · ') };
    if (Array.isArray(x.curves) && x.curves.length) p.curves = x.curves.map(c => ({ depth: num(c.depth), points: normPoints(c.points) }));
    p.points = normPoints((x.points && x.points.length) ? x.points : (p.curves ? p.curves[0].points : []));
    if (p.points.length < 2 && !p.curves) {
      const a = approxCurve(num(x.hmax), num(x.qmax), type, num(x.qnom), num(x.hnom), num(x.h_at_qmax));
      if (!a) return { err: 'нет кривой и нет пары Hmax+Qmax' };
      p.points = a.points; p.approx = true; p.note = a.how + (p.note ? '. ' + p.note : '');
    } else if (x.quality === 'graph') p.note = 'снято с графика. ' + p.note;
    const mono = pts => pts.every((q, i) => i === 0 || q[1] <= pts[i - 1][1]);
    if (!mono(p.points) || (p.curves && !p.curves.every(c => mono(c.points)))) return { err: 'напор растёт с подачей — таблица прочитана неверно' };
    return { pump: p };
  }

  // ---------- поиск по названию ----------
  // «4 142», «4-142», «4142», «водомет 55», «jsw 2» — каждое слово запроса должно найтись в названии
  function normName(t) { return String(t || '').toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9.]+/g, ' ').trim(); }
  function matchName(name, q) {
    const n = normName(name), nc = n.replace(/ /g, '');
    return normName(q).split(' ').filter(Boolean).every(w => n.includes(w) || nc.includes(w));
  }

  // ---------- координаты ----------
  // «55.751244, 37.618423», «55,751244 37,618423», «N55.75 E37.61» → {lat, lon}
  function parseLatLon(t) {
    const n = String(t || '').replace(/(\d),(\d)/g, '$1.$2').match(/-?\d+(\.\d+)?/g);
    if (!n || n.length < 2) return null;
    const lat = +n[0], lon = +n[1];
    if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) return null;
    return { lat, lon };
  }
  function distM(a, b) {
    const R = 6371008.8, r = Math.PI / 180;
    const dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }
  // локальная плоская проекция вокруг центра (м): x — на восток, y — на север
  function toLocal(points) {
    const ps = points.filter(p => p.lat != null && p.lon != null);
    if (!ps.length) return { pts: [], lat0: null, lon0: null };
    const lat0 = ps.reduce((s, p) => s + p.lat, 0) / ps.length, lon0 = ps.reduce((s, p) => s + p.lon, 0) / ps.length;
    const kx = 111320 * Math.cos(lat0 * Math.PI / 180), ky = 110574;
    return { lat0, lon0, pts: ps.map(p => ({ ...p, x: (p.lon - lon0) * kx, y: (p.lat - lat0) * ky })) };
  }

  // ---------- форматирование ----------
  function num(x) { if (x === '' || x == null) return null; const n = typeof x === 'number' ? x : parseFloat(String(x).replace(',', '.')); return isFinite(n) ? n : null; }
  function fx(x, d) { return x == null || !isFinite(x) ? '—' : (+x).toLocaleString('ru-RU', { minimumFractionDigits: d, maximumFractionDigits: d }); }
  const f1 = x => fx(x, 1), f2 = x => fx(x, 2), f0 = x => fx(x, 0);
  function fmtHours(h) {
    if (h == null) return '—';
    if (h === Infinity) return 'никогда';
    const m = h * 60;
    if (m < 1) return `${f0(m * 60)} с`;
    if (m < 90) return `${f0(m)} мин`;
    return `${f1(h)} ч`;
  }
  function fmtDur(sec) {
    if (!isFinite(sec)) return '—';
    const m = Math.floor(sec / 60), s = sec - m * 60;
    if (m >= 60) { const h = Math.floor(m / 60); return `${h}:${String(m % 60).padStart(2, '0')}:${String(Math.floor(s)).padStart(2, '0')}`; }
    return `${String(m).padStart(2, '0')}:${s.toFixed(1).padStart(4, '0')}`;
  }

  const API = { VERSION, M_PER_BAR, units, runRate, seriesStats, PIPES, pipeById, pipeOf, nu, friction,
    wellModel, levelAt, drainHours, systemHead, normPoints, parsePoints, pumpH, pumpQmax,
    flowAtPressure, pressureAtFlow, evaluatePump, regimeView, wellModelText, dynFromPressure, wellPoints, fitWell, inflowAt, isAbyss, cavNote, CAV_NAMES, groundZ, effAlt, ROCKS, ROCK_NAME, ROCK_COLOR, AQ_NAMES, aquiferOf, zInfo, wellPairs, fmtDist, fmtZ, pairRows, availH, reqH, ejectorH, suctionAt, suctionQmax, hAtm, hVap, parseCurves, curvesToText, pumpType, installOf, TYPE_NAMES, INSTALL_NAMES, parseLatLon, distM, toLocal, normName, matchName, approxCurve, fromExternal, designPoint, rankPumps, pipeAlternatives, CATALOG, defaults, merge, newId, wellDefaults, newWell, migrate, recomputeMeas,
    num, fx, f0, f1, f2, fmtHours, fmtDur };
  if (typeof module !== 'undefined') module.exports = API; else root.QD = API;
})(this);
