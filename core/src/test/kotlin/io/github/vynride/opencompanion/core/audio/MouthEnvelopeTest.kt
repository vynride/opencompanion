// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouthEnvelopeTest {
    @Test
    fun `envelope is normalised by the loudest window`() {
        val quiet = ShortArray(800) { 100 }
        val loud = ShortArray(800) { 10000 }
        val env = envelope(wavBytes(quiet + loud, 16000), rateHz = 20)
        assertEquals(2, env.size)
        assertEquals(1f, env[1])
        assertTrue(env[0] in 0.005f..0.02f)
    }

    @Test
    fun `silent wav gives zeros`() {
        assertEquals(listOf(0f, 0f), envelope(wavBytes(ShortArray(1600), 16000), rateHz = 20))
    }

    @Test
    fun `streaming mouth emits one level per window with a running peak`() {
        val m = StreamingMouth(rateHz = 20, pcmRate = 24000)
        val window = 1200
        val loud = ShortArray(window) { 12000 }.toLittleEndianBytes()
        val half = ShortArray(window) { 6000 }.toLittleEndianBytes()
        assertEquals(listOf(1f), m.push(loud))
        assertEquals(listOf(0.5f), m.push(half))
        assertEquals(emptyList(), m.push(half.copyOf(100)))
        assertEquals(1, m.flush().size)
    }
}
