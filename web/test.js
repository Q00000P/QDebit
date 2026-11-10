const Q = require('./core.js');
const d0 = Q.defaults(); const d = { ...d0, ...d0.wells[0] };
// замер: 10 л за 7.2 с => 1.389 л/с => 5.0 м3/ч
const st = Q.seriesStats([{v:10,t:7.2},{v:10,t:7.5},{v:12,t:8.4}]);
console.log('series', st.avg.m3h.toFixed(3), st.min.m3h.toFixed(3), st.max.m3h.toFixed(3), (st.cv*100).toFixed(2)+'%', (st.errSeries*100).toFixed(2)+'%');
console.log('10л/7.2с m3h', Q.units(10/7.2).m3h.toFixed(4), 'lpm', Q.units(10/7.2).lpm.toFixed(2));
// трение: ПНД32 PN16 (26мм) 5 м3/ч 75 м
const f = Q.friction(5, 26, 75, 0.007, 8);
console.log('pipe', Q.pipeById('pe32-16').idmm, 'v', f.v.toFixed(3), 'Re', f.re.toFixed(0), 'f', f.f.toFixed(4), 'hf', f.hf.toFixed(2));
// сверка с Colebrook итерацией
let ff=0.02; const D=0.026, eps=0.007e-3, Re=f.re; for(let i=0;i<50;i++){ff=Math.pow(-2*Math.log10(eps/(3.7*D)+2.51/(Re*Math.sqrt(ff))),-2);} console.log('colebrook f',ff.toFixed(4));
const f40 = Q.friction(5, 32.6, 75, 0.007, 8); console.log('pe40-16 hf', f40.hf.toFixed(2), 'v', f40.v.toFixed(2));
// скважина: статика 35, динамика 50 при 3 м3/ч
const w = {...d.well, dynQ: 3};
const wm = Q.wellModel(w); console.log('well q', wm.q.toFixed(3), 'Qsafe', wm.Qsafe.toFixed(2), 'reserve L', wm.reserveL.toFixed(0), 'drain@5', Q.fmtHours(Q.drainHours(wm,5)));
const cfg = {s:d.sys, w, target:d.target, relay:d.relay};
const dp = Q.designPoint(cfg); console.log('design', dp.Q.toFixed(2), dp.limited, 'Hreq', dp.sh.total.toFixed(1), JSON.stringify(Object.fromEntries(Object.entries(dp.sh).map(([k,v])=>[k,+(+v).toFixed(2)]))));
const p142 = Q.CATALOG.find(p=>p.name.includes('4-142'));
const e = Q.evaluatePump(p142, cfg); console.log(JSON.stringify(e,(k,v)=>typeof v==='number'?+v.toFixed(2):v,1));
// без динамики (dynQ null)
const cfg2 = {s:d.sys, w:d.well, target:d.target, relay:d.relay};
const r = Q.rankPumps(Q.CATALOG, cfg2).slice(0,6).map(x=>[x.p.name,x.e.fits,x.e.fitsCore,x.e.PatNeed&&x.e.PatNeed.toFixed(2),x.e.Qon&&x.e.Qon.toFixed(2),x.e.Qoff&&x.e.Qoff.toFixed(2)]);
console.log(r);
console.log(Q.parsePoints('0:183, 1:183; 2 173\n3,5 150'));
console.log(JSON.stringify(p142).includes('_pts'));
