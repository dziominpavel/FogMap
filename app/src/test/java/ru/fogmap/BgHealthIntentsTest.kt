package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.ui.BgHealthIntents

/**
 * fix-track-gap-visibility 5.2: переход к исправлению обязан выживать на
 * устройстве без нужного экрана — Huawei-кандидаты не единственный путь,
 * запасной переход всегда настройки приложения.
 *
 * Тест на JVM: generic-форма выбора не требует android.content.Intent.
 */
class BgHealthIntentsTest {

    private val candidates = listOf("huawei-startup", "huawei-appcontrol", "generic-action")

    @Test
    fun `первый резолвящийся кандидат выигрывает`() {
        val picked = BgHealthIntents.pickAny(candidates, { it == "huawei-appcontrol" }, "details")
        assertEquals("huawei-appcontrol", picked)
    }

    @Test
    fun `порядок кандидатов сохраняется`() {
        val picked = BgHealthIntents.pickAny(candidates, { true }, "details")
        assertEquals("huawei-startup", picked)
    }

    @Test
    fun `если экранов нет — запасной переход`() {
        val picked = BgHealthIntents.pickAny(candidates, { false }, "details")
        assertEquals("details", picked)
    }

    @Test
    fun `пустой список кандидатов тоже ведет в настройки`() {
        val picked = BgHealthIntents.pickAny(emptyList<String>(), { true }, "details")
        assertEquals("details", picked)
    }
}
