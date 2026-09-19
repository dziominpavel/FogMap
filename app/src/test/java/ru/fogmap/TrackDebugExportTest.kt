package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.TrackDebugExport
import ru.fogmap.data.db.RawFixEntity
import ru.fogmap.data.db.TrackPointEntity
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class TrackDebugExportTest {
    private fun raw(day: String, time: Long) = RawFixEntity(
        day = day, time = time, lat = 55.0, lon = 37.0, acc = 10f,
        speed = 5f, isMock = false, filter = "ok",
        state = "MOVING", trust = 70, openFog = 1
    )

    @Test
    fun `недельный экспорт содержит 7 файлов дней включая пустые`() {
        val from = "2026-09-13"
        val to = "2026-09-19"
        val rawByDay = mapOf(
            "2026-09-13" to listOf(raw("2026-09-13", 1L), raw("2026-09-13", 2L)),
            "2026-09-15" to listOf(raw("2026-09-15", 3L))
        )
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(from, to, rawByDay, emptyList(), emptyMap(), "test")
        )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.getNextEntry() ?: break
                entries[e.name] = zin.readBytes().toString(Charsets.UTF_8)
                zin.closeEntry()
            }
        }
        assertTrue(entries.containsKey("meta.json"))
        assertTrue(entries.containsKey("fog.jsonl"))
        assertTrue(entries.containsKey("counters.json"))
        val days = TrackDebugExport.daysInRange(from, to)
        assertEquals(7, days.size)
        for (day in days) {
            assertTrue("нет days/$day.jsonl", entries.containsKey("days/$day.jsonl"))
        }
        // Пустые дни — пустые файлы, непустые — по строке на точку.
        assertEquals("", entries["days/2026-09-14.jsonl"])
        assertEquals(2, entries["days/2026-09-13.jsonl"]!!.lines().filter { it.isNotBlank() }.size)
        assertEquals(1, entries["days/2026-09-15.jsonl"]!!.lines().filter { it.isNotBlank() }.size)
        assertTrue(entries["meta.json"]!!.contains("\"format\":1"))
    }

    @Test
    fun `диапазон одного дня дает один файл`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input("2026-09-19", "2026-09-19", emptyMap(), emptyList(), emptyMap(), "test")
        )
        var dayFiles = 0
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.getNextEntry() ?: break
                if (e.name.startsWith("days/")) dayFiles++
                zin.closeEntry()
            }
        }
        assertEquals(1, dayFiles)
    }

    private fun point(time: Long) = TrackPointEntity(
        id = time, trackId = 7, time = time, lat = 53.9, lon = 27.6,
        acc = 12f, speed = 14f, trust = 10, state = "SUSPECT",
        rejectReason = "jump", fogOpened = 2
    )

    @Test
    fun `треки дня лежат в tracks и пустые дни пустые`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-19", "2026-09-20", emptyMap(), emptyList(), emptyMap(), "test",
                trackByDay = mapOf("2026-09-19" to listOf(point(1L), point(2L)))
            )
        )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.getNextEntry() ?: break
                entries[e.name] = zin.readBytes().toString(Charsets.UTF_8)
                zin.closeEntry()
            }
        }
        assertTrue(entries.containsKey("tracks/2026-09-19.jsonl"))
        assertTrue(entries.containsKey("tracks/2026-09-20.jsonl"))
        assertEquals("", entries["tracks/2026-09-20.jsonl"])
        val lines = entries["tracks/2026-09-19.jsonl"]!!.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"state\":\"SUSPECT\""))
        assertTrue(lines[0].contains("\"trust\":10"))
        assertTrue(lines[0].contains("\"reject\":\"jump\""))
        assertTrue(lines[0].contains("\"fogOpened\":2"))
        assertTrue(lines[0].contains("\"lat\":53.9"))
    }

    @Test
    fun `старый tolerant-парсер игнорирует tracks`() {
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-19", "2026-09-19", emptyMap(), emptyList(), emptyMap(), "test",
                trackByDay = mapOf("2026-09-19" to listOf(point(1L)))
            )
        )
        val parsed = ru.fogmap.data.TrackDebugImport.parseZip(zip)
        assertTrue(parsed.days.containsKey("2026-09-19"))
        assertTrue(parsed.days["2026-09-19"]!!.isEmpty())
    }
}
