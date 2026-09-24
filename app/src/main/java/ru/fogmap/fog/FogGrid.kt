package ru.fogmap.fog

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Пирамида тумана WebMercator (fog-pyramid, spec fog-grid/fog-pyramid).
 *
 * База — мелкие клетки [BASE_Z] (~11 м в Минске: только проезжая часть),
 * родители — вверх до [MIN_Z]. ID ячейки — тройка (x, y, z), детерминирован
 * координатами. Хранятся только открытые ячейки, инвариант: наборы разных
 * уровней не пересекаются (родитель существует <=> детей нет) — его держит
 * компакшн в FogRepository.
 *
 * Вся геометрия чистая (без Android/Room): покрыта unit-тестами.
 * Площадь считается в базовых эквивалентах: вес клетки = 4^(BASE_Z - z).
 */
object FogGrid {
    /** База: смена только вместе с полевым тестом (дизайн fog-pyramid). */
    const val BASE_Z = 21
    /** Самый крупный родитель (район целиком — одна строка). */
    const val MIN_Z = 14

    /** Номинальный размер базовой клетки, м (экватор; в Минске ~11 м). */
    const val CELL_BASE_M = 40075000.0 / (1 shl BASE_Z) // ≈19.11
    /**
     * Номинальная площадь базовой клетки, км² — на экваторе (CELL_BASE_M²).
     * Реальная площадь на широте lat — [areaPerBaseCellKm2] (× cos² широты):
     * без этой поправки все км² и проценты завышались в ~2,9 раза
     * на широте Минска (spec fog-grid, change add-region-borders).
     */
    const val AREA_PER_BASE_CELL_KM2 = 0.000365

    /**
     * Средняя широта Беларуси — представительная широта для глобальных метрик
     * (статистика, история, суммарная площадь), у которых нет регионального
     * контекста. Для метрик региона берётся центроид региона.
     */
    const val BELARUS_MEAN_LAT = 53.7

    // Пороги скорости (м/с): 10 км/ч ≈ 2.78 м/с, 50 км/ч ≈ 13.89 м/с.
    const val SPEED_WALK_MS = 2.7778
    const val SPEED_DRIVE_MS = 13.8889

    /** Кисть (м): пешком / город / трасса (fog-pyramid 2.1). */
    const val RADIUS_WALK_M = 15.0
    const val RADIUS_MID_M = 60.0
    const val RADIUS_FAST_M = 100.0

    /**
     * Пороги доверия (контракт с gps-trust-filter, единое место).
     * TRUST_OPEN — ниже туман не открываем; TRUST_HIGH — ниже только минимум.
     */
    const val TRUST_OPEN = 30
    const val TRUST_HIGH = 70
    /** Accuracy хуже — кисть упирается в минимум (должно совпадать с TrustEngine). */
    const val ACC_SOFT_CAP_M = 15f

    /** Коридор длиннее — только круги по концам (защита от телепортов). */
    const val CORRIDOR_LINK_MAX_M = 500.0

    data class Cell(val x: Int, val y: Int, val z: Int = BASE_Z)

