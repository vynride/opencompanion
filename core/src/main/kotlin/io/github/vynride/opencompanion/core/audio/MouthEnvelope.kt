// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

/** RMS per window of a 16-bit mono WAV, normalised 0..1 by the loudest window. */
fun envelope(
    wav: ByteArray,
    rateHz: Int = 20,
): List<Float> {
    val data = parseWav(wav)
    val window = maxOf(1, data.rateHz / rateHz)
    val values = (0 until data.samples.size step window).map { rms(data.samples, it, minOf(it + window, data.samples.size)) }
    val peak = values.maxOrNull() ?: 0.0
    if (peak == 0.0) return values.map { 0f }
    return values.map { (it / peak).toFloat() }
}

/** Levels from raw PCM chunks as they flow, using a running peak with a floor so a quiet start doesn't saturate. */
class StreamingMouth(
    rateHz: Int,
    pcmRate: Int,
    private val peakFloor: Double = 6000.0,
) {
    private val windowBytes = 4 * maxOf(1, pcmRate / rateHz)
    private var buf = ByteArray(0)
    private var peak = peakFloor

    fun push(chunk: ByteArray): List<Float> {
        buf += chunk
        return drain(final = false)
    }

    fun flush(): List<Float> = drain(final = true)

    private fun drain(final: Boolean): List<Float> {
        val out = ArrayList<Float>()
        while (buf.size >= windowBytes || (final && buf.isNotEmpty())) {
            val n = minOf(windowBytes, buf.size)
            val r = rms(buf.copyOf(n).toShorts())
            buf = buf.copyOfRange(n, buf.size)
            peak = maxOf(peak, r)
            out += (r / peak).coerceAtMost(1.0).toFloat()
        }
        return out
    }
}
