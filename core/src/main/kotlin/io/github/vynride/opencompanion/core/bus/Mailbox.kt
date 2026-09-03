// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import io.github.vynride.opencompanion.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Bridges a bus flow to a handler through an unbounded queue. The feeder subscribes eagerly and
 * only offers, so a handler that publishes while it works can never stall the bus it reads from.
 */
class Mailbox<T>(
    private val scope: CoroutineScope,
    private val source: Flow<T>,
    private val log: Log,
    private val tag: String,
    private val handle: suspend (T) -> Unit,
) {
    private val queue = Channel<T>(Channel.UNLIMITED)
    private var feeder: Job? = null
    private var worker: Job? = null

    fun start() {
        feeder = scope.launch(start = CoroutineStart.UNDISPATCHED) { source.collect { queue.trySend(it) } }
        worker =
            scope.launch {
                for (event in queue) {
                    runCatching { handle(event) }.onFailure { e -> log.error(tag, "handler failed", e) }
                }
            }
    }

    fun stop() {
        feeder?.cancel()
        worker?.cancel()
        queue.close()
    }
}
