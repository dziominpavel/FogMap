package ru.fogmap.fog

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * Сетка тумана WebMercator 100 м (ЧП-2, spec fog-grid).
 *
 * Мир делится на квадратные ячейки WebMercator со стороной [CELL_SIZE_M].
 * ID ячейки — целочисленная пара (x, y) на фикс-зуме [GRID_ZOOM], где клетка ≈100 м.
 * Храним только открытые ячейки visited_cells(x, y).
 *
 * Радиус открытия — круг ячеек вокруг точки по скорости провайдера:
 * - до 10 км/ч → ~50 м (одна точка пешком — крест из 5 клеток, не 3×3)
 * - 10–50 км/ч → ~200 м
 * - выше 50 км/ч → ~500 м
 *
 * Площадь = кол-во ячеек × 0,01 км² (принято для MVP без учета широты).
 */
object FogGrid {
    const val CELL_SIZE_M = 100.0
    const val GRID_ZOOM = 17
    const val AREA_PER_CELL_KM2 = 0.01

    // Пороги скорости (м/с): 10 км/ч ≈ 2.78 м/с, 50 км/ч ≈ 13.89 м/с.
    const val SPEED_WALK_MS = 2.7778
    const val SPEED_DRIVE_MS = 13.8889

    const val RADIUS_WALK_M = 50.0
    const val RADIUS_MID_M = 200.0
    const val RADIUS_FAST_M = 500.0

    data class Cell(val x: Int, val y: Int)

    /** Детерминированный ID ячейки 100×100 м по координатам. */
    fun cellFor(lat: Double, lon: Double): Cell {
        val n = 1 shl GRID_ZOOM
        val xTile = floor((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val yTile = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return Cell(xTile.coerceIn(0, n - 1), yTile.coerceIn(0, n - 1))
    }

    /** Радиус открытия (м) по скорости провайдера (м/с, null = неизвестна → пеший). */
    fun radiusForSpeed(speedMs: Float?): Double = when {
        speedMs == null -> RADIUS_WALK_M
        speedMs < 0 -> RADIUS_WALK_M
        speedMs <= SPEED_WALK_MS -> RADIUS_WALK_M
        speedMs <= SPEED_DRIVE_MS -> RADIUS_MID_M
        else -> RADIUS_FAST_M
    }

    /**
     * Круг ячеек вокруг точки радиусом по скорости.
     * Возвращает саму ячейку + соседей, чьи центры ближе радиуса.
     */
    fun cellsAround(lat: Double, lon: Double, speedMs: Float?): Set<Cell> {
        val radius = radiusForSpeed(speedMs)
        val steps = (radius / CELL_SIZE_M).toInt().coerceAtLeast(1)
        val center = cellFor(lat, lon)
        // Грубая оценка метров в ячейках по широте не нужна: сетка равномерна
        // в WebMercator-пикселях на фикс-зуме, шаг 1 клетка = ~100 м.
        val result = HashSet<Cell>()
        for (dx in -steps..steps) {
            for (dy in -steps..steps) {
                val distCells = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                if (distCells * CELL_SIZE_M <= radius + CELL_SIZE_M / 2.0) {
                    result.add(Cell(center.x + dx, center.y + dy))
                }
            }
        }
        return result
    }

    fun areaKm2(cells: Long): Double = cells * AREA_PER_CELL_KM2
}
