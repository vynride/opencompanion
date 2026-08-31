// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.listen

import io.github.vynride.opencompanion.core.audio.FRAME_MS
import io.github.vynride.opencompanion.core.vad.Vad

/** Decides when a recording is over: trailing silence, the onset timeout, or the hard length cap. */
class SilenceDetector(
    private val vad: Vad,
    silenceMs: Int,
    maxMs: Int,
    minMs: Int,
    onsetMs: Int = 0,
) {
    private val silenceFrames = maxOf(1, silenceMs / FRAME_MS)
    private val maxFrames = maxOf(1, maxMs / FRAME_MS)
    private val minFrames = maxOf(1, minMs / FRAME_MS)
    private val onsetFrames = if (onsetMs > 0) maxOf(1, onsetMs / FRAME_MS) else 0
    private val frames = ArrayList<ShortArray>()
    private var quietRun = 0
    var speechSeen = false
        private set

    /** Add a frame; true means recording should stop. */
    fun feed(frame: ShortArray): Boolean {
        frames += frame
        if (vad.isSpeech(frame)) {
            speechSeen = true
            quietRun = 0
        } else {
            quietRun++
        }
        if (onsetFrames > 0 && !speechSeen && frames.size >= onsetFrames) return true
        val stopOnSilence = speechSeen && quietRun >= silenceFrames
        return stopOnSilence || frames.size >= maxFrames
    }

    fun pcm(): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var offset = 0
        for (f in frames) {
            f.copyInto(out, offset)
            offset += f.size
        }
        return out
    }

    fun hasEnough(): Boolean = speechSeen && frames.size >= minFrames
}
