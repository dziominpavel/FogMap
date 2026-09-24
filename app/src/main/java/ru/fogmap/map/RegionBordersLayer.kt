package ru.fogmap.map

import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.LineStyle
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.PolylineMapObject
import ru.fogmap.region.RegionType
import ru.fogmap.region.Regions

/**
 * Границы регионов на карте (change add-region-borders, spec region-borders).
 *
 * Полилинии добавляются в mapObjects MapView, то есть лежат ПОД Compose-вуалью
 * тумана (FogMaskOverlay рисуется поверх AndroidView): граница физически не
 * может оказаться поверх тумана и видна только в открытых дырках.
 *
 * Стиль по типам: республика — жирная сплошная, область — штриховая,
 * город — тонкая сплошная. Единая палитра: светлая линия + тёмный контур
 * (читается и на дневной, и на ночной карте). Цвета/толщины/dash — начальные,
 * подгоняются по скриншоту (design.md, Open Questions).
 *
 * Объекты создаются один раз при первом включении и дальше прячутся через
 * isVisible (без пересоздания). Сюда относится только главная карта —
 * экран истории дня свой слой не получает (spec region-borders).
 */
class RegionBordersLayer(private val map: Map) {

    private val regionLines = ArrayList<PolylineMapObject>()
    private val cityLines = ArrayList<PolylineMapObject>()
    private var attached = false

    /**
     * Применить состояние: [bordersOn] — переключатель из настроек;
     * [citiesOn] — показывать ли городские контуры (зум-гейт < z10).
     */
    fun show(bordersOn: Boolean, citiesOn: Boolean) {
        runCatching {
            if (bordersOn && !attached) attach()
            if (!attached) return@runCatching
            for (line in regionLines) line.isVisible = bordersOn
            for (line in cityLines) line.isVisible = bordersOn && citiesOn
        }
    }

    private fun attach() {
        for (region in Regions.ALL) {
            val isCity = region.type == RegionType.CITY
            val style = styleFor(region.type)
            for (ring in region.drawRings) {
                if (ring.size < 2) continue
                val line = map.mapObjects.addPolyline(
                    Polyline(ring.map { (lon, lat) -> Point(lat, lon) })
                )
                line.setStrokeColor(LINE_COLOR)
                line.style = style
                line.isVisible = false
                (if (isCity) cityLines else regionLines).add(line)
            }
        }
        attached = true
    }

    private fun styleFor(type: RegionType): LineStyle = when (type) {
        RegionType.REPUBLIC -> LineStyle().apply {
            strokeWidth = REPUBLIC_WIDTH
            outlineColor = OUTLINE_COLOR
            outlineWidth = OUTLINE_WIDTH
        }
        RegionType.OBLAST -> LineStyle().apply {
            strokeWidth = OBLAST_WIDTH
            outlineColor = OUTLINE_COLOR
            outlineWidth = OUTLINE_WIDTH
            dashLength = DASH_LENGTH
            gapLength = GAP_LENGTH
        }
        RegionType.CITY -> LineStyle().apply {
            strokeWidth = CITY_WIDTH
            outlineColor = OUTLINE_COLOR
            outlineWidth = CITY_OUTLINE_WIDTH
        }
    }

    companion object {
        /** Ниже этого зума городские контуры скрыты (spec region-borders); порог начальный. */
        const val CITY_MIN_ZOOM = 10f

        /** Светлая линия — читается и на дневной, и на ночной карте. */
        private const val LINE_COLOR = 0xFFFFF3E0.toInt()

        /** Тёмный контур под линией — отделение от фона на любой карте. */
        private const val OUTLINE_COLOR = 0x99000000.toInt()

        private const val REPUBLIC_WIDTH = 5f
        private const val OBLAST_WIDTH = 4f
        private const val CITY_WIDTH = 2.5f
        private const val OUTLINE_WIDTH = 1.5f
        private const val CITY_OUTLINE_WIDTH = 1f
        private const val DASH_LENGTH = 12f
        private const val GAP_LENGTH = 8f
    }
}