    /** Детерминированный ID ячейки уровня [z] по координатам. */
    fun cellFor(lat: Double, lon: Double, z: Int = BASE_Z): Cell {
        val n = 1 shl z
        val xTile = floor((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val yTile = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return Cell(xTile.coerceIn(0, n - 1), yTile.coerceIn(0, n - 1), z)
    }

    /** Родитель 2x2 (уровень z-1). */
    fun parentOf(c: Cell): Cell {
        require(c.z > MIN_Z) { "у родителя $c нет родителя в пирамиде" }
        return Cell(c.x shr 1, c.y shr 1, c.z - 1)
    }

    /** Четверка детей (уровень z+1). */
    fun childrenOf(c: Cell): List<Cell> {
        require(c.z < BASE_Z) { "у базовой $c нет детей" }
        val (x, y, z) = c
        return listOf(
            Cell(x * 2, y * 2, z + 1), Cell(x * 2 + 1, y * 2, z + 1),
            Cell(x * 2, y * 2 + 1, z + 1), Cell(x * 2 + 1, y * 2 + 1, z + 1)
        )
    }

    /** Цепочка предков от z-1 до MIN_Z. */
    fun ancestorsOf(c: Cell): List<Cell> {
        val out = ArrayList<Cell>(c.z - MIN_Z)
        var cur = c
        while (cur.z > MIN_Z) {
            cur = parentOf(cur)
            out.add(cur)
        }
        return out
    }

    /** Вес клетки в базовых эквивалентах: 4^(BASE_Z - z). */
    fun weightOf(z: Int): Long {
        require(z in MIN_Z..BASE_Z)
        return 1L shl (2 * (BASE_Z - z))
    }

    /** Радиус кисти (м) по скорости провайдера (м/с, null = неизвестна → пеший). */
    fun radiusForSpeed(speedMs: Float?): Double = when {
        speedMs == null -> RADIUS_WALK_M
        speedMs < 0 -> RADIUS_WALK_M
        speedMs <= SPEED_WALK_MS -> RADIUS_WALK_M
        speedMs <= SPEED_DRIVE_MS -> RADIUS_MID_M
        else -> RADIUS_FAST_M
    }

    /**
     * Адаптивная кисть (gps-trust-filter 3.1): радиус зависит от доверия.
     * null = не открывать (низкое доверие). Среднее доверие и плохой accuracy —
     * минимум (пешая кисть), высокое — полная по скорости.
     */
    fun brushRadius(speedMs: Float?, trust: Int, accuracy: Float?): Double? {
        if (trust < TRUST_OPEN) return null
        val base = radiusForSpeed(speedMs)
        if (trust < TRUST_HIGH) return minOf(base, RADIUS_WALK_M)
        if (accuracy != null && accuracy > ACC_SOFT_CAP_M) return minOf(base, RADIUS_WALK_M)
        return base
    }

    /** Круг ячеек базы вокруг точки радиусом по скорости (без ворот доверия). */
    fun cellsAround(lat: Double, lon: Double, speedMs: Float?): Set<Cell> =
        cellsWithRadius(lat, lon, radiusForSpeed(speedMs))

    /** Клетки базы адаптивной кисти; пусто = точка туман не открывает. */
    fun cellsForTrust(
        lat: Double,
        lon: Double,
        speedMs: Float?,
        trust: Int,
        accuracy: Float?
    ): Set<Cell> {
        val radius = brushRadius(speedMs, trust, accuracy) ?: return emptySet()
        return cellsWithRadius(lat, lon, radius)
    }

    /**
     * Коридор базы вдоль отрезка (fog-pyramid): покрытие отрезка кистью растром.
     * Непрерывен по построению (без дырок между редкими точками) и ограничен
     * шириной 2*радиус (без километровых пятен). Отрезок длиннее
     * [CORRIDOR_LINK_MAX_M] — только круги по концам.
     */
    fun cellsForSegment(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        radiusM: Double
    ): Set<Cell> {
        if (radiusM <= 0) return emptySet()
        val segLen = haversineM(lat1, lon1, lat2, lon2)
        if (segLen > CORRIDOR_LINK_MAX_M) {
            // Телепорт/разрыв: не тянем нитку через полгорода.
            return cellsWithRadius(lat1, lon1, radiusM) +
                cellsWithRadius(lat2, lon2, radiusM)
        }
        val n = 1 shl BASE_Z
        // Рамка отрезка + радиус — в тайлах базы.
        val padLon = (radiusM + CELL_BASE_M) / 40075000.0 * 360.0
        val midLat = (lat1 + lat2) / 2.0
        val padLat = (radiusM + CELL_BASE_M) / 40075000.0 * 360.0 /
            cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        val lon0 = minOf(lon1, lon2) - padLon
        val lon9 = maxOf(lon1, lon2) + padLon
        val lat0 = minOf(lat1, lat2) - padLat
        val lat9 = maxOf(lat1, lat2) + padLat
        val x0 = (floor((lon0 + 180.0) / 360.0 * n).toInt()).coerceAtLeast(0)
        val x1 = (floor((lon9 + 180.0) / 360.0 * n).toInt()).coerceAtMost(n - 1)
        val yTop = cellFor(lat9.coerceIn(-85.05112878, 85.05112878), lon1).y
        val yBottom = cellFor(lat0.coerceIn(-85.05112878, 85.05112878), lon1).y
        val limit = radiusM + CELL_BASE_M / 2.0
        val out = HashSet<Cell>()
        for (x in x0..x1) {
            for (y in yTop..yBottom) {
                val (clat, clon) = cellCenter(x, y)
                if (pointSegDistM(clat, clon, lat1, lon1, lat2, lon2) <= limit) {
                    out.add(Cell(x, y, BASE_Z))
                }
            }
        }
        return out
    }

    /**
     * Предок клетки на уровне [z] (для пятен присутствия маски).
     * [z] SHALL лежать в [MIN_Z]..[c.z].
     */
    fun ancestorAt(c: Cell, z: Int): Cell {
        require(z in MIN_Z..c.z) { "уровень $z вне пирамиды для $c" }
        var cur = c
        while (cur.z > z) cur = parentOf(cur)
        return cur
    }

    /** Верхний-левый угол клетки (широта, долгота), WebMercator. */
    fun cellTopLeft(x: Int, y: Int, z: Int = BASE_Z): Pair<Double, Double> {
        val n = 1 shl z
        val lon = x.toDouble() / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1 - 2.0 * y / n)))
        return Math.toDegrees(latRad) to lon
    }

    /** Нижний-правый угол прямоугольника клеток (широта, долгота). */
    fun cellBottomRight(x1: Int, y1: Int, z: Int = BASE_Z): Pair<Double, Double> =
        cellTopLeft(x1 + 1, y1 + 1, z).let { (lat, lon) -> lat to lon }

    /**
     * Реальная площадь базовой клетки на широте, км²: номинал экватора × cos².
     * На широте Минска (53,9°) ≈ 0,000127 км² (клетка ~11 м), на экваторе — 0,000365.
     */
    fun areaPerBaseCellKm2(lat: Double): Double {
        val c = Math.cos(Math.toRadians(lat))
        return AREA_PER_BASE_CELL_KM2 * c * c
    }

    /**
     * Открытая площадь в км² по счетчику базовых эквивалентов. Широта не
     * региональная — средняя широта Беларуси (глобальные метрики); региональные
     * метрики передают центроид региона (spec fog-grid).
     */
    fun areaKm2(baseCells: Long, lat: Double = BELARUS_MEAN_LAT): Double =
        baseCells * areaPerBaseCellKm2(lat)

    // --- Внутренняя геометрия ---

    private fun cellsWithRadius(lat: Double, lon: Double, radius: Double): Set<Cell> {
        val steps = (radius / CELL_BASE_M).toInt().coerceAtLeast(1)
        val center = cellFor(lat, lon, BASE_Z)
        val result = HashSet<Cell>()
        for (dx in -steps..steps) {
            for (dy in -steps..steps) {
                val distCells = sqrt((dx * dx + dy * dy).toDouble())
                if (distCells * CELL_BASE_M <= radius + CELL_BASE_M / 2.0) {
                    result.add(Cell(center.x + dx, center.y + dy, BASE_Z))
                }
            }
        }
        return result
    }

    /** Центр базовой клетки в координатах. */
    fun cellCenter(x: Int, y: Int): Pair<Double, Double> {
        val n = 1 shl BASE_Z
        val lon = (x + 0.5) / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1 - 2.0 * (y + 0.5) / n)))
        return Math.toDegrees(latRad) to lon
    }

    internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Расстояние от точки до отрезка, м (эквидистантное приближение). */
    internal fun pointSegDistM(
        lat: Double, lon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2.0)).coerceAtLeast(0.2)
        fun px(lo: Double) = (lo - lon1) * 111320.0 * k
        fun py(la: Double) = (la - lat1) * 111320.0
        val vx = px(lon2); val vy = py(lat2)
        val wx = px(lon); val wy = py(lat)
        val len2 = vx * vx + vy * vy
        if (len2 == 0.0) return sqrt(wx * wx + wy * wy)
        val t = ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
        val dx = wx - t * vx; val dy = wy - t * vy
        return sqrt(dx * dx + dy * dy)
    }
}
