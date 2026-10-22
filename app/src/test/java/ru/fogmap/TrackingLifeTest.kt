package ru.fogmap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.TrackingLife

/**
 * fix-track-gap-visibility 1.2/1.3/1.5/1.6: канал жизни процесса.
 *
 * Метка последнего события переживает убийство процесса (файл в filesDir),
 * поэтому разрыв после рестарта равен времени отсутствия процесса, а не нулю.
 */
class TrackingLifeTest {

    private fun tempMarker(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "fogmap-life-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, TrackingLife.MARKER_FILE)
    }

    @Test
    fun `метка переживает смерть процесса и gap равен времени отсутствия`() {
        val file = tempMarker()
        val diedAt = 1_700_000_000_000L
        TrackingLife.writeMarker(file, TrackingLife.Marker(diedAt, 1_234_000L, "sess-a"))

        // «Смерть» процесса: новый старт через 5 минут читает ту же метку.
        val restartedAt = diedAt + 5 * 60_000L
        val m = TrackingLife.readMarker(file)!!
        assertEquals("sess-a", m.session)
        assertEquals(1_234_000L, m.bootMonoMs)
        assertEquals(5 * 60_000L, TrackingLife.gapMs(m.wallMs, restartedAt))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun `битая метка не роняет старт`() {
        val file = tempMarker()
        file.writeText("не-метка")
        assertNull(TrackingLife.readMarker(file))
        // Отсутствующий файл — тоже null, а не исключение.
        assertNull(TrackingLife.readMarker(File(file.parentFile, "nope.txt")))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun `метка кодируется и разбирается без потерь`() {
        val m = TrackingLife.Marker(1_700_000_000_000L, 987_654_321L, "0ed4a2ce")
        assertEquals(m, TrackingLife.parseMarker(TrackingLife.encodeMarker(m)))
        assertNull(TrackingLife.parseMarker(null))
        assertNull(TrackingLife.parseMarker(""))
    }

    @Test
    fun `отрицательный разрыв не превращается в дыру`() {
        // Часы могли перевести назад: gap не должен быть отрицательным.
        assertEquals(0L, TrackingLife.gapMs(2_000L, 1_500L))
    }

    @Test
    fun `порог заморозки — десять минут`() {
        assertFalse(TrackingLife.isFreezeGap(TrackingLife.FREEZE_GAP_MS - 1))
        assertTrue(TrackingLife.isFreezeGap(TrackingLife.FREEZE_GAP_MS))
        assertTrue(TrackingLife.isFreezeGap(226 * 60_000L))
    }

    @Test
    fun `freeze_gap несет фактическую длительность а не порог`() {
        val p = TrackingLife.freezePayload(12 * 60_000L)
        assertEquals(720_000L, p[TrackingLife.KEY_GAP_MS] as Long)
        assertEquals("flush", p[TrackingLife.KEY_SOURCE])
        val hb = TrackingLife.freezePayload(15 * 60_000L, source = "heartbeat")
        assertEquals("heartbeat", hb[TrackingLife.KEY_SOURCE])
    }

    @Test
    fun `proc_start несет прошлую сессию и разрыв`() {
        val prev = TrackingLife.Marker(1_000_000L, 555_000L, "prev-session")
        val payload = TrackingLife.procStartPayload(prev, 90_000L)
        assertEquals("prev-session", payload[TrackingLife.KEY_PREV_SESSION])
        assertEquals(555_000L, payload[TrackingLife.KEY_PREV_BOOT_MONO_MS] as Long)
        assertEquals(90_000L, payload[TrackingLife.KEY_GAP_MS] as Long)
        // Первый запуск: ни прошлой сессии, ни разрыва.
        val first = TrackingLife.procStartPayload(null, null)
        assertNull(first[TrackingLife.KEY_PREV_SESSION])
        assertNull(first[TrackingLife.KEY_GAP_MS])
    }

    @Test
    fun `heartbeat несет канал и фактический интервал`() {
        val p = TrackingLife.heartbeatPayload("alarm", 300_123L)
        assertEquals("alarm", p[TrackingLife.KEY_VIA])
        assertEquals(300_123L, p[TrackingLife.KEY_SINCE_LAST_HEARTBEAT_MS] as Long)
    }

    @Test
    fun `причина смерти читается по коду и не теряет неизвестную`() {
        assertEquals("exit_self", TrackingLife.exitReasonName(1))
        assertEquals("low_memory", TrackingLife.exitReasonName(3))
        assertEquals("anr", TrackingLife.exitReasonName(6))
        assertEquals("user_requested", TrackingLife.exitReasonName(10))
        // API 31+: системный freezer и обновление пакета.
        assertEquals("freezer", TrackingLife.exitReasonName(12))
        assertEquals("package_updated", TrackingLife.exitReasonName(14))
        assertEquals("code_99", TrackingLife.exitReasonName(99))
    }

    @Test
    fun `вендорский тег термического убийцы вытаскивается из описания`() {
        val desc = "stop ru.fogmap due to from pid 2819 iAwareF[Thermal] -1ms"
        assertEquals("iAwareF[Thermal]", TrackingLife.vendorTag(desc))
        assertNull(TrackingLife.vendorTag(null))
        assertNull(TrackingLife.vendorTag("plain reason without brackets"))
    }
}
