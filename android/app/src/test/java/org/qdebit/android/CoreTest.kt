package org.qdebit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Эталоны — из веб-версии (core.js), должны совпадать до сотых. */
class CoreTest {
    private val S = WellProfile(id = "t").let { it.copy(well = it.well.copy(dynQ = 3.0)) }

    @Test fun series() {
        val st = Core.seriesStats(listOf(Run(10.0, 7.2), Run(10.0, 7.5), Run(12.0, 8.4)))!!
        assertEquals(4.987, st.avg.m3h, 1e-3)
        assertEquals(0.0346, st.cv, 1e-4)
        assertEquals(5.0, Core.Units(10 / 7.2).m3h, 1e-9)
    }

    @Test fun friction() {
        val f = Core.friction(5.0, 26.0, 75.0, 0.007, 8.0)
        assertEquals(22.06, f.hf, 0.01)
        assertEquals(2.616, f.v, 0.001)
    }

    @Test fun well() {
        val wm = Core.wellModel(S.well)
        assertEquals(0.2, wm.q, 1e-9)
        assertEquals(7.0, wm.QsafeRaw!!, 1e-9)
        assertEquals(4.5, wm.Qsafe!!, 1e-9)            // одна точка: не дальше 1,5× расхода замера
        assertEquals(304.5, wm.reserveL!!, 0.1)
        assertTrue(Core.drainHours(wm, 5.0)!!.isInfinite())
    }

    @Test fun pump() {
        assertEquals(118.60, Core.designPoint(S).sh.total, 0.01)   // расход ограничен проверенным дебитом 4,5
        val e = Core.evaluatePump(Catalog.ALL.first { it.id == "cat-eco-4-142" }, S)
        assertEquals(2.87, e.PatNeed!!, 0.01)
        assertEquals(4.82, e.Qon!!, 0.01)
        assertEquals(4.68, e.Qoff!!, 0.01)
        assertEquals(21.87, e.PshutBottom!!, 0.01)
        assertEquals(4, e.warn.size)
        // базовый ряд ECO: лучший — 4-142; по всему каталогу первым должен идти насос, проходящий по основным условиям
        assertEquals("UNIPUMP ECO 4-142", Core.rankPumps(Catalog.ALL.filter { it.id.startsWith("cat-eco-") }, S).first().pump.name)
        assertTrue(Core.rankPumps(Catalog.ALL, S).first().fitsCore)
    }

    @Test fun points() {
        assertEquals(listOf(listOf(0.0, 183.0), listOf(1.0, 183.0), listOf(2.0, 173.0), listOf(3.5, 150.0)), Core.parsePoints("0:183, 1:183; 2 173\n3,5 150"))
    }

    @Test fun migrateV1() {
        val m = migrate(Settings(wells = emptyList(), well = Well(static = 30.0), sys = Sys(horizLen = 40.0), refId = "r1"))
        assertEquals(1, m.wells.size); assertEquals(30.0, m.wells[0].well.static!!, 0.0); assertEquals(40.0, m.wells[0].sys.horizLen!!, 0.0)
        assertEquals("r1", m.wells[0].refId); assertEquals(m.wells[0].id, m.activeWell); assertEquals(null, m.well)
    }

    @Test fun webBackupImport() {
        // формат экспорта веб-версии v1.1
        val json = """{"app":"QDebit","v":"1.1.0","settings":{"v":2,"presets":[10,12],"wells":[{"id":"w-1","name":"Баня","note":"","well":{"static":12,"dyn":20,"dynQ":2},"sys":{"horizLen":30},"target":{"q":4,"p":3},"relay":{"on":3,"off":4},"chart":["cat-eco-4-142",null,null],"refId":null,"updatedAt":1}],"activeWell":"w-1","measWell":null,"pumps":[{"id":"u1","name":"Мой","points":[[0,100],[5,10]]}]},"measurements":[{"id":"m1","ts":1730000000000,"runs":[{"v":10,"t":7.2}],"q":5,"wellId":"w-1","updatedAt":1730000000000}]}"""
        val b = QJson.decodeFromString(Backup.serializer(), json)
        val s = migrate(b.settings!!)
        assertEquals("Баня", s.wells[0].name); assertEquals(12.0, s.wells[0].well.static!!, 0.0); assertEquals(1, s.pumps.size)
        assertEquals("w-1", b.measurements[0].wellId)
    }

