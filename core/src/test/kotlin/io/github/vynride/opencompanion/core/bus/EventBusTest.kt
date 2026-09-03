// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.state.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on filters by event type in publish order`() = runTest(UnconfinedTestDispatcher()) {
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `publish does not suspend while the buffer has room`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(bufferSize = 8)
        val slow = launch { bus.events.collect { delay(1_000) } }
        val publishing = launch { repeat(8) { bus.publish(Mouth(0.5f)) } }
        assertTrue(publishing.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
        slow.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a mailboxed consumer never stalls a publisher`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(bufferSize = 8)
        val mailbox = Mailbox(backgroundScope, bus.events, Log.Stdout, "test") { delay(1_000) }
        mailbox.start()
        val publishing = launch { repeat(8 + 16) { bus.publish(Mouth(0.5f)) } }
        assertTrue(publishing.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
        mailbox.stop()
    }
}
