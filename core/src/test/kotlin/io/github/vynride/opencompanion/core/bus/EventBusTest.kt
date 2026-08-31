// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import io.github.vynride.opencompanion.core.state.State
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventBusTest {
    @Test
    fun `on filters by event type in publish order`() =
        runTest {
            val bus = EventBus()
            val seen = mutableListOf<Transcript>()
            val collector = launch { bus.on<Transcript>().take(2).toList(seen) }
            bus.publish(Wake)
            bus.publish(Transcript("one"))
            bus.publish(StateChanged(State.IDLE))
            bus.publish(Transcript("two"))
            collector.join()
            assertEquals(listOf(Transcript("one"), Transcript("two")), seen)
        }

    @Test
    fun `publish does not block when nobody listens`() =
        runTest {
            val bus = EventBus()
            repeat(1000) { bus.publish(Mouth(0.5f)) }
        }
}
