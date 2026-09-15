package ru.fogmap.map

import com.yandex.mapkit.geometry.LinearRing
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polygon
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fogmap.data.FogRepository
import ru.fogmap.fog.FogGrid
import kotlin.math.floor

/**
 * Слой тумана полигонами MapKit (spec map-render, задача 4.1):
 * - только ячейки в текущем viewport (viewport-culling);
 * - склейка соседних в прямоугольники ([FogRects]) + LRU-кэш;
 * - на мелких зумах — агрегированный показ (сплошная вуаль);
 * - полигоны — объекты карты, логотип/копирайты SDK остаются поверх (задача 4.2).
 */
class FogLayer(
    private val mapView: MapView,
    private val fogRepository: FogRepository,
    private val scope: CoroutineScope
) {
    private val cache = RectCache()
    private var job: Job? = null

    private val cameraListener = CameraListener { _, _, _, _ -> scheduleRebuild() }
    private val cameraListenerRef = java.lang.ref.WeakReference(cameraListener)

    fun start() {
        mapView.mapWindow.map.addCameraListener(cameraListenerRef)
        scheduleRebuild()
    }

    fun stop() {
        mapView.mapWindow.map.removeCameraListener(cameraListenerRef)
        job?.cancel()
    }

    private fun scheduleRebuild() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            delay(250) // дебаунс пан/зум
            rebuild()
        }
    }

    private suspend fun rebuild() {
        val map: Map = mapView.mapWindow.map
        val pos: CameraPosition = map.cameraPosition
        // Мелкие зумы: агрегация — сплошной туман без дырок (лимит полигонов).
        if (pos.zoom < AGGREGATE_ZOOM) {
            scope.launch(Dispatchers.Main) { drawSolidFog(map) }
            return
        }
        val region = map.visibleRegion
        val topLat = maxOf(region.topLeft.latitude, region.topRight.latitude)
        val bottomLat = minOf(region.bottomLeft.latitude, region.bottomRight.latitude)
        val leftLon = minOf(region.topLeft.longitude, region.bottomLeft.longitude)
        val rightLon = maxOf(region.topRight.longitude, region.bottomRight.longitude)

        val c1 = FogGrid.cellFor(topLat, leftLon)
        val c2 = FogGrid.cellFor(bottomLat, rightLon)
        val x0 = minOf(c1.x, c2.x); val x1 = maxOf(c1.x, c2.x)
        val y0 = minOf(c1.y, c2.y); val y1 = maxOf(c1.y, c2.y)
        // Лимит viewport: не больше 400×400 клеток за раз (защита от перегрузки).
        if ((x1 - x0) > MAX_SPAN || (y1 - y0) > MAX_SPAN) {
            scope.launch(Dispatchers.Main) { drawSolidFog(map) }
            return
        }
        val visited = fogRepository.cellsInViewport(x0, x1, y0, y1)
            .map { FogGrid.Cell(it.x, it.y) }.toSet()

        // Туман = дополнение: все клетки viewport минус открытые.
        val fogCells = HashSet<FogGrid.Cell>()
        for (x in x0..x1) for (y in y0..y1) {
            val c = FogGrid.Cell(x, y)
            if (c !in visited) fogCells.add(c)
        }
        val key = "$x0,$x1,$y0,$y1:${visited.hashCode()}"
        val rects = cache.getOrPut(key) { FogRects.merge(fogCells) }
        scope.launch(Dispatchers.Main) { drawRects(map, rects) }
    }

    private fun drawSolidFog(map: Map) {
        map.mapObjects.clear()
        val region = map.visibleRegion
        val outer = listOf(region.topLeft, region.topRight, region.bottomRight, region.bottomLeft)
        addFogPolygon(map, outer, emptyList())
    }

    private fun drawRects(map: Map, rects: List<FogRects.Rect>) {
        map.mapObjects.clear()
        for (r in rects) {
            val tl = cellTopLeft(r.x0, r.y0)
            val br = cellBottomRight(r.x1, r.y1)
            val outer = listOf(
                Point(tl.first, tl.second),
                Point(tl.first, br.second),
                Point(br.first, br.second),
                Point(br.first, tl.second)
            )
            addFogPolygon(map, outer, emptyList())
        }
    }

    private fun addFogPolygon(map: Map, outer: List<Point>, holes: List<List<Point>>) {
        val polygon = Polygon(
            LinearRing(outer),
            holes.map { LinearRing(it) }
        )
        val obj = map.mapObjects.addPolygon(polygon)
        obj.fillColor = FOG_FILL
        obj.strokeWidth = 0f
    }

    companion object {
        const val AGGREGATE_ZOOM = 11f
        const val MAX_SPAN = 400
        const val FOG_FILL = 0xB3141B2E.toInt() // полупрозрачный темный слой

        /** Геометрия ячейки -> углы (WebMercator, зум [FogGrid.GRID_ZOOM]). */
        fun cellTopLeft(x: Int, y: Int): Pair<Double, Double> {
            val n = 1 shl FogGrid.GRID_ZOOM
            val lon = x.toDouble() / n * 360.0 - 180.0
            val latRad = kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2.0 * y / n)))
            return Math.toDegrees(latRad) to lon
        }

        fun cellBottomRight(x1: Int, y1: Int): Pair<Double, Double> =
            cellTopLeft(x1 + 1, y1 + 1).let { (lat, lon) -> lat to lon }

        fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt()
    }
}
