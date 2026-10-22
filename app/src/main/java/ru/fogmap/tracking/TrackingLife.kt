package ru.fogmap.tracking

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import ru.fogmap.diag.DevLog
import java.io.File

/**
 * Канал жизни процесса (fix-track-gap-visibility 1.2–1.6).
 *
 * Задача — атрибутировать разрыв записи: процесс убит, процесс заморожен или
 * отстал цикл записи. Следы пишутся ДО и ПОСЛЕ разрыва, поэтому переживают его:
 *  - `LIFE/proc_start` — первое событие процесса, с `gap_ms` от последней
 *    известной метки прошлой сессии (метка лежит в filesDir и переживает
 *    убийство процесса; `cn` файла — [MARKER_FILE]);
 *  - `LIFE/heartbeat` — раз в [HEARTBEAT_INTERVAL_MS] из выделенного потока
 *    сервиса и из alarm-канала watchdog, то есть независимо от flush-цикла;
 *  - `LIFE/freeze_gap` — первый тик записи после молчания дольше [FREEZE_GAP_MS];
 *  - `LIFE/exit_reason` — причина прошлой смерти из `ApplicationExitInfo`
 *    (API 30+), на более старых API событие просто не пишется.
 *
 * Чистые функции (метка, `gap_ms`, имена причин, разбор вендорского описания,
 * payload-строители) вынесены так, чтобы unit-тесты шли на JVM без Android.
 */
object TrackingLife {

    const val TAG = DevLog.TAG_LIFE

    /** Запрошенный период heartbeat; в Doze система может доставить позже. */
    const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L

    /** Порог «цикл записи не тикал»: 10 мин (40 flush-интервалов). */
    const val FREEZE_GAP_MS = 10 * 60_000L

    /** Троттл записи метки на диск: разрыв на старте точен до минуты. */
    const val MARKER_THROTTLE_MS = 60_000L

    const val MARKER_FILE = "life-state.txt"

    const val KEY_PREV_SESSION = "prev_session"
    const val KEY_PREV_BOOT_MONO_MS = "prev_boot_mono_ms"
    const val KEY_GAP_MS = "gap_ms"
    const val KEY_SOURCE = "source"
    const val KEY_VIA = "via"
    const val KEY_SINCE_LAST_HEARTBEAT_MS = "since_last_heartbeat_ms"
    const val KEY_REASON = "reason"
    const val KEY_REASON_CODE = "reason_code"
    const val KEY_AGE_MS = "age_ms"
    const val KEY_DEATH_WALL_MS = "death_wall_ms"
    const val KEY_VENDOR = "vendor"
    const val KEY_DESC = "desc"

    /** Обрезка вендорского описания: строка лога не должна пухнуть. */
    const val DESC_MAX = 120

    /** Метка последнего известного события сессии (переживает смерть процесса). */
    data class Marker(val wallMs: Long, val bootMonoMs: Long, val session: String)

    @Volatile
    private var appContext: Context? = null

    /** Моно-время прошлого тика записи (0 — сервис еще не стартовал). */
    @Volatile
    private var lastFlushTickMono: Long = 0L

    @Volatile
    private var lastAliveWall: Long = 0L

    @Volatile
    private var lastPersistWall: Long = 0L

    @Volatile
    private var lastHeartbeatWall: Long = 0L

    // --- чистая часть (unit-тесты на JVM) ---

    fun encodeMarker(m: Marker): String = "${m.wallMs}|${m.bootMonoMs}|${m.session}"

