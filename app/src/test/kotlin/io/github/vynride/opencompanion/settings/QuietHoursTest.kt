// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {
    private val window = QuietHours(start = LocalTime.of(22, 0), end = LocalTime.of(7, 0))

    @Test
    fun `a window across midnight is quiet late at night and early in the morning`() {
        assertTrue(window.isQuiet(LocalTime.of(23, 30)))
        assertTrue(window.isQuiet(LocalTime.of(3, 0)))
        assertFalse(window.isQuiet(LocalTime.of(12, 0)))
    }

    @Test
    fun `the start is quiet and the end is not`() {
        assertTrue(window.isQuiet(LocalTime.of(22, 0)))
        assertFalse(window.isQuiet(LocalTime.of(7, 0)))
    }

    @Test
    fun `a window within one day is quiet only between its bounds`() {
        val nap = QuietHours(start = LocalTime.of(13, 0), end = LocalTime.of(14, 0))
        assertTrue(nap.isQuiet(LocalTime.of(13, 30)))
        assertFalse(nap.isQuiet(LocalTime.of(12, 59)))
        assertFalse(nap.isQuiet(LocalTime.of(14, 0)))
    }

    @Test
    fun `equal bounds are never quiet`() {
        val none = QuietHours(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0))
        assertFalse(none.isQuiet(LocalTime.of(9, 0)))
        assertFalse(none.isQuiet(LocalTime.of(21, 0)))
    }

    @Test
    fun `the next boundary is the coming start during the day`() {
        val now = LocalDateTime.of(2026, 9, 3, 12, 0)
        assertEquals(LocalDateTime.of(2026, 9, 3, 22, 0), window.nextBoundary(now))
    }

    @Test
    fun `the next boundary is tomorrow's end late at night`() {
        val now = LocalDateTime.of(2026, 9, 3, 23, 0)
        assertEquals(LocalDateTime.of(2026, 9, 4, 7, 0), window.nextBoundary(now))
    }

    @Test
    fun `a boundary landing exactly now is skipped for the next one`() {
        val now = LocalDateTime.of(2026, 9, 3, 22, 0)
        assertEquals(LocalDateTime.of(2026, 9, 4, 7, 0), window.nextBoundary(now))
    }

    @Test
    fun `minutes of day round trip`() {
        val fromMinutes = QuietHours.fromMinutes(start = 22 * 60, end = 7 * 60)
        assertEquals(window, fromMinutes)
        assertEquals(22 * 60, fromMinutes.start.toMinutesOfDay())
    }
}

class QuietHoursSettingsTest {
    @Test
    fun `settings yield a window only when enabled`() {
        val off = Settings(quietStartMin = 22 * 60, quietEndMin = 7 * 60)
        assertEquals(null, off.quietHours())
        val on = off.copy(quietHoursEnabled = true)
        assertEquals(QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)), on.quietHours())
    }
}
