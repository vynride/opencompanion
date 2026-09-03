// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.wakeword

import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.onnx.OnnxModel
import io.github.vynride.opencompanion.core.onnx.floats
import io.github.vynride.opencompanion.core.ports.ModelStore

private const val MEL_CONTEXT_SAMPLES = 480
private const val MEL_WINDOW_SAMPLES = FRAME_SAMPLES + MEL_CONTEXT_SAMPLES
private const val MEL_BINS = 32
private const val MEL_FRAMES_PER_STEP = 8
private const val EMBEDDING_WINDOW = 76
private const val EMBEDDING_SIZE = 96

/** openWakeWord's melspectrogram -> speech embedding -> keyword classifier, streamed per 80 ms frame. */
class OpenWakeWordPipeline(
    private val mel: OnnxModel,
    private val embedding: OnnxModel,
    private val keyword: OnnxModel,
) : WakePredictor,
    AutoCloseable {
    private val melInput = mel.inputNames.single()
    private val embeddingInput = embedding.inputNames.single()
    private val keywordInput = keyword.inputNames.single()
    private val keywordWindow = keyword.inputShape(keywordInput)[1].toInt()

    private var raw = FloatArray(MEL_WINDOW_SAMPLES)
    private val melRows = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    override fun reset() {
        raw = FloatArray(MEL_WINDOW_SAMPLES)
        melRows.clear()
        embeddings.clear()
    }

    override fun score(frame: ShortArray): Float {
        require(frame.size == FRAME_SAMPLES) { "frame must be $FRAME_SAMPLES samples" }
        raw.copyInto(raw, 0, FRAME_SAMPLES, MEL_WINDOW_SAMPLES)
        for (i in 0 until FRAME_SAMPLES) raw[MEL_CONTEXT_SAMPLES + i] = frame[i].toFloat()
        appendMel()
        if (melRows.size < EMBEDDING_WINDOW) return 0f
        appendEmbedding()
        if (embeddings.size < keywordWindow) return 0f
        return classify()
    }

    private fun appendMel() {
        mel.floatTensor(raw, longArrayOf(1, MEL_WINDOW_SAMPLES.toLong())).use { t ->
            mel.run(mapOf(melInput to t)).use { r ->
                val out = r.floats()
                val frames = out.size / MEL_BINS
                for (f in frames - MEL_FRAMES_PER_STEP until frames) {
                    melRows.addLast(FloatArray(MEL_BINS) { out[f * MEL_BINS + it] / 10f + 2f })
                }
            }
        }
        while (melRows.size > EMBEDDING_WINDOW + MEL_FRAMES_PER_STEP) melRows.removeFirst()
    }

    private fun appendEmbedding() {
        val window = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
        melRows.takeLast(EMBEDDING_WINDOW).forEachIndexed { i, row -> row.copyInto(window, i * MEL_BINS) }
        embedding.floatTensor(window, longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
            embedding.run(mapOf(embeddingInput to t)).use { r -> embeddings.addLast(r.floats()) }
        }
        while (embeddings.size > keywordWindow) embeddings.removeFirst()
    }

    private fun classify(): Float {
        val input = FloatArray(keywordWindow * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, e -> e.copyInto(input, i * EMBEDDING_SIZE) }
        keyword.floatTensor(input, longArrayOf(1, keywordWindow.toLong(), EMBEDDING_SIZE.toLong())).use { t ->
            keyword.run(mapOf(keywordInput to t)).use { r -> return r.floats()[0].coerceIn(0f, 1f) }
        }
    }

    override fun close() {
        mel.close()
        embedding.close()
        keyword.close()
    }
}

fun loadOpenWakeWord(
    models: ModelStore,
    keywordFile: String,
): OpenWakeWordPipeline = OpenWakeWordPipeline(
    OnnxModel(models.read("melspectrogram.onnx")),
    OnnxModel(models.read("embedding_model.onnx")),
    OnnxModel(models.read(keywordFile)),
)
