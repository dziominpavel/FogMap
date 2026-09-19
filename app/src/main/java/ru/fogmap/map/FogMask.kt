package ru.fogmap.map

import ru.fogmap.fog.FogGrid
import ru.fogmap.fog.FogGrid.Cell

/**
 * Чистая логика маски тумана (fog-mask-canvas, без MapKit/Compose):
 * константы вуали, выбор дырок по зуму, пятна присутствия, бюджет.
 * Покрывается unit-тестами; проекция в пиксели и рисование — в
 * [FogMaskOverlay] и [ru.fogmap.ui.screens.MapScreen].
 */
object FogMask {
    /** Глухая вуаль 100%: тот же тон, что раньше, но без просвечивания. */
    const val VEIL_DARK = 0xFF0F1419.toInt()
    const val VEIL_LIGHT = 0xFFF6F8F7.toInt()

    /**
     * Ручной fallback на дневную карту при темной оболочке: выставить true
     * одной строкой, если проверка на устройстве покажет плохой контраст.
     * Переехал из удаленного FogLayer без смены смысла.
     */
    const val FORCE_DAY_MAP = false

    /** Уровень пятен присутствия: средний зум (улицы видны, но мелочь тяжела). */
    const val MID_PRESENCE_Z = 16

    /** Уровень пятен присутствия: дальний зум (обзор города). */
    const val FAR_PRESENCE_Z = 14

    /** Зум переключения: >= — точные дырки, < — пятна присутствия. */
    const val DETAIL_ZOOM = 13f

    /** Ниже — только крупные пятна (обзор города целиком). */
    const val FAR_ZOOM = 11f

    /**
     * Бюджет дырок: перебор точных дырок = fallback на пятна присутствия,
     * перебор и пятен = глухая вуаль без дырок (fail-closed).
     */
    const val MAX_HOLES = 800

    /** Широкое мягкое перо (1.3: принято 14px) и скругление (принято 6px). */
    const val FEATHER_PX = 14f
    const val CORNER_PX = 6f

    /** Минимум на дырку, чтобы тропа не схлопывалась в ноль. */
    const val MIN_HOLE_PX = 2f

