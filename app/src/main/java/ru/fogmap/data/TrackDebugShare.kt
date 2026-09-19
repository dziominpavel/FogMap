package ru.fogmap.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ru.fogmap.BuildConfig
import ru.fogmap.data.db.TrackPointEntity
import java.io.File
import java.time.LocalDate

/**
 * Сборка и шаринг батч-экспорта (track-debug 2.2): читает БД, строит ZIP
 * через [TrackDebugExport] и отдает через системный chooser тем же
 * FileProvider что и дев-лог (cache/logs). Без варнингов по решению владельца.
 */
object TrackDebugShare {
    suspend fun buildExportFile(
        container: AppContainer,
        fromDay: String,
        toDay: String
    ): File {
        val db = container.db
        val rows = db.rawFixDao().range(fromDay, toDay)
        val rawByDay = rows.groupBy { it.day }
        // Принятые точки по дням (track-debug 4.3): день трека — по дате
        // старта tracks.startedAt (день-атом), точки — pointsOf каждого трека.
        val trackByDay = HashMap<String, List<TrackPointEntity>>()
        for (day in TrackDebugExport.daysInRange(fromDay, toDay)) {
            val date = runCatching { LocalDate.parse(day) }.getOrNull()
            if (date == null) {
                trackByDay[day] = emptyList()
                continue
            }
            val (fromMs, toMs) = TrackRepository.dayBoundsMs(date)
            val ids = db.trackDao().inRange(fromMs, toMs).map { it.id }
            val pts = ArrayList<TrackPointEntity>()
            for (id in ids) pts.addAll(db.trackDao().pointsOf(id))
            pts.sortBy { it.time }
            trackByDay[day] = pts
        }
        val cells = db.fogDao().allCells()
        val counters = db.counterDao().all().associate { it.key to it.value }
        val zip = TrackDebugExport.buildZip(
            TrackDebugExport.Input(
                fromDay = fromDay, toDay = toDay,
                rawByDay = rawByDay, cells = cells,
                counters = counters, appVersion = BuildConfig.VERSION_NAME,
                trackByDay = trackByDay
            )
        )
        val dir = File(container.context.cacheDir, "logs")
        dir.mkdirs()
        val file = File(dir, "track-debug_${fromDay}_${toDay}.zip")
        file.writeBytes(zip)
        return file
    }

    fun shareZip(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.devlog", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Поделиться треком"))
    }
}