    @Test fun influxParsers() {
        val ql = """{"results":[{"statement_id":0,"series":[{"name":"qdebit_doc","tags":{"id":"w-1","kind":"well"},"columns":["time","json"],"values":[[1,"{\"id\":\"w-1\"}"]]}]}]}"""
        assertEquals(listOf(InfluxSync.Doc("well", "w-1", "{\"id\":\"w-1\"}")), InfluxSync.parseInfluxQl(ql))
        assertEquals(0, InfluxSync.parseInfluxQl("""{"results":[{"statement_id":0}]}""").size)
        val csv = "#group,false,false,true,true,false\r\n,result,table,id,kind,_value\r\n,_result,0,w-1,well,\"{\"\"a\"\":\"\"x,y\"\"}\"\r\n\r\n,result,table,id,kind,_value\r\n,_result,1,m1,meas,{}\r\n"
        val d = InfluxSync.parseFluxCsv(csv)
        assertEquals(2, d.size); assertEquals("{\"a\":\"x,y\"}", d[0].json); assertEquals("meas", d[1].kind)
    }

    @Test fun surfaceSuction() {
        val w = S.copy(well = S.well.copy(install = "surface", static = 4.0, dyn = 6.0, dynQ = 1.5, pumpDepth = 10.0, minAbove = 1.0, pumpElev = 0.3),
            sys = S.sys.copy(riserPipe = "st32", horizPipe = "pe32-10", horizLen = 15.0), target = Target(2.5, 2.5), relay = Relay(1.5, 2.8))
        val j = Pump("t", "t", type = "surface", suction = 8.0, points = listOf(listOf(0.0, 45.0), listOf(0.6, 40.0), listOf(1.2, 35.0), listOf(1.8, 29.0), listOf(2.4, 22.0), listOf(3.0, 14.0), listOf(3.6, 5.0)))
        val e = Core.evaluatePump(j, w)
        assertEquals(2.28, e.Qon!!, 0.01); assertEquals(1.19, e.PatNeed!!, 0.01); assertEquals(2.58, e.QsuctMax!!, 0.01)
        val e2 = Core.evaluatePump(j, w.copy(well = w.well.copy(static = 9.0, dyn = 11.0)))
        assertEquals(null, e2.Qon); assertTrue(e2.warn.first().startsWith("Не возьмёт воду"))
    }

    @Test fun ejectorCurves() {
        val ej = Pump("e", "ej", type = "ejector", curves = Core.parseCurves("@10\n0 50\n1 40\n2 25\n@20\n0 40\n1 30\n2 12\n@30\n0 30\n1 18\n1.5 5"))
        val we = S.copy(well = S.well.copy(install = "ejector", static = 15.0, dyn = 20.0, dynQ = 1.0, pumpDepth = 28.0, minAbove = 2.0, pumpElev = 0.0),
            sys = S.sys.copy(horizLen = 10.0), target = Target(1.0, 2.0), relay = Relay(1.5, 2.5))
        val e = Core.evaluatePump(ej, we)
        assertEquals(45.0, e.H0!!, 1e-9); assertEquals(2.92, e.PatNeed!!, 0.01); assertEquals(1.17, e.Qoff!!, 0.01)
        assertEquals(35.0, Core.ejectorH(ej, 1.0, 15.0)!!, 1e-9)
    }

    @Test fun geo() {
        val a = Core.parseLatLon("55,751244 37,618423")!!
        assertEquals(36.6, Core.distM(a.lat, a.lon, 55.7513, 37.619), 0.1)
    }

    @Test fun search() {
        assertTrue(Core.matchName("UNIPUMP ECO 4-142", "4 142")); assertTrue(Core.matchName("UNIPUMP ECO 4-142", "4142"))
        assertTrue(Core.matchName("Джилекс Водомет 55/50", "водомет 55")); assertTrue(!Core.matchName("Джилекс Водомет 40/50", "водомет 55"))
        assertTrue(Catalog.ALL.size > 100)
    }