    fun parseMarker(text: String?): Marker? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split('|')
        if (parts.size != 3) return null
        return Marker(
            wallMs = parts[0].toLongOrNull() ?: return null,
            bootMonoMs = parts[1].toLongOrNull() ?: return null,
            session = parts[2]
        )
    }

    /** Разрыв между последней известной меткой и текущим моментом, мс. */
    fun gapMs(prevWallMs: Long, nowWallMs: Long): Long =
        (nowWallMs - prevWallMs).coerceAtLeast(0L)

    fun isFreezeGap(gapMs: Long): Boolean = gapMs >= FREEZE_GAP_MS

    fun procStartPayload(prev: Marker?, gap: Long?): Map<String, Any?> = mapOf(
        KEY_PREV_SESSION to prev?.session,
        KEY_PREV_BOOT_MONO_MS to prev?.bootMonoMs,
        KEY_GAP_MS to gap
    )

    fun heartbeatPayload(via: String, sinceLastMs: Long): Map<String, Any?> = mapOf(
        KEY_VIA to via,
        KEY_SINCE_LAST_HEARTBEAT_MS to sinceLastMs
    )

    fun freezePayload(gapMs: Long, source: String = "flush"): Map<String, Any?> = mapOf(
        KEY_GAP_MS to gapMs,
        KEY_SOURCE to source
    )

    /** Имя причины смерти процесса; неизвестный код виден числом. */
    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resources"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_OTHER -> "other"
        // Константы новее compileSdk-словаря API 30-х: значения стабильны в AOSP,
        // а линкуются они только на API 31+, поэтому здесь числами.
        12 -> "freezer" // REASON_FREEZER, API 31
        13 -> "package_state_change" // REASON_PACKAGE_STATE_CHANGE, API 31
        14 -> "package_updated" // REASON_PACKAGE_UPDATED, API 31
        15 -> "package_disabled" // REASON_PACKAGE_DISABLED, API 35
        else -> "code_$reason"
    }

    /**
     * Вендорский тег из описания `ApplicationExitInfo`
     * (`... from pid 2819 iAwareF[Thermal] -1ms` -> `iAwareF[Thermal]`).
     * Именно он отличает OEM-убийство от обычного нехвата памяти.
     */
    fun vendorTag(description: String?): String? {
        if (description.isNullOrBlank()) return null
        return Regex("([A-Za-z][A-Za-z0-9_]*)\\[([^]]+)]").find(description)?.value
    }

    // --- метка на диске ---

    fun markerFile(context: Context): File = File(context.filesDir, MARKER_FILE)

    fun readMarker(file: File): Marker? = runCatching {
        if (!file.isFile) null else parseMarker(file.readText())
    }.getOrNull()

    fun writeMarker(file: File, marker: Marker) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encodeMarker(marker))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    // --- события ---

    /**
     * Первое событие процесса (1.3): вызывается из `FogMapApp.onCreate` сразу
     * после старта файлового лога и ДО любых других записей.
     */
    fun onProcessStart(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val now = DevLog.wallClock()
        val file = markerFile(ctx)
        val prev = readMarker(file)
        val gap = prev?.let { gapMs(it.wallMs, now) }
        lastAliveWall = now
        lastPersistWall = now
        lastFlushTickMono = 0L
        DevLog.i(TAG, "proc_start", procStartPayload(prev, gap))
        val exit = readLastExit(ctx)
        if (exit != null) DevLog.i(TAG, "exit_reason", exit)
        writeMarker(file, Marker(now, DevLog.bootMonoMs(), DevLog.session))
    }

    /** Отметка «процесс жив»; на диск — не чаще [MARKER_THROTTLE_MS]. */
    fun noteAlive() {
        val now = DevLog.wallClock()
        lastAliveWall = now
        val ctx = appContext ?: return
        if (now - lastPersistWall < MARKER_THROTTLE_MS) return
        lastPersistWall = now
        writeMarker(markerFile(ctx), Marker(now, DevLog.bootMonoMs(), DevLog.session))
    }

    /** Принудительная запись метки (остановка сервиса, конец сессии). */
    fun flushMarker() {
        val ctx = appContext ?: return
        val now = DevLog.wallClock()
        lastPersistWall = now
        writeMarker(markerFile(ctx), Marker(now, DevLog.bootMonoMs(), DevLog.session))
    }

    /**
     * Один удар heartbeat: событие + обновление метки. Пишется и потоком
     * сервиса, и alarm-каналом — оба не зависят от flush-цикла.
     */
    fun heartbeat(via: String) {
        val now = DevLog.wallClock()
        val since = if (lastHeartbeatWall == 0L) 0L else gapMs(lastHeartbeatWall, now)
        lastHeartbeatWall = now
        DevLog.i(TAG, "heartbeat", heartbeatPayload(via, since))
        noteAlive()
    }

    /** Старт цикла записи: база для первого `freeze_gap`. */
    fun onServiceStart() {
        lastFlushTickMono = DevLog.bootMonoMs()
        noteAlive()
    }

    /**
     * Тик цикла записи. Возвращает фактический моно-разрыв с прошлого тика;
     * разрыв сверх [FREEZE_GAP_MS] пишется как `LIFE/freeze_gap` — по нему
     * видно, что процесс не падал, а был заморожен.
     */
    fun onFlushTick(): Long {
        val nowMono = DevLog.bootMonoMs()
        val prev = lastFlushTickMono
        lastFlushTickMono = nowMono
        val gap = if (prev == 0L) 0L else gapMs(prev, nowMono)
        if (prev != 0L && isFreezeGap(gap)) {
            DevLog.w(TAG, "freeze_gap", freezePayload(gap))
        }
        noteAlive()
        return gap
    }

    /** Возраст последней отметки жизни, мс (0 — отметок не было). */
    fun lastAliveWallMs(): Long = lastAliveWall

    fun lastHeartbeatWallMs(): Long = lastHeartbeatWall

    /**
     * Проверка со стороны heartbeat-потока (1.5): процесс жив, а тиков записи
     * нет. Дыра атрибутируется как «живой процесс, мертвый цикл записи», а не
     * как смерть процесса. База сдвигается, чтобы один разрыв не отчитывался
     * дважды: следующий отчет — не раньше нового порога.
     */
    fun checkFlushSilence(): Long {
        val prev = lastFlushTickMono
        if (prev == 0L) return 0L
        val now = DevLog.bootMonoMs()
        val gap = gapMs(prev, now)
        if (!isFreezeGap(gap)) return gap
        lastFlushTickMono = now
        DevLog.w(TAG, "freeze_gap", freezePayload(gap, source = "heartbeat"))
        return gap
    }

    // --- причина прошлой смерти ---

    /** `null` на API < 30: раздел проверки просто не покажет пункт. */
    fun readLastExit(context: Context): Map<String, Any?>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readExitApi30(context) else null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitApi30(context: Context): Map<String, Any?>? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = am
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull() ?: return null
        val age = gapMs(info.timestamp, DevLog.wallClock())
        buildMap {
            put(KEY_REASON, exitReasonName(info.reason))
            put(KEY_REASON_CODE, info.reason)
            put(KEY_AGE_MS, age)
            put(KEY_DEATH_WALL_MS, info.timestamp)
            vendorTag(info.description)?.let { put(KEY_VENDOR, it) }
            put(KEY_DESC, (info.description ?: "").take(DESC_MAX))
        }
    }.getOrNull()
}
