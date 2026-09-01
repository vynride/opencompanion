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
    minSpeechMs: Int = 100,
    leadInMs: Int = 300,
) {
    private val silenceFrames = maxOf(1, silenceMs / FRAME_MS)
    private val maxFrames = maxOf(1, maxMs / FRAME_MS)
    private val minFrames = maxOf(1, minMs / FRAME_MS)
    private val onsetFrames = if (onsetMs > 0) maxOf(1, onsetMs / FRAME_MS) else 0

    // Round up: floor would let a single frame satisfy any bar up to twice the frame length.
    private val minSpeechFrames = maxOf(1, (minSpeechMs + FRAME_MS - 1) / FRAME_MS)

    // Wake turns only: a follow-up window has no wake tail, and its onset timeout
    // must count from the first frame.
    private val leadInFrames = if (onsetMs == 0) leadInMs / FRAME_MS else 0
    private val frames = ArrayList<ShortArray>()
    private var quietRun = 0
    private var speechFrames = 0
    var speechSeen = false
        private set

    /** Add a frame; true means recording should stop. */
    fun feed(frame: ShortArray): Boolean {
        frames += frame
        // The model VAD is stateful, so it sees every frame even when the verdict
        // is discarded: the wake phrase's own tail must not arm the silence stop.
        val voiced = vad.isSpeech(frame)
        if (frames.size <= leadInFrames) {
            quietRun = 0
        } else if (voiced) {
            speechSeen = true
            speechFrames++
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

    val voicedMs: Int get() = speechFrames * FRAME_MS
    val totalMs: Int get() = frames.size * FRAME_MS

    // A single VAD-positive blip arms the stop logic but is not worth an API call:
    // noise-only segments make STT models hallucinate text.
    fun hasEnough(): Boolean = speechSeen && frames.size >= minFrames && speechFrames >= minSpeechFrames
}
