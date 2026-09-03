// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmTest {
    @Test
    fun `shorts round trip through little-endian bytes`() {
        val s = shortArrayOf(1, -2, 32767, -32768)
        assertContentEquals(s, s.toLittleEndianBytes().toShorts())
        assertContentEquals(byteArrayOf(1, 0, -2, -1), shortArrayOf(1, -2).toLittleEndianBytes())
    }

    @Test
    fun `rms of silence is zero and of a square wave is its amplitude`() {
        assertEquals(0.0, rms(ShortArray(100)))
        assertEquals(1000.0, rms(ShortArray(100) { if (it % 2 == 0) 1000 else -1000 }), 1e-9)
    }
}
