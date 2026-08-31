// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.FakeHaptics
import io.github.vynride.opencompanion.core.FixedClock
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.bus.TimerDone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClockToolsTest {
    @Test
    fun `get_time formats the injected clock`() =
        runTest {
            val clock = FixedClock(Instant.parse("2026-03-04T15:30:00Z"), ZoneId.of("UTC"))
            val tools = clockTools(clock, FakeHaptics(), EventBus(), backgroundScope)
            assertEquals(
                "Wednesday 4 March 2026, 15:30 (UTC)",
                tools.first { it.name == "get_time" }.call(buildJsonObject {}),
            )
        }

    @Test
    fun `get_time does not zero pad single digit day`() =
        runTest {
            val clock = FixedClock(Instant.parse("2026-08-05T09:00:00Z"), ZoneId.of("UTC"))
            val tools = clockTools(clock, FakeHaptics(), EventBus(), backgroundScope)
            assertEquals(
                "Wednesday 5 August 2026, 09:00 (UTC)",
                tools.first { it.name == "get_time" }.call(buildJsonObject {}),
            )
        }

    @Test
    fun `set_timer fires after the delay with a buzz and a say`() =
        runTest {
            val bus = EventBus()
            val haptics = FakeHaptics()
            val events = mutableListOf<Event>()
            bus.events.onEach { events += it }.launchIn(backgroundScope)
            val tools = clockTools(FixedClock(Instant.parse("2026-01-01T12:00:00Z")), haptics, bus, backgroundScope)
            val setTimer = tools.first { it.name == "set_timer" }
            val reply =
                setTimer.call(
                    buildJsonObject {
                        put("seconds", 2)
                        put("label", "tea")
                    },
                )
            assertEquals("Timer 'tea' set for 2 seconds.", reply)
            advanceTimeBy(2500)
            assertEquals(listOf(400L), haptics.buzzes)
            assertTrue(events.contains(Say("Timer done: tea")))
            assertTrue(events.contains(TimerDone("tea")))
        }

    @Test
    fun `set_timer defaults the label to timer`() =
        runTest {
            val bus = EventBus()
            val haptics = FakeHaptics()
            val events = mutableListOf<Event>()
            bus.events.onEach { events += it }.launchIn(backgroundScope)
            val tools = clockTools(FixedClock(Instant.parse("2026-01-01T12:00:00Z")), haptics, bus, backgroundScope)
            val setTimer = tools.first { it.name == "set_timer" }
            val reply = setTimer.call(buildJsonObject { put("seconds", 1) })
            assertEquals("Timer 'timer' set for 1 seconds.", reply)
            advanceTimeBy(1500)
            assertTrue(events.contains(TimerDone("timer")))
        }
}
