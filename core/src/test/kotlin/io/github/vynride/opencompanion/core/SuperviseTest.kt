// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SuperviseTest {
    @Test
    fun `restarts a crashing block with growing backoff`() = runTest {
        var runs = 0
        val job =
            backgroundScope.supervise("x", Log.Stdout, minBackoff = 10.milliseconds, maxBackoff = 40.milliseconds) {
                runs++
                error("boom")
            }
        advanceTimeBy(1)
        assertEquals(1, runs)
        advanceTimeBy(10)
        assertEquals(2, runs)
        advanceTimeBy(20)
        assertEquals(3, runs)
        advanceTimeBy(40)
        assertEquals(4, runs)
        advanceTimeBy(40)
        assertEquals(5, runs)
        job.cancel()
    }

    @Test
    fun `a clean return ends supervision`() = runTest {
        var runs = 0
        val job = backgroundScope.supervise("x", Log.Stdout, minBackoff = 10.milliseconds) { runs++ }
        advanceTimeBy(100)
        assertEquals(1, runs)
        assertTrue(job.isCompleted)
    }
}
