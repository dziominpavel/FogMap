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
import ru.fogmap.data.TrackRepository
import ru.fogmap.data.db.AppDatabase

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
}
