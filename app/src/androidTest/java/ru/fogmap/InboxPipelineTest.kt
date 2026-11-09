package ru.fogmap

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.fogmap.data.DayAuditRepository
import ru.fogmap.data.TrackRepository
import ru.fogmap.data.db.AppDatabase
import ru.fogmap.data.db.DayEntity
import ru.fogmap.data.db.FixInboxEntity
import ru.fogmap.data.db.TrackPointEntity
import ru.fogmap.tracking.FixEnvelope
import java.time.LocalDate
import java.time.ZoneId

/**
 * fix-journal 1.2/3.3 и day-ledger 1.1/2.3 на реальной Room-БД.
 *
 * Проверяем то, что нельзя проверить чистыми функциями: дедупликацию по
 * уникальному ключу, порядок выдачи журнала, идемпотентность реестра дней
 * и ремонт расхождений аудитом.
 */
@RunWith(AndroidJUnit4::class)
class InboxPipelineTest {

    private lateinit var db: AppDatabase
    private val zone: ZoneId = ZoneId.of("Europe/Minsk")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun envelope(time: Long, lat: Double = 53.9443, lon: Double = 27.6963): FixInboxEntity =
        FixEnvelope.of(time, lat, lon, 5f, 2f, isMock = false, zone = zone).toEntity()

    private fun at(day: Int, h: Int, m: Int): Long =
        java.time.ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun inboxDeduplicatesByKeyAndKeepsOrder() = runTest {
        val t0 = at(8, 13, 0)
        db.fixInboxDao().insertIgnore(envelope(t0 + 2_000))
        db.fixInboxDao().insertIgnore(envelope(t0))
        // Повторная доставка того же фикса не создаёт строку.
        db.fixInboxDao().insertIgnore(envelope(t0))
        val batch = db.fixInboxDao().pendingBatch(10)
        assertEquals(2, batch.size)
        assertTrue("порядок по метке фикса", batch[0].fixTime < batch[1].fixTime)
    }

    @Test
    fun markDoneRemovesFromPending() = runTest {
        val t0 = at(8, 13, 0)
        db.fixInboxDao().insertIgnore(envelope(t0))
        val row = db.fixInboxDao().pendingBatch(10).single()
        db.fixInboxDao().markDone(listOf(row.id))
        assertEquals(0L, db.fixInboxDao().pendingCount())
        assertEquals(1L, db.fixInboxDao().countByState(FixInboxEntity.STATE_DONE))
    }

    @Test
    fun openDayIsIdempotentAndWritesRegistry() = runTest {
        val repo = TrackRepository(db)
        val day = LocalDate.of(2026, 11, 8)
        val first = repo.openDay(day)
        val second = repo.openDay(day)
        assertEquals("повторное открытие не создаёт вторую строку", first, second)
        val entry = db.dayDao().byDay(day.toString())
        assertNotNull(entry)
        assertEquals(first, entry!!.trackId)
        assertEquals(1, db.dayDao().all().size)
    }

    @Test
    fun pendingBatchPaginatesWithoutGapsOrOverlaps() = runTest {
        val t0 = at(8, 10, 0)
        repeat(450) { i ->
            db.fixInboxDao().insertIgnore(envelope(t0 + i * 1000L))
        }
        val first = db.fixInboxDao().pendingBatch(200)
        db.fixInboxDao().markDone(first.map { it.id })
        val second = db.fixInboxDao().pendingBatch(200)
        db.fixInboxDao().markDone(second.map { it.id })
        val third = db.fixInboxDao().pendingBatch(200)
        db.fixInboxDao().markDone(third.map { it.id })
        assertEquals(200, first.size)
        assertEquals(200, second.size)
        assertEquals(50, third.size)
        val ids = (first + second + third).map { it.id }
        assertEquals("страницы не пересекаются", 450, ids.toSet().size)
        val times = (first + second + third).map { it.fixTime }
        assertEquals("порядок по метке фикса", times.sorted(), times)
        assertEquals(0L, db.fixInboxDao().pendingCount())
    }

