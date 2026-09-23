package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.MotionKind
import ru.fogmap.tracking.MotionKindClassifier

class MotionKindTest {
    @Test
    fun `границы полос 1_0 2_5 8_0`() {
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(0.5f, null))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(0.99f, null))
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(1.0f, null))
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(2.49f, null))
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(2.5f, null))
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(7.99f, null))
        assertEquals(MotionKind.VEHICLE, MotionKindClassifier.classify(8.0f, null))
        assertEquals(MotionKind.VEHICLE, MotionKindClassifier.classify(30f, null))
    }

    @Test
    fun `нет speed сохраняет prev или STILL`() {
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(null, MotionKind.WALK))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(null, null))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(-1f, null))
    }

    @Test
    fun `гистерезис не мигает на шуме ±0_2 от границы`() {
        // От WALK вверх: 2.5 без гистерезиса дал бы BIKE, с гистерезисом — WALK.
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(2.5f, MotionKind.WALK))
        // Выход из WALK только на 0.3 выше верхней границы (2.5+0.3=2.8).
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(2.7f, MotionKind.WALK))
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(2.8f, MotionKind.WALK))
        // Вниз из BIKE: 2.5-0.3=2.2 ещё BIKE, ниже — WALK.
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(2.2f, MotionKind.BIKE))
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(2.1f, MotionKind.BIKE))
        // STILL↔WALK на границе 1.0: уход вверх только с 1.3, вниз — с 0.7.
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(1.0f, MotionKind.STILL))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(1.2f, MotionKind.STILL))
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(1.3f, MotionKind.STILL))
        assertEquals(MotionKind.WALK, MotionKindClassifier.classify(0.8f, MotionKind.WALK))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(0.69f, MotionKind.WALK))
        // Шум ±0.2 на границе BIKE/VEHICLE не мигает.
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(8.0f, MotionKind.BIKE))
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(8.2f, MotionKind.BIKE))
        assertEquals(MotionKind.VEHICLE, MotionKindClassifier.classify(8.3f, MotionKind.BIKE))
    }

    @Test
    fun `явная скорость вне prev-полосы уводит сразу`() {
        // prev=STILL, явная VEHICLE — сразу VEHICLE (нет гистерезиса вверх).
        assertEquals(MotionKind.VEHICLE, MotionKindClassifier.classify(20f, MotionKind.STILL))
        // prev=VEHICLE, скорость ниже 7.7 уходит в целевую полосу сразу.
        assertEquals(MotionKind.VEHICLE, MotionKindClassifier.classify(7.8f, MotionKind.VEHICLE))
        assertEquals(MotionKind.BIKE, MotionKindClassifier.classify(7.6f, MotionKind.VEHICLE))
        assertEquals(MotionKind.STILL, MotionKindClassifier.classify(0.5f, MotionKind.VEHICLE))
    }
}
