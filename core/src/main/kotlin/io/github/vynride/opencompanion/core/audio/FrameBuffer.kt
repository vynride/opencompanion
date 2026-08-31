// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.audio

/** Regroups arbitrary-length sample runs into fixed-size frames. */
class FrameBuffer(
    private val frameSamples: Int,
) {
    private var pending = ShortArray(0)

    fun push(samples: ShortArray): List<ShortArray> {
        val all = pending + samples
        val frames = ArrayList<ShortArray>(all.size / frameSamples)
        var offset = 0
        while (all.size - offset >= frameSamples) {
            frames += all.copyOfRange(offset, offset + frameSamples)
            offset += frameSamples
        }
        pending = all.copyOfRange(offset, all.size)
        return frames
    }
}
