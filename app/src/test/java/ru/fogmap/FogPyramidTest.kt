package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.fog.FogGrid
import ru.fogmap.map.FogMask

/** Пирамида fog-pyramid 1.1/2.1: родители, веса, коридор, уровни чтения. */
class FogPyramidTest {
    @Test
    fun `родитель-дети обратимы`() {
        val c = FogGrid.cellFor(53.9, 27.56)
        val p = FogGrid.parentOf(c)
        assertEquals(20, p.z)
        assertTrue(FogGrid.childrenOf(p).contains(c))
        assertEquals(p, FogGrid.parentOf(FogGrid.childrenOf(p).first()))
    }

    @Test
    fun `родитель базы равен клетке z20 той же точки`() {
        val lat = 53.9; val lon = 27.56
        assertEquals(FogGrid.cellFor(lat, lon, 20), FogGrid.parentOf(FogGrid.cellFor(lat, lon)))
    }

    @Test
    fun `цепочка предков доходит до MIN_Z`() {
        val chain = FogGrid.ancestorsOf(FogGrid.cellFor(53.9, 27.56))
        assertEquals(FogGrid.BASE_Z - FogGrid.MIN_Z, chain.size)
        assertEquals(FogGrid.MIN_Z, chain.last().z)
    }

    @Test
    fun `веса — 4 ребенка равны родителю`() {
        assertEquals(1L, FogGrid.weightOf(21))
        assertEquals(4L, FogGrid.weightOf(20))
        assertEquals(16L, FogGrid.weightOf(19))
        assertEquals(16384L, FogGrid.weightOf(14))
        // Площадь сохраняется при компакшне.
        assertEquals(
            4 * FogGrid.areaKm2(1), FogGrid.areaKm2(FogGrid.weightOf(20)), 1e-12
        )
    }

    @Test
    fun `коридор непрерывен вдоль отрезка 110м`() {
        val lat1 = 53.9; val lon = 27.56
        val lat2 = 53.9 + 0.00099 // ~110 м севернее
        val set = FogGrid.cellsForSegment(lat1, lon, lat2, lon, 60.0)
        assertFalse(set.isEmpty())
        // Каждая проба вдоль отрезка покрыта.
        var t = 0.0
        while (t <= 1.0) {
            val cell = FogGrid.cellFor(lat1 + (lat2 - lat1) * t, lon)
            assertTrue("дырка на t=$t", set.contains(cell))
            t += 0.05
        }
    }

    @Test
    fun `коридор ограничен шириной кисти`() {
        val lat1 = 53.9; val lon = 27.56
        val lat2 = 53.9 + 0.00099
        val set = FogGrid.cellsForSegment(lat1, lon, lat2, lon, 60.0)
        // Точка в 150 м вбок от середины — вне коридора 60м.
        val mid = (lat1 + lat2) / 2.0
        val side = FogGrid.cellFor(mid, lon + 0.0023) // ~150 м восточнее
        assertFalse(set.contains(side))
    }

    @Test
    fun `длинный разрыв не тянет нитку`() {
        val set = FogGrid.cellsForSegment(53.9, 27.56, 53.909, 27.56, 60.0) // ~1000м
        // Середина отрезка не открыта — только круги по концам.
        assertFalse(set.contains(FogGrid.cellFor(53.9045, 27.56)))
        assertTrue(set.contains(FogGrid.cellFor(53.9, 27.56)))
    }

