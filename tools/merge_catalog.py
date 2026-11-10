"""Сводит базовый каталог (ECO) + результаты исследований в web/catalog.json."""
import json, os, re, sys
TR = dict(zip('абвгдеёжзийклмнопрстуфхцчшщъыьэюя', ['a','b','v','g','d','e','e','zh','z','i','y','k','l','m','n','o','p','r','s','t','u','f','h','c','ch','sh','sch','','y','','e','yu','ya']))
def slug(name):
    s = ''.join(TR.get(c, c) for c in name.lower())
    return 'cat-' + re.sub(r'[^a-z0-9]+', '-', s).strip('-')
base = [p for p in json.load(open(sys.argv[1])) if p['name'].startswith('UNIPUMP ECO ') and p.get('type','sub')=='sub' and re.match(r'UNIPUMP ECO \d-\d+$', p['name'])]
out = {p['id']: p for p in base}
SKIP = {'Джилекс Джамбо 50/28': 'кривая приблизительная (старое руководство, Hmax не сходится)'}
FIX_DROP_LAST = {'DAB JET 112 M': 'последняя точка 3,6→20 м исключена — выбивается из кривой во всех источниках (вероятная опечатка)'}
skipped = []
# Аппроксимация по паспортным числам: H = Hmax·(1 − (Q/Qmax)^n). Калибровка по 86 табличным кривым каталога:
# погружные n=2 (ср. ошибка 5% Hmax), поверхностные n=1,4 (ср. 8%). Если есть номинал — n подбирается по нему.
import math
def approx(x, typ):
    hmax, qmax = x.get('hmax'), x.get('qmax')
    qn, hn, hq = x.get('qnom'), x.get('hnom'), x.get('h_at_qmax')
    if not hmax or not qmax: return None, None
    n = 2.0 if typ == 'sub' else 1.4
    how = f"≈ по Hmax {hmax} м и Qmax {qmax} м³/ч, форма n={n}"
    if qn and hn and 0 < qn < qmax and 0 < hn < hmax:
        n = math.log(1 - hn / hmax) / math.log(qn / qmax) if hq in (None, 0) else n
        how = f"≈ по Hmax {hmax}, номиналу {qn}/{hn}, Qmax {qmax}; n={n:.2f}"
    top = hq or 0
    pts = [[round(qmax * i / 10, 3), round(top + (hmax - top) * (1 - (i / 10) ** n), 1)] for i in range(11)]
    return pts, how
force = False   # файлы после --force заменяют уже имеющиеся модели (точнее оцифрованные графики)
for f in sys.argv[2:]:
    if f == '--force': force = True; continue
    if not os.path.exists(f): continue
    for x in json.load(open(f)):
        n = x['name']
        # «ДЖИЛЕКС ВОДОМЕТ 3ДК» → «Джилекс Водомет 3ДК»: слова из одних заглавных кириллических букв длиннее 3
        n = ' '.join(w.capitalize() if re.fullmatch(r'[А-ЯЁ]{4,}', w) else w for w in n.split(' '))
        x = dict(x); x['name'] = n
        # формат «большого ТЗ»: brand/quality/hmax/qmax/diam_mm/power_kw…
        if 'quality' in x or 'diam_mm' in x:
            x = dict(x)
            for a, b in (('diam_mm', 'diam'), ('power_kw', 'powerKW'), ('current_a', 'currentA'), ('suction_m', 'suction'), ('min_q_m3h', 'minQ')):
                if x.get(a) is not None and x.get(b) is None: x[b] = x[a]
            if x.get('power_type') and x.get('power_kw'): x['powerType'] = x['power_type']
            if not (x.get('points') or x.get('curves')):
                pts, how = approx(x, x.get('type') or 'sub')
                if not pts: skipped.append((n, 'нет кривой и нет пары Hmax+Qmax')); continue
                x['points'] = pts; x['note'] = (how + '. ' + (x.get('note') or '')).strip(); x['approx'] = True
            elif x.get('quality') == 'graph': x['note'] = 'снято с графика. ' + (x.get('note') or '')
        nn = re.sub(r'[^a-zа-я0-9]+', ' ', n.lower().replace('ё', 'е')).strip()
        dup = next((q for q in out.values() if re.sub(r'[^a-zа-я0-9]+', ' ', q['name'].lower().replace('ё', 'е')).strip() == nn), None)
        if dup is not None and force and not x.get('approx') and (x.get('points') or x.get('curves')):
            skipped.append((n, 'заменён более точной кривой')); out.pop(dup['id'], None); dup = None
        if dup is not None and not dup.get('approx'):
            same = [list(map(float, a)) for a in (x.get('points') or [])] == [list(map(float, a)) for a in dup.get('points', [])]
            skipped.append((n, 'уже есть в каталоге' + ('' if not x.get('points') else (', таблица совпадает' if same else ', ТАБЛИЦА ОТЛИЧАЕТСЯ'))))
            continue
        if n in SKIP: skipped.append((n, SKIP[n])); continue
        pts = [[float(a), float(b)] for a, b in (x.get('points') or [])]
        note = []
        if n in FIX_DROP_LAST: pts = pts[:-1]; note.append(FIX_DROP_LAST[n])
        if x.get('powerP2kW') and not x.get('powerKW'): note.append(f"P2 {x['powerP2kW']} кВт (P1 в паспорте нет)")
        if x.get('notes'): note.append(x['notes'])
        if x.get('note'): note.append(x['note'])
        if x.get('powerType') and x.get('powerKW'): note.append(f"мощность: {x['powerType']}")
        if x.get('p2KW'): note.append(f"P2 {x['p2KW']} кВт")
        p = {'id': slug(n), 'name': n, 'type': x.get('type') or 'sub', 'points': sorted(pts),
             'suction': x.get('suction'), 'diam': x.get('diam'), 'powerKW': x.get('powerKW'), 'currentA': x.get('currentA'),
             'minQ': x.get('minQ') if x.get('minQ') is not None else x.get('minFlowM3h'), 'approx': x.get('approx'), 'src': x.get('src'), 'note': ' · '.join(note) or None}
        cv = x.get('curves')
        if isinstance(cv, dict): cv = [{'depth': float(k), 'points': v} for k, v in cv.items()]
        if cv: p['curves'] = sorted([{'depth': float(c['depth']), 'points': sorted([[float(a), float(b)] for a, b in c['points']])} for c in cv if len(c['points']) >= 2], key=lambda c: c['depth'])
        if len(p['points']) < 2 and not p.get('curves'): skipped.append((n, 'меньше 2 точек')); continue
        mono = lambda pts: all(pts[i][1] >= pts[i + 1][1] for i in range(len(pts) - 1))
        if not all(mono(c['points']) for c in (p.get('curves') or [{'points': p['points']}])): skipped.append((n, 'напор растёт с подачей — таблица прочитана неверно')); continue
        p = {k: v for k, v in p.items() if v is not None}
        out[p['id']] = p
cat = sorted(out.values(), key=lambda p: ({'sub': 0, 'surface': 1, 'ejector': 2}[p['type']], p['name']))
json.dump(cat, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
from collections import Counter
print('каталог:', len(cat), Counter(p['type'] for p in cat)); print('пропущено:', skipped)
