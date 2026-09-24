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
}
