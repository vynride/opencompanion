// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import java.time.LocalDateTime
import java.time.LocalTime

/** A daily window in which the companion is stopped; it may cross midnight. Equal bounds mean no window. */
data class QuietHours(
    val start: LocalTime,
    val end: LocalTime,
) {
    fun isQuiet(now: LocalTime): Boolean = when {
        start == end -> false
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }

    /** The next start or end strictly after `now`. */
    fun nextBoundary(now: LocalDateTime): LocalDateTime = listOf(start, end)
        .map { time -> now.toLocalDate().atTime(time).let { if (it > now) it else it.plusDays(1) } }
        .min()

    companion object {
        fun fromMinutes(
            start: Int,
            end: Int,
        ) = QuietHours(LocalTime.ofSecondOfDay(start * 60L), LocalTime.ofSecondOfDay(end * 60L))
    }
}

fun LocalTime.toMinutesOfDay(): Int = toSecondOfDay() / 60
