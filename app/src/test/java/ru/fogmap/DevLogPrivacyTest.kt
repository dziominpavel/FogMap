package ru.fogmap

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.fogmap.diag.DevLog
import ru.fogmap.tracking.TrackingService

/**
 * ВРЕМЕННОЕ (dev-logging): приватность координат в логах.
 * Точные lat/lon запрещены — только клетки x,y,z21 и количества.
 */
class DevLogPrivacyTest {

    @Test
    fun `payload отвергает ключи с координатами`() {
        for (key in listOf("lat", "lon", "latitude", "longitude", "LAT", "center_lat")) {
            try {
                DevLog.buildPayloadJson(mapOf(key to 1.0))
                fail("ключ $key должен быть отвергнут")
            } catch (_: IllegalArgumentException) {
                // ok
            }
        }
        // Легитимные ключи проходят.
        DevLog.buildPayloadJson(
            mapOf("cells" to 1, "cell_x" to 2, "cell_y" to 3, "z" to 21, "tilt" to 0.5)
        )
    }

    @Test
    fun `в вызовах DevLog нет ключей lat lon`() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: return // исходники недоступны из этого окружения — пропускаем скан
        val keyPattern = Regex("\"[^\"]*(lat|lon|latitude|longitude)[^\"]*\"\\s*(to|=)")
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("DevLog.") && keyPattern.containsMatchIn(line.lowercase())) {
                    bad.add("${f.name}:${i + 1}: $line")
                }
            }
        }
        assertTrue("координаты в DevLog вызовах:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * diag-start-failures 3.1: события отказов старта обязаны существовать,
     * не тащить координаты и обрезать текст ошибки.
     */
    @Test
    fun `отказы старта пишутся событиями без координат и с обрезкой err_msg`() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory } ?: return
        val lines = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { it.readLines() }
            .toList()

        val events = listOf(
            "start_attempt", "service_init_skip", "watchdog", "watchdog_schedule"
        )
        for (e in events) {
            assertTrue(
                "событие TRACK/$e должно писаться в исходниках",
                lines.any { it.contains("\"$e\"") }
            )
        }

        val keyPattern = Regex("\"[^\"]*(lat|lon|latitude|longitude)[^\"]*\"\\s*(to|=)")
        val offenders = lines.filter { line ->
            events.any { line.contains("\"$it\"") } &&
                keyPattern.containsMatchIn(line.lowercase())
        }
        assertTrue("координаты в событиях отказов:\n" + offenders.joinToString("\n"), offenders.isEmpty())

        val errLines = lines.filter { it.contains("\"err_msg\"") }
        assertTrue("err_msg обязан обрезаться", errLines.isNotEmpty())
        assertTrue(
            "обрезка не найдена:\n" + errLines.joinToString("\n"),
            errLines.all { it.contains("take(") }
        )
    }

    /** errText обрезает сообщение и сохраняет класс исключения. */
    @Test
    fun `errText обрезает сообщение до порога`() {
        val t = TrackingService.errText(RuntimeException("x".repeat(500)))
        assertTrue("длина ${t.length} > ${TrackingService.ERR_MSG_MAX}", t.length <= TrackingService.ERR_MSG_MAX)
        assertTrue("класс исключения потерян: $t", t.startsWith("RuntimeException:"))
        val short = TrackingService.errText(IllegalStateException("boom"))
        assertTrue(short.contains("boom"))
        val noMsg = TrackingService.errText(ArrayIndexOutOfBoundsException())
        assertTrue(noMsg.startsWith("ArrayIndexOutOfBoundsException:"))
    }
}
