package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
