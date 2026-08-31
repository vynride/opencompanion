// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000
const val FRAME_MS = 80
const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

fun ShortArray.toLittleEndianBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    buf.asShortBuffer().put(this)
    return buf.array()
}

fun ByteArray.toShorts(): ShortArray {
    val out = ShortArray(size / 2)
    ByteBuffer
        .wrap(this, 0, out.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asShortBuffer()
        .get(out)
    return out
}

fun rms(
    samples: ShortArray,
    from: Int = 0,
    to: Int = samples.size,
): Double {
    if (to <= from) return 0.0
    var acc = 0.0
    for (i in from until to) acc += samples[i].toDouble() * samples[i]
    return sqrt(acc / (to - from))
}
