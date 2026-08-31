// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {
    @Test
    fun `48k to 16k keeps one third of the samples`() {
        val r = Resampler(48000, 16000)
        val out = r.process(ShortArray(4800) { 100 })
        assertEquals(1600, out.size)
        assertTrue(out.all { it == 100.toShort() })
    }

    @Test
    fun `same rate is a pass-through`() {
        val input = shortArrayOf(1, 2, 3)
        assertEquals(input.toList(), Resampler(16000, 16000).process(input).toList())
    }

    @Test
    fun `a tone keeps its frequency after resampling`() {
        val r = Resampler(48000, 16000)
        val tone = ShortArray(48000) { (10000 * sin(2 * PI * 440 * it / 48000)).toInt().toShort() }
        val out = r.process(tone)
        var crossings = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0) != (out[i] < 0)) crossings++
        assertEquals(880, crossings, 4)
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
        tolerance: Int,
    ) = assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected±$tolerance, got $actual")
}
