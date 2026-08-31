// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * One shared event stream. Collectors are launched with [kotlinx.coroutines.CoroutineStart.UNDISPATCHED] so they are
 * subscribed before any publisher runs, and must hand work off immediately: blocking or long work inside `collect`
 * stalls every publisher once the buffer fills.
 */
class EventBus(
    bufferSize: Int = 256,
) {
    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = bufferSize)

    val events: SharedFlow<Event> = flow.asSharedFlow()

    suspend fun publish(event: Event) {
        flow.emit(event)
    }

    inline fun <reified T : Event> on(): Flow<T> = events.filterIsInstance<T>()
}
