package ru.fogmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fogmap.tracking.LocationFilter

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
}
