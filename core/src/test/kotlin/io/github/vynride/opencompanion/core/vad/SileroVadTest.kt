// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

import io.github.vynride.opencompanion.core.RepoModelStore
import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.audio.FrameBuffer
import io.github.vynride.opencompanion.core.audio.parseWav
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SileroVadTest {
    private val store = RepoModelStore()

    @Test
    fun `silence is not speech`() {
        assumeTrue(store.has("silero_vad.onnx"))
        val vad = loadSileroVad(store, 0.5f)
        repeat(10) { assertFalse(vad.isSpeech(ShortArray(FRAME_SAMPLES))) }
    }

    @Test
    fun `recorded speech is detected`() {
        assumeTrue(store.has("silero_vad.onnx"))
        val wav = javaClass.getResource("/speech-16k.wav")
        assumeTrue(wav != null)
        val data = parseWav(wav!!.readBytes())
        val vad = loadSileroVad(store, 0.5f)
        val hits = FrameBuffer(FRAME_SAMPLES).push(data.samples).count { vad.isSpeech(it) }
        assertTrue(hits > 0, "no speech frames detected")
    }

    @Test
    fun `reset makes the detector forget its state`() {
        assumeTrue(store.has("silero_vad.onnx"))
        val vad = loadSileroVad(store, 0.5f)
        val tone = ShortArray(FRAME_SAMPLES) { (8000 * kotlin.math.sin(it * 0.3)).toInt().toShort() }
        val first = vad.probability(tone.copyOf(512))
        vad.probability(tone.copyOf(512))
        vad.reset()
        val again = vad.probability(tone.copyOf(512))
        assertTrue(kotlin.math.abs(first - again) < 1e-4)
    }
}
