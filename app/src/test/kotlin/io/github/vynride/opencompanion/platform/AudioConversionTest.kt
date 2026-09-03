// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import io.github.vynride.opencompanion.core.audio.Resampler
import io.github.vynride.opencompanion.core.audio.toLittleEndianBytes
import io.github.vynride.opencompanion.core.audio.toShorts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

class AudioConversionTest {
    @Test
    fun `duplicates every sample into both channels`() {
        val mono = shortArrayOf(1, -2, 3)
        val out = monoToStereoBytes(mono.toLittleEndianBytes(), Resampler(48000, 48000)).toShorts()
        assertContentEquals(shortArrayOf(1, 1, -2, -2, 3, 3), out)
    }

    @Test
    fun `resamples to the output rate keeping phase across chunks`() {
        val resampler = Resampler(24000, 48000)
        val chunk = ShortArray(240) { 100 }.toLittleEndianBytes()
        val first = monoToStereoBytes(chunk, resampler)
        val second = monoToStereoBytes(chunk, resampler)
        // 240 samples in at half rate become 480 stereo frames of 4 bytes out.
        assertEquals(480 * 4, first.size)
        assertEquals(480 * 4, second.size)
        assertContentEquals(ShortArray(960) { 100 }, second.toShorts())
    }
}