    @Test
    fun appendPointsMarksInboxRowsInSameTransaction() = runTest {
        val t0 = at(8, 13, 0)
        db.fixInboxDao().insertIgnore(envelope(t0))
        db.fixInboxDao().insertIgnore(envelope(t0 + 1000))
        val ids = db.fixInboxDao().pendingBatch(10).map { it.id }

        val repo = TrackRepository(db)
        val day = LocalDate.of(2026, 11, 8)
        val trackId = repo.openDay(day)
        val fog = ru.fogmap.data.FogRepository(db)
        val res = fog.appendPoints(
            trackId,
            listOf(
                ru.fogmap.data.RawPoint(time = t0, lat = 53.9443, lon = 27.6963, acc = 5f, speed = 2f),
                ru.fogmap.data.RawPoint(time = t0 + 1000, lat = 53.9444, lon = 27.6963, acc = 5f, speed = 2f)
            ),
            date = day,
            inboxIds = ids
        )
        assertEquals(trackId, res.trackId)
        assertEquals("строки журнала помечены обработанными", 0L, db.fixInboxDao().pendingCount())
        assertEquals(2L, db.fixInboxDao().countByState(FixInboxEntity.STATE_DONE))
    }

    @Test
    fun migrationBackfillFillsRegistryFromTracks() = runTest {
        val d1 = LocalDate.of(2026, 11, 8)
        val t1 = d1.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val t2 = d1.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val early = db.trackDao().insertTrack(
            ru.fogmap.data.db.TrackEntity(
                startedAt = t1, finishedAt = t1, distanceM = 0.0, pointsCount = 0, name = "a"
            )
        )
        val late = db.trackDao().insertTrack(
            ru.fogmap.data.db.TrackEntity(
                startedAt = t2, finishedAt = t2, distanceM = 0.0, pointsCount = 0, name = "b"
            )
        )
        db.dayDao().clearAll()
        // Ровно тот же SQL, что выполняет миграция v7 -> v8.
        db.openHelper.writableDatabase.execSQL(AppDatabase.DAYS_BACKFILL_SQL)
        val row = db.dayDao().byDay(d1.toString())
        assertNotNull("день должен появиться в реестре", row)
        assertEquals("на дату — новейшая строка", late, row!!.trackId)
        assertTrue(early != row.trackId)
        assertEquals(1, db.dayDao().all().size)
    }

    @Test
    fun auditMovesForeignPointsAndFixesCount() = runTest {
        val repo = TrackRepository(db)
        val day = LocalDate.of(2026, 11, 8)
        val prev = LocalDate.of(2026, 11, 7)
        val todayId = repo.openDay(day)
        // Строка дня 08.11 с двумя точками вечера 07.11 — точный полевой случай.
        db.trackDao().insertPoints(
            listOf(
                TrackPointEntity(
                    trackId = todayId, time = at(7, 17, 0), lat = 53.9443, lon = 27.6963,
                    acc = 5f, speed = 2f, trust = 100, state = "MOVING"
                ),
                TrackPointEntity(
                    trackId = todayId, time = at(7, 17, 1), lat = 53.9444, lon = 27.6963,
                    acc = 5f, speed = 2f, trust = 100, state = "MOVING"
                ),
                TrackPointEntity(
                    trackId = todayId, time = at(8, 1, 39), lat = 53.9445, lon = 27.6963,
                    acc = 5f, speed = 2f, trust = 100, state = "MOVING"
                )
            )
        )
        db.trackDao().auditStats(todayId, 0.0, 99, at(8, 1, 39))

        val res = DayAuditRepository(db).audit(day, zone)
        assertTrue("аудит должен найти день", res.found)
        assertEquals("две чужие точки уезжают в свои сутки", 2, res.movedForeign)
        assertEquals(1, db.trackDao().pointsOf(todayId).size)
        val prevEntry = db.dayDao().byDay(prev.toString())
        assertNotNull(prevEntry)
        assertEquals(2, db.trackDao().pointsOf(prevEntry!!.trackId).size)
        // Счётчик точек пересчитан из фактических строк.
        assertEquals(1, db.trackDao().trackById(todayId)!!.pointsCount)
    }
}
