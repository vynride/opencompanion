// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.onnx

import io.github.vynride.opencompanion.core.RepoModelStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class OnnxModelTest {
    @Test
    fun `melspectrogram model exposes its input and runs`() {
        val store = RepoModelStore()
        assumeTrue(store.has("melspectrogram.onnx"))
        OnnxModel(store.read("melspectrogram.onnx")).use { m ->
            val name = m.inputNames.single()
            val shape = m.inputShape(name)
            assertEquals(2, shape.size)
            m.floatTensor(FloatArray(1760), longArrayOf(1, 1760)).use { t ->
                m.run(mapOf(name to t)).use { r ->
                    val out = r[0].info as ai.onnxruntime.TensorInfo
                    assertEquals(32L, out.shape.last())
                    assertEquals(8L, out.shape[out.shape.size - 2])
                }
            }
        }
    }
}