    /** Дырка в пикселях экрана (физических) для оверлея. */
    data class HolePx(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Дырка в клетках с уровнем (после склейки). */
    data class Hole(val z: Int, val rect: FogRects.Rect)

    /** Рамка viewport в координатах (срез в памяти, без БД на сдвиг). */
    data class RegionBox(
        val topLat: Double,
        val bottomLat: Double,
        val leftLon: Double,
        val rightLon: Double
    ) {
        /** Та же клетка видна ли хоть краем (по углам клетки). */
        fun intersects(x0: Int, x1: Int, y0: Int, y1: Int, z: Int): Boolean {
            val (tlLat, tlLon) = FogGrid.cellTopLeft(x0, y0, z)
            val (brLat, brLon) = FogGrid.cellBottomRight(x1, y1, z)
            return tlLon <= rightLon && brLon >= leftLon &&
                tlLat >= bottomLat && brLat <= topLat
        }
    }

    /** Срез клеток по viewport в памяти (2.3: ноль запросов Room на сдвиг). */
    fun viewportCells(cells: Set<Cell>, region: RegionBox?): Set<Cell> {
        if (region == null || cells.isEmpty()) return cells
        return cells.filterTo(HashSet()) { c ->
            val (tlLat, tlLon) = FogGrid.cellTopLeft(c.x, c.y, c.z)
            val (brLat, brLon) = FogGrid.cellBottomRight(c.x, c.y, c.z)
            tlLon <= region.rightLon && brLon >= region.leftLon &&
                tlLat >= region.bottomLat && brLat <= region.topLat
        }
    }

    /**
     * Пятна присутствия: каждая клетка — в предка уровня [z].
     * Только для показа; данные не меняются.
     */
    fun presenceCells(cells: Set<Cell>, z: Int): Set<Cell> =
        cells.mapTo(HashSet()) { FogGrid.ancestorAt(it, z) }

    /**
     * Дырки по зуму: вблизи (>= [DETAIL_ZOOM]) — точное объединение всех
     * уровней (наборы уровней не пересекаются по инварианту компакшна),
     * средний зум — пятна [MID_PRESENCE_Z], обзор города — [FAR_PRESENCE_Z].
     * Лестница вместо одного порога: иначе пятно z14 выглядит гигантским
     * рядом с точной ниткой (поп на границе 13). Склейка — [FogRects.merge].
     *
     * Перебор точных дырок (> [MAX_HOLES]) откатывается на пятна
     * [MID_PRESENCE_Z] по тому же срезу viewport (без скалы 800 -> 0);
     * перебор и пятен возвращается как есть — слой рисует глухую вуаль.
     */
    fun holesForZoom(cells: Set<Cell>, zoom: Float, region: RegionBox? = null): List<Hole> {
        if (cells.isEmpty()) return emptyList()
        if (zoom < FAR_ZOOM) return presenceHoles(viewportCells(cells, region), FAR_PRESENCE_Z)
        if (zoom < DETAIL_ZOOM) return presenceHoles(viewportCells(cells, region), MID_PRESENCE_Z)
        val visible = viewportCells(cells, region)
        if (visible.isEmpty()) return emptyList()
        val byLevel = visible.groupBy { it.z }
        val out = ArrayList<Hole>()
        for ((z, levelCells) in byLevel) {
            for (r in FogRects.merge(levelCells.toSet())) {
                out.add(Hole(z, r))
                if (out.size > MAX_HOLES) return fallbackHoles(visible)
            }
        }
        return out
    }

    /**
     * Fallback при переборе точных дырок: пятна [MID_PRESENCE_Z] по тому же
     * срезу viewport. Клетки грубее [MID_PRESENCE_Z] (родители компакшна
     * z14–z15, у которых нет предка z16) рисуются как есть своим уровнем —
     * иначе [FogGrid.ancestorAt] бросил бы require. Перебор и здесь
     * возвращается как есть для глухой вуали вторым уровнем защиты.
     */
    private fun fallbackHoles(visible: Set<Cell>): List<Hole> {
        val fine = visible.filterTo(HashSet()) { it.z >= MID_PRESENCE_Z }
        val coarseByLevel = visible.filter { it.z < MID_PRESENCE_Z }.groupBy { it.z }
        val out = ArrayList<Hole>()
        if (fine.isNotEmpty()) {
            for (h in presenceHoles(fine, MID_PRESENCE_Z)) {
                out.add(h)
                if (out.size > MAX_HOLES) return out
            }
        }
        for ((z, levelCells) in coarseByLevel) {
            for (r in FogRects.merge(levelCells.toSet())) {
                out.add(Hole(z, r))
                if (out.size > MAX_HOLES) return out
            }
        }
        return out
    }

    fun presenceHoles(cells: Set<Cell>, z: Int): List<Hole> {
        val presence = presenceCells(cells, z)
        if (presence.isEmpty()) return emptyList()
        return FogRects.merge(presence).map { Hole(z, it) }
    }

    /**
     * Перебор бюджета (проверяется слоем перед рисованием): true = пятен
     * тоже слишком много, рисовать глухую вуаль. После fallback true
     * означает именно этот случай, а не перебор точных дырок.
     */
    fun overBudget(holes: List<Hole>): Boolean = holes.size > MAX_HOLES

    /** Углы дырки в координатах (для проекции слоем). */
    fun holeBounds(h: Hole): Pair<Pair<Double, Double>, Pair<Double, Double>> =
        FogGrid.cellTopLeft(h.rect.x0, h.rect.y0, h.z) to
            FogGrid.cellBottomRight(h.rect.x1, h.rect.y1, h.z)

    /**
     * Живая дырка-предпросмотр вокруг текущего фикса (location-cursor).
     * Только показ: в БД, площадь и счетчики не попадает, вызывается слоем
     * из `MapScreen` и добавляется к дыркам из памяти перед проекцией.
     * Размер — пешая кисть ([FogGrid.RADIUS_WALK_M]), как потом реально
     * откроется подтверждением: переход без хлопка. Лестница зумов та же:
     * >= [DETAIL_ZOOM] — точная, 11–13 — пятно [MID_PRESENCE_Z],
     * ниже [FAR_ZOOM] — пусто (видна только точка).
     */
    fun liveHoles(lat: Double, lon: Double, zoom: Float): List<Hole> {
        if (zoom < FAR_ZOOM) return emptyList()
        val cells = FogGrid.cellsAround(lat, lon, null)
        if (cells.isEmpty()) return emptyList()
        if (zoom < DETAIL_ZOOM) return presenceHoles(cells, MID_PRESENCE_Z)
        return FogRects.merge(cells).map { Hole(FogGrid.BASE_Z, it) }
    }

    /** Растянуть дырку до минимума [MIN_HOLE_PX] вокруг центра. */
    fun ensureMinPx(h: HolePx, min: Float = MIN_HOLE_PX): HolePx {
        val w = h.right - h.left
        val hgt = h.bottom - h.top
        val cx = (h.left + h.right) / 2f
        val cy = (h.top + h.bottom) / 2f
        val nw = maxOf(w, min)
        val nh = maxOf(hgt, min)
        return HolePx(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
    }
}
