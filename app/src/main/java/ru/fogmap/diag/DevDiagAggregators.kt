package ru.fogmap.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.Locale

/**
 * ВРЕМЕННОЕ (dev-logging): агрегаторы горячего пути.
 * Правило: в слушателях камеры/кадрах — только примитивы, ноль строк.
 * Строки собираются здесь на сбросе окна.
 */
object DevCameraStats {
    /** Порог шторма (no-tilt-plus-diag): наблюдалось 300+/сек в петле, обычный жест — десятки. */
    const val STORM_EVENTS_PER_S = 60.0
    /** Минимум длительности окна для детекции: пара быстрых событий — не шторм. */
    const val STORM_MIN_WINDOW_MS = 500L

    private var count = 0
    private var tiltNonZero = 0
    private var tiltFix = 0
    private var moves = 0
    private var stormFired = false
    private var lastZoom = 0f
    private var lastAzimuth = 0f
    private var lastTilt = 0f
    private var windowStartMono = 0L
    private var started = false

    /** Исходящее программное движение камеры (кнопки, стартовое позиционирование). */
    @Synchronized
    fun onMove() {
        moves++
    }

    @Synchronized
    fun onEvent(zoom: Float, azimuth: Float, tilt: Float, tiltFixed: Boolean, nowMono: Long) {
        if (!started) {
            started = true
            windowStartMono = nowMono
        }
        count++
        lastZoom = zoom
        lastAzimuth = azimuth
        lastTilt = tilt
        if (tilt > 1f) tiltNonZero++
        if (tiltFixed) tiltFix++
        val dtMs = nowMono - windowStartMono
        // no-tilt-plus-diag: детектор шторма — сразу, не дожидаясь конца окна.
        // Строки строятся только в момент срабатывания (раз на окно), не на событие.
        if (!stormFired && dtMs >= STORM_MIN_WINDOW_MS) {
            val eps = if (dtMs > 0) count * 1000.0 / dtMs else 0.0
            if (eps > STORM_EVENTS_PER_S) {
                stormFired = true
                DevLog.w(
                    "CAMERA", "storm",
                    mapOf(
                        "events_per_s" to String.format(Locale.US, "%.1f", eps),
                        "events" to count,
                        "window_ms" to dtMs,
                        "zoom" to String.format(Locale.US, "%.1f", lastZoom),
                        "azimuth" to String.format(Locale.US, "%.0f", lastAzimuth),
                        "tilt" to String.format(Locale.US, "%.1f", lastTilt),
                        "moves" to moves
                    )
                )
            }
        }
        if (dtMs >= 2000L) {
            val eps = if (dtMs > 0) count * 1000.0 / dtMs else 0.0
            DevLog.i(
                "CAMERA", "agg",
                mapOf(
                    "events" to count,
                    "events_per_s" to String.format(Locale.US, "%.1f", eps),
                    "zoom" to String.format(Locale.US, "%.1f", lastZoom),
                    "azimuth" to String.format(Locale.US, "%.0f", lastAzimuth),
                    "tilt" to String.format(Locale.US, "%.1f", lastTilt),
                    "tilt_nonzero" to tiltNonZero,
                    "tilt_fix" to tiltFix,
                    "moves" to moves
                )
            )
            count = 0
            tiltNonZero = 0
            tiltFix = 0
            moves = 0
            stormFired = false
            windowStartMono = nowMono
        }
    }
}

object DevRenderStats {
    private var frames = 0
    private var sumTotalMs = 0.0
    private var maxTotalMs = 0.0
    private var sumMergeMs = 0.0
    private var sumProjMs = 0.0
    private var lastCells = 0
    private var lastHoles = 0
    private var lastNullProj = 0
    private var overBudgetHits = 0
    private var windowStartMono = 0L
    private var started = false