    @Test fun abyssMigration() {
        // старая абиссинка: забор 10 м, длина всасывающей 16 (= 6 м горизонтали), Ø колонны поправлен на 26 при трубе по умолчанию
        val old = WellProfile(id = "a", well = Well(kind = "abyssinian", install = "surface", static = 4.0, dyn = 6.0, dynQ = 1.5, pumpDepth = 10.0, casingID = 26.0, minAbove = 1.0, depth = null),
            sys = Sys(riserPipe = "st32", riserLen = 16.0))
        val n = old.upgraded()
        assertEquals(3, n.fmt)
        assertEquals(10.0, n.well.depth!!, 1e-9)
        assertEquals("pe32-16", n.sys.riserPipe)
        assertEquals(6.0, n.sys.suctLen!!, 1e-9)
        assertEquals(6.0, n.well.dyn!!, 1e-9)            // данные не теряются, просто не используются
        assertTrue(n.legacy != null)
        assertEquals(n, n.upgraded())                     // однократно
        val s0 = Core.suctionAt(0.0, n.sys, n.well, Core.wellModel(n.well))
        assertEquals(4.0, s0.lift, 1e-9)                  // всегда со статики
        assertEquals("surface", Core.installOf(n.well.copy(install = "sub")))
        // трубу меняли руками — её и оставляем
        assertEquals("pe25-16", old.copy(sys = Sys(riserPipe = "pe25-16")).upgraded().sys.riserPipe)
        // обычная скважина — только отметка формата
        val b = WellProfile(id = "b").upgraded(); assertEquals(3, b.fmt); assertTrue(b.legacy == null)
        // кавитация по замерам
        val cn = Core.cavNote(listOf(Measurement("1", 1L, emptyList(), 1.2, cav = 0), Measurement("2", 2L, emptyList(), 1.8, cav = 2)))!!
        assertEquals("crit", cn.first)
    }

    @Test fun sitePairs() {
        val a = WellProfile(id = "a", name = "A", lat = 55.9, lon = 37.5, well = Well(static = 35.0, elev = 182.4, aquifer = "limestone", aqTop = 42.0))
        val b = WellProfile(id = "b", name = "B", lat = 55.9004, lon = 37.5006, well = Well(kind = "abyssinian", static = 3.2, elevDem = 181.1, aqTop = 4.0))
        val c = WellProfile(id = "c", name = "C", lat = 55.9002, lon = 37.5012, well = Well(static = 6.0, elev = 180.2, aquifer = "sand", aqTop = 12.0))
        val za = Core.zInfo(a); assertEquals(147.4, za.Zw!!, 1e-9); assertEquals(140.4, za.Ztop!!, 1e-9); assertEquals("нивелир", za.src)
        val zb = Core.zInfo(b); assertEquals("sand", zb.aquifer); assertEquals("рельеф", zb.src)
        val p = Core.wellPairs(listOf(a, b, c))
        assertEquals(3, p.size)
        val bc = p.first { it.a.id == "b" && it.b.id == "c" }
        assertTrue(bc.sameAq); assertEquals(-3.7, bc.dZw!!, 1e-9); assertEquals(44.0, bc.dist!!, 1.0)
        // атмосфера — по отметке устья
        assertEquals(181.1, Core.effAlt(b.well)!!, 1e-9)
        assertEquals("17,6 км", Core.fmtDist(17623.0)); assertEquals("1,25 км", Core.fmtDist(1250.0)); assertEquals("958 м", Core.fmtDist(958.0))
        val (rows, flow) = Core.pairRows(bc)
        assertTrue(rows.any { it[0] == "Зеркало воды, м абс." && it[3].contains("ниже") }); assertTrue(flow.contains("течёт"))
    }

    @Test fun stepFit() {
        // синтетика s = 2Q + 0,8Q²: подбор должен вернуть B, C
        val pts = listOf(1.0, 2.0, 3.0).map { q -> Core.WPoint(q, 2 * q + 0.8 * q * q, 0.0, "t", null, null) }
        val f = Core.fitWell(pts)!!
        assertEquals("step", f.kind); assertEquals(2.0, f.B, 1e-9); assertEquals(0.8, f.C, 1e-9)
        val w = Well(static = 20.0, pumpDepth = 50.0, minAbove = 3.0, fitB = f.B, fitC = f.C, fitN = 3, fitQmax = 3.0, fitKind = "step")
        val wm = Core.wellModel(w)
        assertEquals(4.6924, wm.Qsafe!!, 1e-3); assertEquals(27.2, Core.levelAt(wm, w, 2.0), 1e-9)
        // одна точка: прямая, предел 1,5× замера
        val w1 = Well(static = 2.0, dyn = 2.5, dynQ = 1.5, pumpDepth = 30.0, minAbove = 3.0)
        val wm1 = Core.wellModel(w1); assertTrue(wm1.capped); assertEquals(2.25, wm1.Qsafe!!, 1e-9)
        // динамика по манометру — обратная к расчёту давления
        val p = Catalog.ALL.first { it.name == "Grundfos SQE 3-55" }
        val wp = WellProfile(id = "x", well = Well(static = 20.0), pumpId = p.id)
        val sh = Core.systemHead(2.0, 0.0, wp.sys, wp.well, Core.WellModel(false, null, null))
        val P = (Core.pumpH(p, 2.0)!! - 28.0 - (sh.total - sh.level)) / Core.M_PER_BAR
        assertEquals(28.0, Core.dynFromPressure(wp, p, 2.0, P, "house")!!, 1e-6)
    }
}
