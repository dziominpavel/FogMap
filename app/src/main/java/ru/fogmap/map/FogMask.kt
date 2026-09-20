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

    /**
     * Промежуточный уровень fallback (smooth-fog-zoom): первая ступень отката
     * при переборе точных дырок (~90 м в Минске). Прыжок x8 вместо x32 сразу
     * на z16; при переборе и z18 — откат на [MID_PRESENCE_Z] как раньше.
     */
    const val NEAR_PRESENCE_Z = 18

    /**
     * Мелкая ступень fallback (smooth-fog-zoom-2): первая ступень отката
     * (~45 м в Минске). Прыжок x4 вместо x8; в сверхплотных вьюпортах
     * перебирает и падает дальше по лесенке.
     */
    const val FINE_PRESENCE_Z = 19

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

    /**
     * Порог возврата гистерезиса (smooth-fog-zoom): раз упав в пятна,
     * держим их пока точных дырок больше этого порога. Строго меньше
     * [MAX_HOLES], полоса гистерезиса — 200 дырок.
     */
    const val RETURN_THRESHOLD = 600

    /** Широкое мягкое перо (1.3: принято 14px) и скругление (принято 6px). */
    const val FEATHER_PX = 14f
    const val CORNER_PX = 6f

    /** Минимум на дырку, чтобы тропа не схлопывалась в ноль. */
    const val MIN_HOLE_PX = 2f

    /**
     * Дырка в пикселях экрана (физических) для оверлея. Флаг [coarse]
     * отмечает грубые пятна присутствия: оверлей рисует их с увеличенным
     * скруглением и пером, иначе ступени сетки выглядят остроугольно.
     */
    data class HolePx(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val coarse: Boolean = false
    )

    /** Дырка в клетках с уровнем (после склейки). */
    data class Hole(val z: Int, val rect: FogRects.Rect)

    /**
     * Режим показа дырок (smooth-fog-zoom): слой отличает точное от
     * fallback-ступеней и считает fallback в диагностике отдельно от вуали.
     */
    enum class Mode {
        PRECISE, PRESENCE_Z19, PRESENCE_Z18, PRESENCE_Z16, PRESENCE_Z14;

        /**
         * Уровень отрисованных пятен для диагностики (smooth-fog-zoom-2):
         * 0 — точное без пятен, иначе z-уровень ступени.
         */
        fun presenceZ(): Int = when (this) {
            PRECISE -> 0
            PRESENCE_Z19 -> FINE_PRESENCE_Z
            PRESENCE_Z18 -> NEAR_PRESENCE_Z
            PRESENCE_Z16 -> MID_PRESENCE_Z
            PRESENCE_Z14 -> FAR_PRESENCE_Z
        }
    }

    /** Дырки кадра вместе с режимом показа. */
    data class HolesResult(val holes: List<Hole>, val mode: Mode)

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
     * Перебор точных дырок (> [MAX_HOLES]) откатывается лесенкой:
     * сначала пятна [FINE_PRESENCE_Z], при переборе — [NEAR_PRESENCE_Z],
     * затем [MID_PRESENCE_Z] по тому же срезу viewport (без скалы 800 -> 0);
     * перебор и пятен возвращается как есть — слой рисует глухую вуаль.
     * Ниже [FAR_ZOOM] (smooth-fog-zoom-2): сначала пятна [MID_PRESENCE_Z],
     * при переборе — [FAR_PRESENCE_Z]; тонкий маршрут выглядит змейкой,
     * а не кляксой на полгорода.
     */
    fun holesForZoom(cells: Set<Cell>, zoom: Float, region: RegionBox? = null): HolesResult {
        if (cells.isEmpty()) return HolesResult(emptyList(), Mode.PRECISE)
        if (zoom < FAR_ZOOM) {
            val sliced = viewportCells(cells, region)
            val near = presenceHoles(sliced, MID_PRESENCE_Z)
            if (!overBudget(near)) return HolesResult(near, Mode.PRESENCE_Z16)
            return HolesResult(presenceHoles(sliced, FAR_PRESENCE_Z), Mode.PRESENCE_Z14)
        }
        if (zoom < DETAIL_ZOOM) {
            return HolesResult(presenceHoles(viewportCells(cells, region), MID_PRESENCE_Z), Mode.PRESENCE_Z16)
        }
        val visible = viewportCells(cells, region)
        if (visible.isEmpty()) return HolesResult(emptyList(), Mode.PRECISE)
        val byLevel = visible.groupBy { it.z }
        val out = ArrayList<Hole>()
        for ((z, levelCells) in byLevel) {
            for (r in FogRects.merge(levelCells.toSet())) {
                out.add(Hole(z, r))
                if (out.size > MAX_HOLES) return fallbackResult(visible)
            }
        }
        return HolesResult(out, Mode.PRECISE)
    }

    /**
     * Лесенка fallback при переборе точных дырок: пятна [FINE_PRESENCE_Z],
     * затем [NEAR_PRESENCE_Z] и [MID_PRESENCE_Z]. Клетки грубее целевого
     * уровня (родители компакшна, у которых нет предка этого уровня)
     * рисуются как есть своим уровнем — иначе [FogGrid.ancestorAt] бросил
     * бы require. Перебор и здесь возвращается как есть для глухой вуали
     * вторым уровнем защиты.
     */
    private fun fallbackResult(visible: Set<Cell>): HolesResult {
        val fine = fallbackHoles(visible, FINE_PRESENCE_Z)
        if (!overBudget(fine)) return HolesResult(fine, Mode.PRESENCE_Z19)
        val near = fallbackHoles(visible, NEAR_PRESENCE_Z)
        if (!overBudget(near)) return HolesResult(near, Mode.PRESENCE_Z18)
        val mid = fallbackHoles(visible, MID_PRESENCE_Z)
        return HolesResult(mid, Mode.PRESENCE_Z16)
    }

    /**
     * Пятна fallback для показа при залипании гистерезиса (слой уже решил
     * держать пятна, точный подсчет влезает в [RETURN_THRESHOLD], но режим
     * еще не отпустил). Та же лесенка, что внутри [holesForZoom]; режим
     * нужен слою для диагностики (`presence_z`).
     */
    fun presenceFallbackHoles(cells: Set<Cell>, zoom: Float, region: RegionBox? = null): HolesResult =
        fallbackResult(viewportCells(cells, region))

    private fun fallbackHoles(visible: Set<Cell>, z: Int): List<Hole> {
        val fine = visible.filterTo(HashSet()) { it.z >= z }
        val coarseByLevel = visible.filter { it.z < z }.groupBy { it.z }
        val out = ArrayList<Hole>()
        if (fine.isNotEmpty()) {
            for (h in presenceHoles(fine, z)) {
                out.add(h)
                if (out.size > MAX_HOLES) return out
            }
        }
        for ((cz, levelCells) in coarseByLevel) {
            for (r in FogRects.merge(levelCells.toSet())) {
                out.add(Hole(cz, r))
                if (out.size > MAX_HOLES) return out
            }
        }
        return out
    }

    /**
     * Число точных дырок с ранним выходом на [cap] (для гистерезиса слоя:
     * решение об удержании пятен без полного merge). Возвращает не больше
     * [cap]; точное значение выше cap слою не нужно.
     */
    fun preciseHoleCount(
        cells: Set<Cell>,
        zoom: Float,
        region: RegionBox? = null,
        cap: Int = RETURN_THRESHOLD + 1
    ): Int {
        if (cells.isEmpty() || zoom < DETAIL_ZOOM) return 0
        val visible = viewportCells(cells, region)
        var count = 0
        for ((_, levelCells) in visible.groupBy { it.z }) {
            count += FogRects.merge(levelCells.toSet()).size
            if (count >= cap) return cap
        }
        return count
    }

    /**
     * Чистое решение гистерезиса (smooth-fog-zoom): уходить в пятна при
     * переборе ([preciseCount] > [MAX_HOLES]), держать пятна пока
     * [preciseCount] > [RETURN_THRESHOLD], иначе показывать точное.
     * Колебание 790/810 вокруг лимита режим каждый кадр не переключает.
     */
    fun resolvePresenceStuck(preciseCount: Int, stuck: Boolean): Boolean {
        if (preciseCount > MAX_HOLES) return true
        if (stuck && preciseCount > RETURN_THRESHOLD) return true
        return false
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
        return HolePx(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f, h.coarse)
    }
}