    @Synchronized
    fun onFrame(
        dtMergeMs: Double, dtProjMs: Double, dtTotalMs: Double,
        cells: Int, holes: Int, nullProj: Int, overBudget: Boolean,
        nowMono: Long
    ) {
        // Аномалии — сразу, мимо агрегатора.
        if (dtTotalMs > 500.0 || overBudget) {
            DevLog.w(
                "RENDER", "slow_frame",
                mapOf(
                    "dt_total_ms" to String.format(Locale.US, "%.1f", dtTotalMs),
                    "dt_merge_ms" to String.format(Locale.US, "%.1f", dtMergeMs),
                    "dt_proj_ms" to String.format(Locale.US, "%.1f", dtProjMs),
                    "cells" to cells,
                    "holes" to holes,
                    "null_proj" to nullProj,
                    "over_budget" to overBudget
                )
            )
        }
        if (!started) {
            started = true
            windowStartMono = nowMono
        }
        frames++
        sumTotalMs += dtTotalMs
        sumMergeMs += dtMergeMs
        sumProjMs += dtProjMs
        if (dtTotalMs > maxTotalMs) maxTotalMs = dtTotalMs
        lastCells = cells
        lastHoles = holes
        lastNullProj = nullProj
        if (overBudget) overBudgetHits++
        if (nowMono - windowStartMono >= 2000L) {
            val avg = if (frames > 0) sumTotalMs / frames else 0.0
            DevLog.i(
                "RENDER", "agg",
                mapOf(
                    "frames" to frames,
                    "avg_total_ms" to String.format(Locale.US, "%.1f", avg),
                    "max_total_ms" to String.format(Locale.US, "%.1f", maxTotalMs),
                    "avg_merge_ms" to String.format(Locale.US, "%.1f", if (frames > 0) sumMergeMs / frames else 0.0),
                    "avg_proj_ms" to String.format(Locale.US, "%.1f", if (frames > 0) sumProjMs / frames else 0.0),
                    "cells" to lastCells,
                    "holes" to lastHoles,
                    "null_proj" to lastNullProj,
                    "over_budget_hits" to overBudgetHits
                )
            )
            frames = 0
            sumTotalMs = 0.0
            maxTotalMs = 0.0
            sumMergeMs = 0.0
            sumProjMs = 0.0
            overBudgetHits = 0
            windowStartMono = nowMono
        }
    }
}

/**
 * ВРЕМЕННОЕ (dev-logging): PERF — heap раз в 10 сек, jank через Choreographer,
 * ANR-сторож с heartbeat 5 сек.
 */
object DevPerfMonitor {
    private var running = false
    private var lastFrameNanos = 0L
    private var lastAggMono = 0L
    private var cellsCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogThread: Thread? = null

    @Volatile
    private var uiTick = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1e6
                if (deltaMs > 700.0) {
                    DevLog.w(
                        "PERF", "jank",
                        mapOf(
                            "gap_ms" to String.format(Locale.US, "%.0f", deltaMs),
                            "cells" to cellsCount,
                            "log_queue" to DevLog.queueDepth()
                        )
                    )
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val nowMono = DevLog.monoClock()
        lastAggMono = nowMono
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
        // Heartbeat на UI-потоке для ANR-сторожа.
        val beat = object : Runnable {
            override fun run() {
                if (!running) return
                uiTick = System.currentTimeMillis()
                val mono = DevLog.monoClock()
                if (mono - lastAggMono >= 10_000L) {
                    lastAggMono = mono
                    val rt = Runtime.getRuntime()
                    val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L
                    // no-tilt-plus-diag: нативный heap — именно он был раздут (282MB)
                    // при живом dalvik; тренд роста важнее точного учета GL.
                    val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576L
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = am?.let {
                        ActivityManager.MemoryInfo().also { mi -> runCatching { it.getMemoryInfo(mi) } }
                    }
                    DevLog.i(
                        "PERF", "agg",
                        mapOf(
                            "heap_mb" to heapMb,
                            "native_heap_mb" to nativeHeapMb,
                            "cells" to cellsCount,
                            "log_queue" to DevLog.queueDepth(),
                            "low_mem" to (memInfo?.lowMemory ?: false)
                        )
                    )
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.post(beat)
        // ANR-сторож: отдельный поток, ждет heartbeat.
        uiTick = System.currentTimeMillis()
        watchdogThread = Thread({
            while (running) {
                try {
                    Thread.sleep(5000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val silentMs = System.currentTimeMillis() - uiTick
                if (silentMs > 5000L) {
                    DevLog.w(
                        "PERF", "anr_watchdog",
                        mapOf("ui_silent_ms" to silentMs, "cells" to cellsCount)
                    )
                }
            }
        }, "DevAnrWatchdog").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    fun setCells(n: Int) {
        cellsCount = n
    }
}
