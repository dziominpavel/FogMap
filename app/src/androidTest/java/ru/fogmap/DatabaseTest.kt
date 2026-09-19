package ru.fogmap

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.fogmap.data.FogRepository
import ru.fogmap.data.RawPoint
import ru.fogmap.data.TrackDebugExport
import ru.fogmap.data.TrackDebugImport
import ru.fogmap.data.TrackRepository
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.RawFixEntity
import ru.fogmap.tracking.PendingGate

/**
 * Инструментальный тест БД (задача 2.2/2.3):
 * инсерты, индексы, атомарность батча и формула площади.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var fog: FogRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fog = FogRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertsAndIndexes() = runTest {
        val trackId = db.trackDao().insertTrack(
            ru.fogmap.data.db.TrackEntity(
                startedAt = 1, finishedAt = 2, distanceM = 0.0, pointsCount = 0, name = "t"
            )
        )
        db.trackDao().insertPoints(
            listOf(
                ru.fogmap.data.db.TrackPointEntity(
                    trackId = trackId, time = 1, lat = 55.0, lon = 37.0, acc = 5f, speed = 1f
                )
            )
        )
        assertEquals(1, db.trackDao().pointsOf(trackId).size)
    }
    @Test
    fun batchOpensCellsAndCountsArea() = runTest {
        val trackId = db.trackDao().insertTrack(
            ru.fogmap.data.db.TrackEntity(
                startedAt = 1, finishedAt = 2, distanceM = 0.0, pointsCount = 0, name = "t"
            )
        )
        // Ворота C (PendingGate.LAG=2): первая точка открывается только после
        // подтверждения successors — одиночка никогда не откроет ничего,
        // поэтому батч из трех идущих точек (шаг ~111 м на север).
        val res = fog.appendPoints(
            trackId,
            listOf(
                RawPoint(time = 1, lat = 55.7558, lon = 37.6173, acc = 5f, speed = 1f),
                RawPoint(time = 9000, lat = 55.7568, lon = 37.6173, acc = 5f, speed = 1f),
                RawPoint(time = 17000, lat = 55.7578, lon = 37.6173, acc = 5f, speed = 1f)
            )
        )
        assertTrue(res.newCells > 0)
        assertEquals(res.newCells.toLong(), db.fogDao().cellCount())
        // Формула площади: ячейки × 0,01 км² — проверяем через счетчик.
        val cells = db.counterDao().get("area_cells_all") ?: 0
        assertEquals(res.newCells.toLong(), cells)
    }

    /**
     * День-атом (day-track-history 1.1/1.2): повторное открытие той же даты
     * возвращает ту же строку и не инкрементирует счетчики; новая дата —
     * новая строка; пустой день — нулевые метрики.
     */
    @Test
    fun openDayChunkIsIdempotentWithinDate() = runTest {
        val repo = TrackRepository(db)
        val date = java.time.LocalDate.of(2026, 9, 18)
        val first = repo.openDayChunk(date)
        val second = repo.openDayChunk(date)
        assertEquals(first, second)
        assertEquals(1, db.trackDao().allTracks().size)
        assertEquals(1L, db.counterDao().get("tracks_all"))
        assertEquals(1L, db.counterDao().get("tracks_day_$date"))
    }

    @Test
    fun openDayChunkCutsAtMidnight() = runTest {
        val repo = TrackRepository(db)
        val dayA = java.time.LocalDate.of(2026, 9, 18)
        val dayB = dayA.plusDays(1)
        val idA = repo.openDayChunk(dayA)
        val idB = repo.openDayChunk(dayB)
        assertTrue(idA != idB)
        assertEquals(2, db.trackDao().allTracks().size)
        val empty = db.trackDao().trackById(idB)!!
        assertEquals(0, empty.pointsCount)
        assertEquals(0.0, empty.distanceM, 1e-9)
    }

    /**
     * Rebuild большого дня (track-fix 19.09): 40 точек линией обязаны открыть
     * туман вдоль всей линии. Одним батчем ворота C видели только хвост
     * TAIL_LIMIT=16 — остальное висело в ожидании вечно (вайп прогресса).
     * Границы дня — из данных (иначе 0 мин).
     */
    @Test
    fun rebuildBigDayOpensFogBeyondTailLimit() = runTest {
        val day = "2026-09-19"
        val rows = (0 until 40).map { i ->
            RawFixEntity(
                day = day, time = i * 8000L, lat = 55.9 + i * 0.001, lon = 27.0,
                acc = 5f, speed = 14f, isMock = false, filter = "ok",
                state = null, trust = null, openFog = null, rejectReason = null,
                implied = null, cap = null, teleport = null
            )
        }
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                "2026-09-19", "2026-09-19", mapOf(day to rows),
                emptyList(), emptyMap(), "test"
            )
        )
        TrackDebugImport.importAndRebuild(db, zip)
        val tracks = db.trackDao().allTracks()
        assertEquals(1, tracks.size)
        val t = tracks.single()
        val pts = db.trackDao().pointsOf(t.id)
        // Первая точка — STAND холодного старта, остальные приняты.
        assertEquals(pts.size, t.pointsCount)
        assertTrue("точек ${pts.size}", pts.size >= 38)
        // Хвост ожидания — не больше лага ворот, остальное подтверждено.
        val tail = db.trackDao().unopenedTail(t.id, 1000)
        assertTrue("хвост ${tail.size}", tail.size <= PendingGate.LAG)
        // Туман вдоль всей линии (~4.3 км), а не у последних 16 точек.
        assertTrue(db.fogDao().cellCount() > 500)
        // Границы дня из данных.
        assertEquals(pts.first().time, t.startedAt)
        assertEquals(pts.last().time, t.finishedAt)
    }
}
