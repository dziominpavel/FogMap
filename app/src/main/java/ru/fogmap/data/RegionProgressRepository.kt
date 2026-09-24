package ru.fogmap.data

import ru.fogmap.data.db.AppDatabase
import ru.fogmap.fog.FogGrid
import ru.fogmap.region.Region
import ru.fogmap.region.RegionGeometry
import ru.fogmap.region.RegionType
import ru.fogmap.region.Regions

/** Прогресс региона: открытая площадь и производный процент. */
data class RegionProgress(
    val region: Region,
    val regionCells: Long,
    val openAreaKm2: Double,
    val percent: Double
)

/**
 * Чтение materialized counters `region_<id>_cells` (add-region-progress).
 * Процент — производная: (cells × area_per_cell) / totalAreaKm2 × 100.
 */
class RegionProgressRepository(private val db: AppDatabase) {

    suspend fun progress(): List<RegionProgress> {
        val counters = db.counterDao()
        return Regions.ALL.map { region ->
            val cells = counters.get(RegionGeometry.counterKey(region.id)) ?: 0L
            val open = FogGrid.areaKm2(cells)
            val percent = if (region.totalAreaKm2 > 0.0) {
                (open / region.totalAreaKm2) * 100.0
            } else {
                0.0
            }
            RegionProgress(region, cells, open, percent)
        }
    }

    /** Группировка для UI: Города / Области / Республика, сортировка по %. */
    fun grouped(items: List<RegionProgress>): Map<RegionType, List<RegionProgress>> =
        listOf(RegionType.CITY, RegionType.OBLAST, RegionType.REPUBLIC)
            .associateWith { type ->
                items.filter { it.region.type == type }.sortedByDescending { it.percent }
            }
            .filterValues { it.isNotEmpty() }
}
