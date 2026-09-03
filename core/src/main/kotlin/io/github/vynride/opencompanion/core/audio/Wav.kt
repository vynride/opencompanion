// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(
    val rateHz: Int,
    val samples: ShortArray,
)

fun wavBytes(
    pcm: ShortArray,
    rateHz: Int,
): ByteArray {
    val data = pcm.toLittleEndianBytes()
    val buf = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
    buf
        .put("fmt ".toByteArray())
        .putInt(16)
        .putShort(1)
        .putShort(1)
    buf
        .putInt(rateHz)
        .putInt(rateHz * 2)
        .putShort(2)
        .putShort(16)
    buf.put("data".toByteArray()).putInt(data.size).put(data)
    return buf.array()
}

/** Reads 16-bit PCM WAV; only the first channel is kept. */
fun parseWav(bytes: ByteArray): WavData {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var pos = 12
    var rate = 0
    var channels = 1
    var bits = 16
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val size = buf.getInt(pos + 4)
        val body = pos + 8
        when (id) {
            "fmt " -> {
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            }

            "data" -> {
                require(bits == 16) { "only 16-bit WAV is supported" }
                val n = size / 2 / channels
                val samples = ShortArray(n) { buf.getShort(body + it * channels * 2) }
                return WavData(rate, samples)
            }
        }
        pos = body + size + (size and 1)
    }
    error("WAV has no data chunk")
}
