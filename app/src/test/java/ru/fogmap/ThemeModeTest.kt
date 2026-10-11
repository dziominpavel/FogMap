package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fogmap.data.ThemeModes
import ru.fogmap.ui.theme.isDarkTheme

/**
 * remove-system-theme (design D1/D2): только dark/light, устаревшее
 * `system` и мусор мигрируют в `dark`, `isDarkTheme` без второго аргумента.
 */
class ThemeModeTest {
    @Test
    fun `нормализация — light остаётся light, system уходит в dark`() {
        assertEquals("light", ThemeModes.normalize("light"))
        assertEquals("dark", ThemeModes.normalize("system"))
        assertEquals("dark", ThemeModes.normalize(null))
        assertEquals("dark", ThemeModes.normalize("DARK")) // регистр не вскрывается
        assertEquals("dark", ThemeModes.normalize("garbage"))
    }

    @Test
    fun `дефолт темы — dark`() {
        assertEquals("dark", ThemeModes.DEFAULT)
    }

    @Test
    fun `isDarkTheme — light светлая, всё остальное тёмное`() {
        assertEquals(false, isDarkTheme("light"))
        assertEquals(true, isDarkTheme("dark"))
        // Двойная защита (design D1): не-нормализованное значение из прямого
        // чтения ключа трактуется как тёмное, а не как системное.
        assertEquals(true, isDarkTheme("system"))
        assertEquals(true, isDarkTheme(""))
    }
}
