package ru.fogmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.LocationFilter
import ru.fogmap.tracking.MotionKind

class LocationFilterTest {
    @Test
    fun `дрейф с accuracy 60 м отбрасывается`() {
        assertFalse(
            LocationFilter.accept(
                LocationFilter.Input(accuracy = 60f, speed = 1f, isMock = false)
            )
        )
    }

    @Test
    fun `граница accuracy 25 м принимается, 25 целых 1 — нет`() {
        assertTrue(LocationFilter.accept(LocationFilter.Input(25f, 1f, false)))
        assertFalse(LocationFilter.accept(LocationFilter.Input(25.1f, 1f, false)))
    }

    @Test
    fun `скорость выше 150 км в ч отбрасывается`() {
        assertFalse(LocationFilter.accept(LocationFilter.Input(10f, 50f, false)))
        assertTrue(LocationFilter.accept(LocationFilter.Input(10f, 10f, false)))
    }

    @Test
    fun `mock-точки игнорируются`() {
        assertFalse(LocationFilter.accept(LocationFilter.Input(5f, 1f, true)))
    }

    @Test
    fun `точка без accuracy отбрасывается`() {
        assertFalse(LocationFilter.accept(LocationFilter.Input(null, 1f, false)))
    }

    @Test
    fun `per-type accuracy 25 40 100`() {
        // BIKE: 35 проходит, 45 — нет.
        assertTrue(
            LocationFilter.accept(
                LocationFilter.Input(35f, 5f, false), MotionKind.BIKE
            )
        )
        assertFalse(
            LocationFilter.accept(
                LocationFilter.Input(45f, 5f, false), MotionKind.BIKE
            )
        )
        // VEHICLE: 80 проходит, WALK 60 — нет.
        assertTrue(
            LocationFilter.accept(
                LocationFilter.Input(80f, 20f, false), MotionKind.VEHICLE
            )
        )
        assertFalse(
            LocationFilter.accept(
                LocationFilter.Input(60f, 1.5f, false), MotionKind.WALK
            )
        )
        // STILL/WALK — прежний порог 25.
        assertFalse(
            LocationFilter.accept(
                LocationFilter.Input(30f, 0.2f, false), MotionKind.STILL
            )
        )
        assertEquals(25f, LocationFilter.maxAccuracyFor(MotionKind.STILL))
        assertEquals(40f, LocationFilter.maxAccuracyFor(MotionKind.BIKE))
        assertEquals(100f, LocationFilter.maxAccuracyFor(MotionKind.VEHICLE))
    }

    @Test
    fun `причины отбросов различаются`() {
        assertEquals(
            LocationFilter.Reason.BAD_ACCURACY,
            LocationFilter.reason(LocationFilter.Input(60f, 1f, false))
        )
        assertEquals(
            LocationFilter.Reason.BAD_SPEED,
            LocationFilter.reason(LocationFilter.Input(10f, 50f, false))
        )
        assertEquals(
            LocationFilter.Reason.MOCK,
            LocationFilter.reason(LocationFilter.Input(5f, 1f, true))
        )
        assertEquals(
            LocationFilter.Reason.OK,
            LocationFilter.reason(LocationFilter.Input(10f, 1f, false))
        )
        // Скорость >150 км/ч жёстко отбрасывается даже при чистом kind-пороге.
        assertEquals(
            LocationFilter.Reason.BAD_SPEED,
            LocationFilter.reason(
                LocationFilter.Input(10f, 50f, false), MotionKind.VEHICLE
            )
        )
    }
}