    @Test
    fun `маска вблизи точная, дальше лестница пятен`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        val cells = setOf(base)
        val near = FogMask.holesForZoom(cells, 16f)
        assertEquals(FogMask.Mode.PRECISE, near.mode)
        assertTrue(near.holes.any { it.z == FogGrid.BASE_Z })
        // Чистый средний зум без перебора — пятна z16 как раньше.
        val mid = FogMask.holesForZoom(cells, 12f)
        assertEquals(FogMask.Mode.PRESENCE_Z16, mid.mode)
        assertTrue(mid.holes.isNotEmpty() && mid.holes.all { it.z == FogMask.MID_PRESENCE_Z })
        // Дальняя лесенка без перебора — сначала z16 (змейка, не клякса).
        val far = FogMask.holesForZoom(cells, 10f)
        assertEquals(FogMask.Mode.PRESENCE_Z16, far.mode)
        assertTrue(far.holes.isNotEmpty() && far.holes.all { it.z == FogMask.MID_PRESENCE_Z })
        assertEquals(
            FogGrid.ancestorAt(base, FogMask.MID_PRESENCE_Z),
            FogMask.presenceCells(cells, FogMask.MID_PRESENCE_Z).single()
        )
    }

    @Test
    fun `покрытие предком выкидывает детей`() {
        val parent = FogGrid.cellFor(53.9, 27.56, 20)
        val kids = FogGrid.childrenOf(parent).toSet()
        val existing = mapOf(20 to setOf(parent))
        assertTrue(FogRepository.filterCovered(kids, existing).isEmpty())
        // Частичное покрытие не прячет: чужой родитель не влияет.
        val stranger = FogGrid.cellFor(54.5, 28.5, 20)
        assertEquals(kids, FogRepository.filterCovered(kids, mapOf(20 to setOf(stranger))))
    }

    @Test
    fun `промоушн только полной четверки`() {
        val parent = FogGrid.cellFor(53.9, 27.56, 20)
        val kids = FogGrid.childrenOf(parent).toSet()
        assertEquals(
            setOf(parent),
            FogRepository.findPromotable(kids, setOf(parent))
        )
        assertTrue(
            FogRepository.findPromotable(kids.drop(1).toSet(), setOf(parent)).isEmpty()
        )
        assertTrue(FogRepository.findPromotable(kids, emptySet()).isEmpty())
    }

    @Test
    fun `открываемое множество — круги плюс коридор`() {
        val a = RawPoint(time = 0, lat = 53.9, lon = 27.56, acc = 8f, speed = 14f)
        val b = RawPoint(time = 8000, lat = 53.90099, lon = 27.56, acc = 8f, speed = 14f)
        val open = FogRepository.openBaseCells(listOf(a, b), null)
        // Круги по концам плюс непрерывный коридор между ними.
        assertTrue(open.size > FogGrid.cellsForTrust(a.lat, a.lon, a.speed, 100, 8f).size)
        var t = 0.0
        while (t <= 1.0) {
            val cell = FogGrid.cellFor(a.lat + (b.lat - a.lat) * t, a.lon)
            assertTrue("дырка на t=$t", open.contains(cell))
            t += 0.1
        }
    }

    @Test
    fun `недоверенные точки ничего не открывают`() {
        val a = RawPoint(time = 0, lat = 53.9, lon = 27.56, acc = 8f, speed = 14f,
            trust = 10, openFog = false)
        assertTrue(FogRepository.openBaseCells(listOf(a), null).isEmpty())
    }

    @Test
    fun `дырка не схлопывается в ноль`() {
        val tiny = FogMask.HolePx(10f, 10f, 10.5f, 10.5f)
        val fixed = FogMask.ensureMinPx(tiny)
        assertEquals(FogMask.MIN_HOLE_PX, fixed.right - fixed.left, 1e-6f)
        assertEquals(FogMask.MIN_HOLE_PX, fixed.bottom - fixed.top, 1e-6f)
        val big = FogMask.HolePx(0f, 0f, 100f, 50f)
        assertEquals(big, FogMask.ensureMinPx(big))
    }

    @Test
    fun `без перебора на границе 13 точные дырки сохраняются`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        val res = FogMask.holesForZoom(setOf(base), 13f)
        assertEquals(FogMask.Mode.PRECISE, res.mode)
        assertTrue(res.holes.any { it.z == FogGrid.BASE_Z })
        assertFalse(FogMask.overBudget(res.holes))
    }

    @Test
    fun `перебор точных дырок откатывается сначала на пятна z19`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Диагональ: каждый ряд свой run, вертикальная склейка не работает —
        // 900 клеток дают > 800 точных дырок (окно 13–13,6 в городе).
        // Соседние предки z19 частично клеятся и влезают в бюджет.
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i, base.y + 2 * i, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 13f)
        assertEquals(FogMask.Mode.PRESENCE_Z19, res.mode)
        assertFalse("fallback не должен оставлять черный экран", res.holes.isEmpty())
        assertFalse("пятна должны влезать в бюджет", FogMask.overBudget(res.holes))
        assertTrue(res.holes.all { it.z == FogMask.FINE_PRESENCE_Z })
    }

    @Test
    fun `перебор z19 падает дальше на z18`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Шаг 4/8 базовой: предки z19 идут через строку (вертикальная склейка
        // не срабатывает, 900 дырок — перебор), а предки z18 в соседних
        // строках частично совпадают и клеятся в ~450 прямоугольников.
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i * 4, base.y + i * 8, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 13f)
        assertEquals(FogMask.Mode.PRESENCE_Z18, res.mode)
        assertFalse("вторая ступень не должна оставлять черный экран", res.holes.isEmpty())
        assertFalse("пятна z18 должны влезать в бюджет", FogMask.overBudget(res.holes))
        assertTrue(res.holes.all { it.z == FogMask.NEAR_PRESENCE_Z })
    }

    @Test
    fun `перебор z19 и z18 падает дальше на z16`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Шаг 16 базовых клеток: предки z19 (шаг 4) и z18 (шаг 2) изолированы,
        // а предки z16 с шагом 0–1 частично клеятся и влезают в бюджет.
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i * 16, base.y + i * 16, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 13f)
        assertEquals(FogMask.Mode.PRESENCE_Z16, res.mode)
        assertFalse("третья ступень не должна оставлять черный экран", res.holes.isEmpty())
        assertFalse("пятна z16 должны влезать в бюджет", FogMask.overBudget(res.holes))
        assertTrue(res.holes.all { it.z == FogMask.MID_PRESENCE_Z })
    }

    @Test
    fun `перебор пятен остается перебором для глухой вуали`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Шаг 64 базовые клетки: предки изолированы на всех ступенях
        // (z19/Z18/z16) — второй уровень защиты.
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i * 64, base.y + i * 64, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 13f)
        assertEquals(FogMask.Mode.PRESENCE_Z16, res.mode)
        assertTrue(FogMask.overBudget(res.holes))
    }

    @Test
    fun `тонкий маршрут внизу дает змейку z16, а не кляксу`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Короткая нитка вдоль улицы: пятна z16 ложатся змейкой.
        val cells = (0 until 40).map { i ->
            FogGrid.Cell(base.x + i, base.y + i / 4, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 10f)
        assertEquals(FogMask.Mode.PRESENCE_Z16, res.mode)
        assertFalse(FogMask.overBudget(res.holes))
        assertTrue(res.holes.all { it.z == FogMask.MID_PRESENCE_Z })
    }

    @Test
    fun `перебор z16 внизу падает на z14`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        // Шаг 64: предки z16 изолированы (шаг 2 — перебор), предки z14
        // с шагом 0–1 клеятся парами и влезают в бюджет.
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i * 64, base.y + i * 64, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(cells, 10f)
        assertEquals(FogMask.Mode.PRESENCE_Z14, res.mode)
        assertFalse(FogMask.overBudget(res.holes))
        assertTrue(res.holes.all { it.z == FogMask.FAR_PRESENCE_Z })
    }

    @Test
    fun `режим маппится в уровень для диагностики`() {
        assertEquals(0, FogMask.Mode.PRECISE.presenceZ())
        assertEquals(19, FogMask.Mode.PRESENCE_Z19.presenceZ())
        assertEquals(18, FogMask.Mode.PRESENCE_Z18.presenceZ())
        assertEquals(16, FogMask.Mode.PRESENCE_Z16.presenceZ())
        assertEquals(14, FogMask.Mode.PRESENCE_Z14.presenceZ())
    }

    @Test
    fun `fallback не падает на грубых родителях компакшна`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        val coarse = FogGrid.cellFor(53.9, 27.56, FogGrid.MIN_Z)
        val fine = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i, base.y + 2 * i, FogGrid.BASE_Z)
        }.toSet()
        val res = FogMask.holesForZoom(fine + coarse, 13f)
        assertEquals(FogMask.Mode.PRESENCE_Z19, res.mode)
        assertFalse(res.holes.isEmpty())
        assertTrue(res.holes.all { it.z == FogMask.FINE_PRESENCE_Z || it.z == FogGrid.MIN_Z })
    }

    @Test
    fun `гистерезис держит пятна при колебании вокруг лимита`() {
        // Уход в пятна при переборе...
        assertTrue(FogMask.resolvePresenceStuck(FogMask.MAX_HOLES + 10, false))
        // ...удержание при 790 в залипшем режиме (дребезг не переключает)...
        assertTrue(FogMask.resolvePresenceStuck(FogMask.MAX_HOLES - 10, true))
        // ...свободный режим при тех же 790 пятна не включает...
        assertFalse(FogMask.resolvePresenceStuck(FogMask.MAX_HOLES - 10, false))
        // ...возврат к точным только ниже порога возврата...
        assertFalse(FogMask.resolvePresenceStuck(FogMask.RETURN_THRESHOLD, true))
        assertFalse(FogMask.resolvePresenceStuck(FogMask.RETURN_THRESHOLD - 1, true))
        // ...а полоса гистерезиса существует.
        assertTrue(FogMask.RETURN_THRESHOLD < FogMask.MAX_HOLES)
    }

    @Test
    fun `точный подсчет с cap не считает лишнего`() {
        val base = FogGrid.cellFor(53.9, 27.56)
        val cells = (0 until FogMask.MAX_HOLES + 100).map { i ->
            FogGrid.Cell(base.x + i, base.y + 2 * i, FogGrid.BASE_Z)
        }.toSet()
        val cap = FogMask.RETURN_THRESHOLD + 1
        assertEquals(cap, FogMask.preciseHoleCount(cells, 13f, null, cap))
        assertEquals(1, FogMask.preciseHoleCount(setOf(base), 13f, null, cap))
        assertEquals(0, FogMask.preciseHoleCount(setOf(base), 12f, null, cap))
    }
}
