// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

import io.github.vynride.opencompanion.core.audio.SAMPLE_RATE
import io.github.vynride.opencompanion.core.onnx.OnnxModel
import io.github.vynride.opencompanion.core.onnx.floats
import io.github.vynride.opencompanion.core.ports.ModelStore

private const val CHUNK = 512
private const val CONTEXT = 64
private const val STATE_SIZE = 2 * 1 * 128

/** Silero VAD v5 over 512-sample chunks; a frame is speech if any chunk in it is. */
class SileroVad(
    private val model: OnnxModel,
    private val threshold: Float = 0.5f,
) : Vad,
    AutoCloseable {
    private var state = FloatArray(STATE_SIZE)
    private var context = FloatArray(CONTEXT)
    private var pending = ShortArray(0)

    override fun close() = model.close()

    override fun reset() {
        state = FloatArray(STATE_SIZE)
        context = FloatArray(CONTEXT)
        pending = ShortArray(0)
    }

    override fun isSpeech(frame: ShortArray): Boolean {
        val all = pending + frame
        var speech = false
        var offset = 0
        while (all.size - offset >= CHUNK) {
            if (probability(all.copyOfRange(offset, offset + CHUNK)) >= threshold) speech = true
            offset += CHUNK
        }
        pending = all.copyOfRange(offset, all.size)
        return speech
    }

    /** Speech probability for exactly one 512-sample chunk. */
    fun probability(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        val input = FloatArray(CONTEXT + CHUNK)
        context.copyInto(input)
        for (i in 0 until CHUNK) input[CONTEXT + i] = chunk[i] / 32768f
        model.floatTensor(input, longArrayOf(1, (CONTEXT + CHUNK).toLong())).use { x ->
            model.floatTensor(state, longArrayOf(2, 1, 128)).use { s ->
                model.longScalar(SAMPLE_RATE.toLong()).use { sr ->
                    model.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { r ->
                        state = r.floats(1)
                        input.copyInto(context, 0, input.size - CONTEXT, input.size)
                        return r.floats(0)[0]
                    }
                }
            }
        }
    }
}

fun loadSileroVad(
    models: ModelStore,
    threshold: Float,
): SileroVad = SileroVad(OnnxModel(models.read("silero_vad.onnx")), threshold)
