import json, os
d = os.path.dirname(os.path.abspath(__file__))
s = open(os.path.join(d, 'app.html')).read()
c = open(os.path.join(d, 'core.js')).read()
cat = json.load(open(os.path.join(d, 'catalog.json')))
inj = 'const QD_CATALOG = ' + json.dumps(cat, ensure_ascii=False, separators=(',', ':')) + ';\n'
open(os.path.join(d, 'QDebit.html'), 'w').write(s.replace('/*CORE*/', inj + c))
# Android: тот же каталог в Kotlin-файл (raw string, без $)
kt = os.path.join(d, '..', 'android', 'app', 'src', 'main', 'java', 'org', 'qdebit', 'android', 'CatalogData.kt')
if os.path.isdir(os.path.dirname(kt)):
    js = json.dumps([{**p, 'catalog': True, 'type': p.get('type') or 'sub'} for p in cat], ensure_ascii=False, separators=(',', ':'))
    assert '$' not in js and '"""' not in js
    # куски < 64 КБ байт-кода на литерал (лимит constant pool JVM)
    parts = [js[i:i + 12000] for i in range(0, len(js), 12000)]
    body = ',\n'.join('    """' + p + '"""' for p in parts)
    open(kt, 'w').write('package org.qdebit.android\n\n// СГЕНЕРИРОВАНО web/build.py из web/catalog.json — не править руками\ninternal val CATALOG_JSON: String = listOf(\n' + body + ',\n).joinToString("")\n')
    print('CatalogData.kt:', len(cat), 'pumps')
