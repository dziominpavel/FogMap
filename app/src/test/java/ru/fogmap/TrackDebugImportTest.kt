package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.TrackDebugExport
import ru.fogmap.data.TrackDebugImport
import ru.fogmap.data.db.RawFixEntity

class TrackDebugImportTest {
    private fun raw(day: String, time: Long, lat: Double = 55.0, acc: Float = 10f) =
        RawFixEntity(
            day = day, time = time, lat = lat, lon = 37.0, acc = acc,
            speed = 5f, isMock = false, filter = "ok",
            state = "MOVING", trust = 70, openFog = 1
        )

    @Test
    fun `round trip экспорт-парс сохраняет дни и точки`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-18", "2026-09-19",
                mapOf(
                    "2026-09-18" to listOf(raw("2026-09-18", 1L), raw("2026-09-18", 2L)),
                    "2026-09-19" to emptyList()
                ),
                emptyList(), emptyMap(), "test"
            )
        )
        val parsed = TrackDebugImport.parseZip(zip)
        assertEquals(1, parsed.metaFormat)
        assertTrue(parsed.days.containsKey("2026-09-18"))
        assertTrue(parsed.days.containsKey("2026-09-19"))
        assertEquals(2, parsed.days["2026-09-18"]!!.size)
        assertEquals(0, parsed.days["2026-09-19"]!!.size)
        assertEquals(55.0, parsed.days["2026-09-18"]!![0].lat, 1e-9)
    }

    @Test
    fun `старая версия meta не блокирует импорт`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-19", "2026-09-19",
                mapOf("2026-09-19" to listOf(raw("2026-09-19", 1L))),
                emptyList(), emptyMap(), "test"
            )
        )
        // Имитация старого файла: format 0 и лишнее поле.
        val parsedOld = TrackDebugImport.parseZip(zip)
        assertEquals(1, parsedOld.metaFormat)
        val handMeta = "{\"format\":0,\"future\":\"x\"}".toByteArray(Charsets.UTF_8)
        assertEquals(0, TrackDebugImport.parseFormat(handMeta.toString(Charsets.UTF_8)))
        // Парсер битой строки не падает, а пропускает ее.
        val row = TrackDebugImport.parseRawLine("2026-09-19", "not-json")
        assertEquals("2026-09-19", row.day)
    }

    @Test
    fun `перепрожка дропает статику и считает отбросы`() {
        // 6 точек в кластере 20 м + дрейф acc 60: все в STAND/дроп, точек движения 0.
        val rows = (0 until 6).map { i ->
            raw("2026-09-19", i * 8_000L, lat = 55.0 + i * 0.00001)
        } + raw("2026-09-19", 48_000L, acc = 60f)
        val (accepted, rej) = TrackDebugImport.reprocessDay(rows)
        assertEquals(0, accepted.size)
        assertTrue((rej["accuracy"] ?: 0) >= 1)
    }

    @Test
    fun `перепрожка честного движения дает точки`() {
        // Ровная серия с шагом ~111 м за 8 сек выходит из STAND в MOVING.
        val rows = (0 until 6).map { i ->
            raw("2026-09-19", i * 8_000L, lat = 55.0 + i * 0.001)
        }
        val (accepted, _) = TrackDebugImport.reprocessDay(rows)
        assertTrue("ожидались точки движения, получено ${accepted.size}", accepted.isNotEmpty())
    }

    @Test
    fun `пустой meta дает null версии но парсится`() {
        assertNull(TrackDebugImport.parseFormat("{}"))
    }

    @Test
    fun `чанки rebuild режутся по времени и не впитывают тишину`() {
        // fix-import-metrics 2.1: 20 точек по 8 с, дыра 3 часа, ещё 5 точек.
        val pts = (0 until 20).map { pt(it * 8_000L) } +
            (0 until 5).map { pt(20 * 8_000L + 3 * 3_600_000L + it * 8_000L) }
        val chunks = TrackDebugImport.chunkAccepted(pts)
        assertTrue("дыра должна разрезать чанк, кусков ${chunks.size}", chunks.size >= 2)
        for (c in chunks) {
            assertTrue("размер куска ${c.size}", c.size <= TrackDebugImport.REBUILD_CHUNK)
            val span = c.last().time - c.first().time
            assertTrue("спан куска $span мс", span <= TrackDebugImport.REBUILD_SPAN_MS)
        }
        val totalSpan = chunks.sumOf { it.last().time - it.first().time }
        assertTrue(
            "суммарный спан $totalSpan мс не должен включать 3 часа",
            totalSpan < 3_600_000L
        )
    }

    @Test
    fun `counters из выгрузки парсятся tolerant-парсером`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-21", "2026-09-21",
                mapOf("2026-09-21" to listOf(raw("2026-09-21", 1L))),
                emptyList(),
                mapOf("eco_fix_day_2026-09-21" to 1788L, "eco_prefix_b200_n_day_2026-09-21" to 3L),
                "test"
            )
        )
        val parsed = TrackDebugImport.parseZip(zip)
        assertEquals(1788L, parsed.counters["eco_fix_day_2026-09-21"])
        assertEquals(3L, parsed.counters["eco_prefix_b200_n_day_2026-09-21"])
        // Старый/битый формат не роняет: мусорные пары пропускаются.
        val broken = TrackDebugImport.parseCounters("{\"eco_fix_all\":5,broken,\"x\":null}")
        assertEquals(mapOf("eco_fix_all" to 5L), broken)
    }

    @Test
    fun `восстанавливаются только эко-ключи снимка`() {
        // fix-import-metrics 3.2: геометрия пересчитывается, эко переносится.
        val snapshot = mapOf(
            "eco_fix_day_2026-09-21" to 10L,
            "eco_gap_cm_day_2026-09-21" to 300_000L,
            "distance_cm_day_2026-09-21" to 999L,
            "rejected_jump_all" to 5L,
            "area_cells_all" to 42L
        )
        val restored = FogRepository.ecoCountersFromSnapshot(snapshot)
        assertEquals(
            mapOf("eco_fix_day_2026-09-21" to 10L, "eco_gap_cm_day_2026-09-21" to 300_000L),
            restored
        )
        assertTrue(FogRepository.ecoCountersFromSnapshot(emptyMap()).isEmpty())
    }

    private fun pt(time: Long) = ru.fogmap.data.RawPoint(
        time = time, lat = 55.0, lon = 37.0, acc = 10f, speed = 5f
    )
}
