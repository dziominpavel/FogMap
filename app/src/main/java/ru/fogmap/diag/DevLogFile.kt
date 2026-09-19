package ru.fogmap.diag

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ВРЕМЕННОЕ (dev-logging): фоновый writer JSONL.
 * UI-поток только кладет строку в очередь и возвращается.
 */
class DevLogFile(private val context: Context) {

    private val queue = LinkedBlockingQueue<String>(4096)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DevLogFile").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        DevLog.queueDepthProvider = { queue.size }
        DevLog.fileSink = { line -> queue.offer(line) }
        executor.execute { drainLoop() }
    }

    fun stop() {
        running = false
        DevLog.fileSink = null
        executor.shutdown()
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    /** Best-effort сброс при убийстве процесса (потери < 1 сек). */
    fun flushBlocking(timeoutMs: Long = 1000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val line = queue.poll(50, TimeUnit.MILLISECONDS) ?: break
                appendLine(line)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun queueDepth(): Int = queue.size

    fun currentFile(): File = logDir().let { dir ->
        File(dir, "fog-${LocalDate.now()}.jsonl")
    }

    fun allFiles(): List<File> =
        runCatching {
            logDir().listFiles { f -> f.name.startsWith("fog-") && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())

    /** Удаляет файлы старше 24 часов. Возвращает число удаленных. */
    fun cleanupOlderThan24h(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        for (f in allFiles()) {
            if (nowMs - f.lastModified() > TTL_MS && f.delete()) removed++
        }
        return removed
    }

    fun clearAll(): Int {
        var removed = 0
        for (f in allFiles()) if (f.delete()) removed++
        queue.clear()
        DevLog.clearBuffer()
        return removed
    }

    private fun drainLoop() {
        while (running || queue.isNotEmpty()) {
            try {
                val line = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                appendLine(line)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun appendLine(line: String) {
        runCatching {
            val file = currentFile()
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                // Fail-safe: дропаем с маркером вместо бесконечного роста.
                if (!rotatedToday(file)) {
                    file.appendText(lineToJsonlFallback("log_rotated", mapOf("size" to file.length())) + "\n")
                    file.setLastModified(System.currentTimeMillis())
                }
                return
            }
            file.appendText(line + "\n")
        }
    }

    private fun rotatedToday(file: File): Boolean {
        // Грубая метка: файл-маркер рядом; дешевле чем сканировать хвост.
        val marker = File(file.parent, file.name + ".rotated")
        if (marker.exists()) return true
        runCatching { marker.createNewFile() }
        DevLog.w("DevLogFile", "log_rotated", mapOf("file" to file.name))
        return false
    }

    private fun lineToJsonlFallback(msg: String, payload: Map<String, Any?>): String {
        // Не через DevLog.log (иначе рекурсия в sink): форматируем вручную.
        return "${System.currentTimeMillis()}|0|${DevLog.session}|0|DevLogFile|W|$msg|${DevLog.buildPayloadJson(payload)}"
    }

    private fun logDir(): File = File(context.cacheDir, "logs")

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val TTL_MS = 24L * 60L * 60L * 1000L
    }
}
