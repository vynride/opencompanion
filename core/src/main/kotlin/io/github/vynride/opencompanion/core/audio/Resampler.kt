// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

/** Linear-interpolation resampler for speech-band audio; keeps phase across calls. */
class Resampler(
    private val fromHz: Int,
    private val toHz: Int,
) {
    private val step = fromHz.toDouble() / toHz
    private var position = 0.0
    private var last: Short = 0
    private var isFirstCall = true

    fun process(input: ShortArray): ShortArray {
        if (fromHz == toHz) return input
        val out = ArrayList<Short>((input.size / step).toInt() + 1)
        if (isFirstCall && input.isNotEmpty()) {
            last = input[0]
            isFirstCall = false
        }

        // Index -1 is the last sample of the previous call.
        fun at(i: Int): Double = if (i < 0) last.toDouble() else input[i].toDouble()
        while (position < input.size) {
            val i = kotlin.math.floor(position).toInt()
            val frac = position - i
            val a = at(i - 1)
            val b = at(i)
            out += (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            position += step
        }
        position -= input.size
        if (input.isNotEmpty()) last = input.last()
        return out.toShortArray()
    }
}
