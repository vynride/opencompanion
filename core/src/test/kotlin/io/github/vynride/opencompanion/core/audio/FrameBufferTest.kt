// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameBufferTest {
    @Test
    fun `emits whole frames and keeps the remainder`() {
        val fb = FrameBuffer(4)
        assertEquals(0, fb.push(shortArrayOf(1, 2, 3)).size)
        val frames = fb.push(shortArrayOf(4, 5, 6, 7, 8, 9))
        assertEquals(listOf(listOf<Short>(1, 2, 3, 4), listOf<Short>(5, 6, 7, 8)), frames.map { it.toList() })
        assertEquals(listOf(listOf<Short>(9, 10, 11, 12)), fb.push(shortArrayOf(10, 11, 12)).map { it.toList() })
    }
}
