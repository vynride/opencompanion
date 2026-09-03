// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Opens connections to the configured endpoints ahead of a turn: idle turns let the
 * pooled connections expire, so without this the first request pays TLS again.
 * Fire-and-forget; status and errors are ignored and can never block a turn.
 */
class ConnectionPrewarmer(
    private val http: OkHttpClient,
    private val services: List<Service>,
    private val log: Log,
    private val scope: CoroutineScope,
) {
    fun prewarm() {
        for (service in services) {
            scope.launch {
                val request =
                    Request
                        .Builder()
                        .url(service.url("models"))
                        .head()
                        .build()
                runCatching { http.await(request).close() }
                    .onFailure { log.debug("companion", "prewarm failed: ${it.message}") }
            }
        }
    }
}
