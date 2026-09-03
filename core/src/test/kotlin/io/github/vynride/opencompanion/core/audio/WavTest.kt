// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WavTest {
    @Test
    fun `wav header describes 16-bit mono pcm and round trips`() {
        val pcm = shortArrayOf(0, 1000, -1000, 5)
        val wav = wavBytes(pcm, 16000)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val parsed = parseWav(wav)
        assertEquals(16000, parsed.rateHz)
        assertContentEquals(pcm, parsed.samples)
    }
}
