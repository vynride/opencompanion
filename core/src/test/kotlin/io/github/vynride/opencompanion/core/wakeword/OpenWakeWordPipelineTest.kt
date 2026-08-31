// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.wakeword

import io.github.vynride.opencompanion.core.RepoModelStore
import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.audio.FrameBuffer
import io.github.vynride.opencompanion.core.audio.Resampler
import io.github.vynride.opencompanion.core.audio.parseWav
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenWakeWordPipelineTest {
    private val store = RepoModelStore()

    private fun keywordFile(): String? = Path.of("..", "models", "wakeword").takeIf { Files.isDirectory(it) }?.let { dir ->
        Files.list(dir).use { s -> s.filter { it.extension == "onnx" }.findFirst().orElse(null) }?.let { "wakeword/${it.name}" }
    }

    @Test
    fun `features stream and scores stay in range on silence`() {
        val kw = keywordFile()
        assumeTrue(kw != null && store.has("melspectrogram.onnx") && store.has("embedding_model.onnx"))
        loadOpenWakeWord(store, kw!!).use { p ->
            repeat(40) {
                val s = p.score(ShortArray(FRAME_SAMPLES))
                assertTrue(s in 0f..1f, "score $s")
            }
        }
    }

    @Test
    fun `reset gives the same score again for the same audio`() {
        val kw = keywordFile()
        assumeTrue(kw != null && store.has("melspectrogram.onnx"))
        loadOpenWakeWord(store, kw!!).use { p ->
            val frames = List(30) { i -> ShortArray(FRAME_SAMPLES) { ((i * 37 + it * 91) % 2000 - 1000).toShort() } }
            val first = frames.map { p.score(it) }
            p.reset()
            val second = frames.map { p.score(it) }
            assertEquals(first, second)
        }
    }

    @Test
    fun `the wake word fixture triggers and silence does not`() {
        val kw = keywordFile()
        val wav = javaClass.getResource("/wakeword-16k.wav")
        assumeTrue(kw != null && wav != null && store.has("melspectrogram.onnx"))
        loadOpenWakeWord(store, kw!!).use { p ->
            val data = parseWav(wav!!.readBytes())
            val samples = Resampler(data.rateHz, 16000).process(data.samples)
            val peak = FrameBuffer(FRAME_SAMPLES).push(samples).maxOf { p.score(it) }
            assertTrue(peak >= 0.5f, "peak score $peak")
            p.reset()
            val quiet = (1..40).maxOf { p.score(ShortArray(FRAME_SAMPLES)) }
            assertTrue(quiet < 0.2f, "silence score $quiet")
        }
    }
}
