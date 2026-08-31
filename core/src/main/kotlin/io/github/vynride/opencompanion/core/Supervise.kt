// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Run `block`, restarting it with exponential backoff when it throws; a clean return ends supervision. */
fun CoroutineScope.supervise(
    name: String,
    log: Log,
    minBackoff: Duration = 1.seconds,
    maxBackoff: Duration = 30.seconds,
    healthyAfter: Duration = 60.seconds,
    block: suspend () -> Unit,
): Job =
    launch {
        var backoff = minBackoff
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            try {
                block()
                log.info(name, "finished; not restarting")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(name, "crashed", e)
            }
            if (started.elapsedNow() >= healthyAfter) backoff = minBackoff
            log.warn(name, "restarting in $backoff")
            delay(backoff)
            backoff = minOf(backoff * 2, maxBackoff)
        }
    }
