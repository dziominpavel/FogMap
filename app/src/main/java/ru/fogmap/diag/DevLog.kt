package ru.fogmap.diag

import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ВРЕМЕННОЕ (dev-logging): единая точка диагностики.
 * Формат строки: `ts_wall|ts_mono|session|seq|tag|level|msg|payload_json`.
 * Парсинг: `split('|', limit = 8)` — payload не содержит `|` и переносов.
 * Все классы временные (префикс Dev/Diag) — удаление одним revert.
 */
object DevLog {

    const val RING_MAX = 2000
    const val TAIL_MAX = 50
    const val LOGCAT_PREFIX = "FogMap"

    /**
     * Тег жизни процесса (fix-track-gap-visibility 1.1): отдельный логический
     * поток, который пишется и до, и после разрыва записи.
     */
    const val TAG_LIFE = "LIFE"

    /**
     * Абсолютное монотонное время с загрузки устройства, мс. В отличие от
     * `ts_mono` (отсчет от первого события процесса) сравнимо между сессиями.
     */
    const val KEY_BOOT_MONO_MS = "boot_mono_ms"

    enum class Level { D, I, W, E }

    data class Event(
        val tsWall: Long,
        val tsMono: Long,
        val session: String,
        val seq: Long,
        val tag: String,
        val level: Level,
        val msg: String,
        val payloadJson: String
    )

    /** Переопределяются в unit-тестах (на JVM нет SystemClock). */
    @Volatile
    var wallClock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var monoClock: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())
    }

    @Volatile
    var enabled: Boolean = true

    val session: String = UUID.randomUUID().toString().take(8)

    private val startMono: Long by lazy { monoClock() }

    private val seqCounter = AtomicLong(0)

    private val ring = ArrayDeque<String>()

    private val _tail = MutableStateFlow<List<String>>(emptyList())
    val tail: StateFlow<List<String>> = _tail.asStateFlow()

    /** Файловый writer подключается из FogMapApp (null в unit-тестах). */
    @Volatile
    var fileSink: ((String) -> Unit)? = null

    /** Глубина очереди writer для события PERF (0 когда writer нет). */
    @Volatile
    var queueDepthProvider: () -> Int = { 0 }

    fun log(tag: String, level: Level, msg: String, payload: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        // Одно чтение моно-часов на событие: ts_mono и boot_mono_ms описывают
        // один и тот же момент, иначе тест/парсер расходятся на миллисекунды.
        val nowMono = monoClock()
        // LIFE всегда несет абсолютную шкалу (fix-track-gap-visibility 1.1);
        // значение инжектится последним и не может быть подменено вызывающим.
        val fullPayload = if (tag == TAG_LIFE) {
            payload + (KEY_BOOT_MONO_MS to nowMono)
        } else payload
        val payloadJson = buildPayloadJson(fullPayload)
        val e = Event(
            tsWall = wallClock(),
            tsMono = nowMono - startMono,
            session = session,
            seq = seqCounter.incrementAndGet(),
            tag = tag,
            level = level,
            msg = msg.replace('|', '/').replace('\n', ' '),
            payloadJson = payloadJson
        )
        val line = format(e)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > RING_MAX) ring.removeFirst()
            _tail.value = ring.takeLast(TAIL_MAX)
        }
        runCatching { Log.println(logPriority(level), "$LOGCAT_PREFIX:$tag", line) }
        fileSink?.let { sink -> runCatching { sink(line) } }
    }

    fun d(tag: String, msg: String, payload: Map<String, Any?> = emptyMap()) =
        log(tag, Level.D, msg, payload)

    fun i(tag: String, msg: String, payload: Map<String, Any?> = emptyMap()) =
        log(tag, Level.I, msg, payload)

    fun w(tag: String, msg: String, payload: Map<String, Any?> = emptyMap()) =
        log(tag, Level.W, msg, payload)

    fun e(tag: String, msg: String, payload: Map<String, Any?> = emptyMap()) =
        log(tag, Level.E, msg, payload)

    fun queueDepth(): Int = runCatching { queueDepthProvider() }.getOrDefault(0)

    /**
     * Абсолютное монотонное время загрузки (fix-track-gap-visibility): та же
     * шкала, что в `boot_mono_ms` событий `LIFE`. Не зависит от старта процесса.
     */
    fun bootMonoMs(): Long = monoClock()

    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    fun clearBuffer() {
        synchronized(ring) {
            ring.clear()
            _tail.value = emptyList()
        }
    }

    fun format(e: Event): String =
        "${e.tsWall}|${e.tsMono}|${e.session}|${e.seq}|${e.tag}|${e.level}|${e.msg}|${e.payloadJson}"

    fun parse(line: String): Event? {
        val parts = line.split('|', limit = 8)
        if (parts.size != 8) return null
        val seq = parts[3].toLongOrNull() ?: return null
        val level = runCatching { Level.valueOf(parts[5]) }.getOrNull() ?: return null
        return Event(
            tsWall = parts[0].toLongOrNull() ?: return null,
            tsMono = parts[1].toLongOrNull() ?: return null,
            session = parts[2],
            seq = seq,
            tag = parts[4],
            level = level,
            msg = parts[6],
            payloadJson = parts[7]
        )
    }

    /**
     * Плоский JSON-объект, 1 уровень. Ключи с `lat`/`lon` запрещены
     * (приватность: только клетки x,y,z21) — бросает IllegalArgumentException.
     */
    fun buildPayloadJson(payload: Map<String, Any?>): String {
        if (payload.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in payload) {
            val kl = k.lowercase()
            require(!kl.contains("lat") && !kl.contains("lon") && !kl.contains("latitude") && !kl.contains("longitude")) {
                "DevLog payload запрещает координаты: key=$k"
            }
            require(!k.contains('|') && !k.contains('\n') && !k.contains('"')) {
                "DevLog bad key: $k"
            }
            if (v == null) continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(escape(v.toString())).append('"')
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append(' ')
                '|' -> sb.append('/')
                else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun logPriority(l: Level): Int = when (l) {
        Level.D -> Log.DEBUG
        Level.I -> Log.INFO
        Level.W -> Log.WARN
        Level.E -> Log.ERROR
    }
}
