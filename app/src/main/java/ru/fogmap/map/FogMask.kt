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

    /** Бюджет дырок: перебор = глухая вуаль без дырок (fail-closed). */
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

    /** Перебор бюджета = глухая вуаль (проверяется слоем перед рисованием). */
    fun overBudget(holes: List<Hole>): Boolean = holes.size > MAX_HOLES

    /** Углы дырки в координатах (для проекции слоем). */
    fun holeBounds(h: Hole): Pair<Pair<Double, Double>, Pair<Double, Double>> =
        FogGrid.cellTopLeft(h.rect.x0, h.rect.y0, h.z) to
            FogGrid.cellBottomRight(h.rect.x1, h.rect.y1, h.z)

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
