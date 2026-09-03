// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test
    fun `retries transient statuses before the last attempt`() {
        assertTrue(RetryPolicy.shouldRetry(503, 1))
        assertTrue(RetryPolicy.shouldRetry(404, 2))
        assertFalse(RetryPolicy.shouldRetry(503, RetryPolicy.ATTEMPTS))
        assertFalse(RetryPolicy.shouldRetry(400, 1))
    }

    @Test
    fun `delay honours retry-after up to the cap`() {
        assertEquals(1.seconds, RetryPolicy.delay("1", 1))
        assertEquals(2500.milliseconds, RetryPolicy.delay("60", 1))
    }

    @Test
    fun `delay backs off linearly without retry-after`() {
        assertEquals(500.milliseconds, RetryPolicy.delay(null, 1))
        assertEquals(1000.milliseconds, RetryPolicy.delay("soon", 2))
    }
}
