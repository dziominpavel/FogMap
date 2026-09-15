package ru.fogmap.map

import ru.fogmap.fog.FogGrid

/**
 * Чистая геометрия слоя тумана (задача 4.1): склейка соседних клеток
 * в прямоугольники + LRU-кэш склеенных геометрий. Без зависимости от MapKit —
 * покрывается unit-тестами, тонкий адаптер — в [FogLayer].
 */
object FogRects {
    data class Rect(val x0: Int, val x1: Int, val y0: Int, val y1: Int)

    /**
     * Склейка множества [cells] в прямоугольники жадным проходом по строкам.
     * Вход ограничен viewport (рендер только видимой области, spec map-render).
     */
    fun merge(cells: Set<FogGrid.Cell>): List<Rect> {
        if (cells.isEmpty()) return emptyList()
        val byRow = cells.groupBy { it.y }.mapValues { (_, v) -> v.map { it.x }.sorted() }
        val rects = mutableListOf<Rect>()
        val open = mutableMapOf<Pair<Int, Int>, Rect>() // (x0,x1) -> текущий прямоугольник
        for (y in byRow.keys.sorted()) {
            val runs = runs(byRow.getValue(y))
            val next = mutableMapOf<Pair<Int, Int>, Rect>()
            for ((x0, x1) in runs) {
                val key = x0 to x1
                val prev = open.remove(key)
                if (prev != null) next[key] = prev.copy(y1 = y)
                else next[key] = Rect(x0, x1, y, y)
            }
            rects += open.values
            open.clear()
            open.putAll(next)
        }
        rects += open.values
        return rects
    }

    private fun runs(xs: List<Int>): List<Pair<Int, Int>> {
        if (xs.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        var s = xs[0]; var p = xs[0]
        for (x in xs.drop(1)) {
            if (x == p + 1) p = x
            else { out.add(s to p); s = x; p = x }
        }
        out.add(s to p)
        return out
    }
}

/** LRU-кэш склеенных геометрий viewport (до 32 записей). */
class RectCache(private val maxSize: Int = 32) {
    private val map = object : LinkedHashMap<String, List<FogRects.Rect>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<FogRects.Rect>>?) =
            size > maxSize
    }

    @Synchronized
    fun getOrPut(key: String, build: () -> List<FogRects.Rect>): List<FogRects.Rect> =
        map.getOrPut(key, build)
}
