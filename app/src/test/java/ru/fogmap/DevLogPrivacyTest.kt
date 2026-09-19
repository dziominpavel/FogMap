package ru.fogmap

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.fogmap.diag.DevLog

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
}
