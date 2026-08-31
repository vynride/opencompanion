// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Retry policy shared by the HTTP clients. */
object RetryPolicy {
    // 404 included: some hosted backends return it transiently while a model loads.
    private val retryStatuses = setOf(404, 429, 500, 502, 503)
    const val ATTEMPTS = 3
    private val backoffBase = 500.milliseconds
    private val maxDelay = 2500.milliseconds

    fun shouldRetry(
        status: Int,
        attempt: Int,
    ): Boolean = status in retryStatuses && attempt < ATTEMPTS

    fun delay(
        retryAfter: String?,
        attempt: Int,
    ): Duration {
        val header = retryAfter?.toDoubleOrNull()
        if (header != null) return minOf((header * 1000).toLong().milliseconds, maxDelay)
        return backoffBase * attempt
    }
}
