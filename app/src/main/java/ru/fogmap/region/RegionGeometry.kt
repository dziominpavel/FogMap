package ru.fogmap.region

/**
 * Point-in-polygon для регионов (change add-region-progress).
 *
 * Сначала bbox pre-filter (дешёвая проверка), затем ray casting по каждому
 * внешнему кольцу региона: точка внутри, если попала хотя бы в одно кольцо.
 * Только outer-кольца: Минск считается внутри Минской области (spec).
 */
object RegionGeometry {

    /** bbox региона: minLon, minLat, maxLon, maxLat. */
    data class BBox(
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double
    )

    private val bboxCache = HashMap<String, BBox>()

    fun bboxOf(region: Region): BBox =
        bboxCache.getOrPut(region.id) {
            var minLon = Double.POSITIVE_INFINITY
            var minLat = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            for (ring in region.rings) {
                for ((lon, lat) in ring) {
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                }
            }
            BBox(minLon, minLat, maxLon, maxLat)
        }

    fun inBBox(region: Region, lat: Double, lon: Double): Boolean {
        val b = bboxOf(region)
        return lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat
    }

    /** Точка внутри региона: bbox, затем ray casting по внешним кольцам. */
    fun contains(region: Region, lat: Double, lon: Double): Boolean {
        if (!inBBox(region, lat, lon)) return false
        for (ring in region.rings) {
            if (ringIn(ring, lat, lon)) return true
        }
        return false
    }

    /**
     * Ray casting (even-odd): горизонтальный луч из точки вправо;
     * число пересечений нечётно => внутри. Кольцо — список (lon, lat).
     */
    fun ringIn(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            val intersect = (yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    /** Все регионы, содержащие точку (для counters: Минск + область + республика). */
    fun regionsAt(lat: Double, lon: Double): List<Region> =
        Regions.ALL.filter { contains(it, lat, lon) }

    /** Ключ counters региона: `region_<id>_cells`. */
    fun counterKey(regionId: String): String = "region_${regionId}_cells"

    /** Радиус Земли, м (средний). */
    private const val EARTH_R_M = 6371008.8

    /**
     * Кольца, не вложенные ни в другое кольцо региона, — связные куски
     * территории. Вложенные кольца (дырки/артефакты данных, например 6 колец
     * Минской области внутри Минска) исключаются и для отрисовки границ, и для
     * площади (change add-region-borders).
     */
    fun outerRings(rings: List<List<Pair<Double, Double>>>): List<List<Pair<Double, Double>>> =
        rings.filterIndexed { i, ring ->
            if (ring.isEmpty()) return@filterIndexed false
            val (probeLon, probeLat) = ring.first()
            !rings.indices.any { j ->
                j != i && rings[j].size >= 3 && ringIn(rings[j], probeLat, probeLon)
            }
        }

    /**
     * Площадь одного кольца, км²: шалеус в координатах (lon, sin lat) —
     * на сфере это точная замена плоскостного шалеуса; край кольца считается
     * замкнутым (последняя→первая точка), поэтому незамкнутые данные корректны.
     */
    fun ringAreaKm2(ring: List<Pair<Double, Double>>): Double {
        if (ring.size < 3) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val (lon1, lat1) = ring[i]
            val (lon2, lat2) = ring[(i + 1) % ring.size]
            val l1 = Math.toRadians(lon1)
            val l2 = Math.toRadians(lon2)
            sum += l1 * Math.sin(Math.toRadians(lat2)) - l2 * Math.sin(Math.toRadians(lat1))
        }
        return kotlin.math.abs(sum) * 0.5 * EARTH_R_M * EARTH_R_M / 1_000_000.0
    }

    /** Площадь региона, км² — сумма не вложенных колец (spec region-progress). */
    fun areaKm2(rings: List<List<Pair<Double, Double>>>): Double =
        outerRings(rings).sumOf { ringAreaKm2(it) }

    /**
     * Замкнутое кольцо для отрисовки: в данных часть колец не содержит
     * повторной начальной точки — линия должна замкнуться встык (spec region-borders).
     */
    fun closedRing(ring: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        if (ring.size >= 2 && ring.first() == ring.last()) ring else ring + ring.first()

    /** Центроид не вложенных колец (lon, lat), взвешенный по площади колец. */
    fun centroidOf(rings: List<List<Pair<Double, Double>>>): Pair<Double, Double> {
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (ring in outerRings(rings)) {
            if (ring.size < 3) continue
            var a = 0.0
            var cx = 0.0
            var cy = 0.0
            for (i in ring.indices) {
                val (x1, y1) = ring[i]
                val (x2, y2) = ring[(i + 1) % ring.size]
                val cross = x1 * y2 - x2 * y1
                a += cross
                cx += (x1 + x2) * cross
                cy += (y1 + y2) * cross
            }
            a /= 2.0
            if (kotlin.math.abs(a) < 1e-12) continue
            val w = kotlin.math.abs(a)
            sumW += w
            sumX += cx / (6.0 * a) * w
            sumY += cy / (6.0 * a) * w
        }
        if (sumW > 0.0) return sumX / sumW to sumY / sumW
        val flat = rings.flatten()
        return flat.map { it.first }.average() to flat.map { it.second }.average()
    }
}
