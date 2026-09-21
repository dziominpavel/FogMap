package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.data.FogRepository
import ru.fogmap.data.StatsRepository

/**
 * Медиана префикса по бакетам (fix-eco-signal-loss 4.4): среднее уезжало
 * от одного разрыва, медиана держится распределения.
 */
class EcoPrefixMedianTest {
    @Test
    fun `пустая гистограмма — ноль`() {
        assertEquals(0.0, StatsRepository.prefixMedianFromBuckets(0, 0, 0, 0), 0.001)
    }

    @Test
    fun `перевес коротких префиксов держит медиану в бюджете`() {
        // 3 коротких и 1 разрыв: среднее уехало бы, медиана — 50 м.
        assertEquals(50.0, StatsRepository.prefixMedianFromBuckets(3, 0, 0, 1), 0.001)
    }

    @Test
    fun `медиана во втором бакете — еще бюджет`() {
        assertEquals(150.0, StatsRepository.prefixMedianFromBuckets(2, 2, 0, 0), 0.001)
    }

    @Test
    fun `половина длинных — превышение бюджета`() {
        assertEquals(350.0, StatsRepository.prefixMedianFromBuckets(1, 1, 2, 0), 0.001)
    }

    @Test
    fun `один разрыв на дне гистограммы`() {
        assertEquals(750.0, StatsRepository.prefixMedianFromBuckets(0, 0, 0, 1), 0.001)
    }

    @Test
    fun `метрики разрывов и бакетов объявлены в наборе дня`() {
        // fix-eco-signal-loss 4.3: без регистрации в ECO_METRICS их не видно
        // в diagnostics/ecoBreakdown.
        val metrics = FogRepository.ECO_METRICS
        assertTrue(FogRepository.ECO_GAP_CM in metrics)
        assertTrue(FogRepository.ECO_GAP_N in metrics)
        assertTrue(FogRepository.ECO_PREFIX_B100_N in metrics)
        assertTrue(FogRepository.ECO_PREFIX_B200_N in metrics)
        assertTrue(FogRepository.ECO_PREFIX_B500_N in metrics)
        assertTrue(FogRepository.ECO_PREFIX_BHI_N in metrics)
    }
}
