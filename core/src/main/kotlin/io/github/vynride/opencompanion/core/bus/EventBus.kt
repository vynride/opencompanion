// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.yield

class EventBus(
    bufferSize: Int = 256,
) {
    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = bufferSize)

    val events: SharedFlow<Event> = flow.asSharedFlow()

    suspend fun publish(event: Event) {
        flow.emit(event)
        yield()
    }

    inline fun <reified T : Event> on(): Flow<T> = events.filterIsInstance<T>()
}
