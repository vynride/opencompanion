// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer

object Onnx {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
}

/** One ONNX session with the small set of tensor helpers the pipelines need. */
class OnnxModel(
    bytes: ByteArray,
    threads: Int = 1,
) : AutoCloseable {
    private val session: OrtSession =
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(threads)
            Onnx.env.createSession(bytes, opts)
        }

    val inputNames: List<String> = session.inputNames.toList()

    fun inputShape(name: String): LongArray = (session.inputInfo.getValue(name).info as TensorInfo).shape

    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result = session.run(inputs)

    fun floatTensor(
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(Onnx.env, FloatBuffer.wrap(data), shape)

    fun longScalar(value: Long): OnnxTensor = OnnxTensor.createTensor(Onnx.env, LongBuffer.wrap(longArrayOf(value)), longArrayOf())

    override fun close() = session.close()
}

/** Flattens a tensor result of any rank into a FloatArray. */
fun OrtSession.Result.floats(index: Int = 0): FloatArray {
    val value = this[index].value
    val out = ArrayList<Float>()

    fun walk(v: Any) {
        when (v) {
            is FloatArray -> v.forEach { out += it }
            is Array<*> -> v.forEach { walk(it!!) }
            else -> error("unexpected tensor element ${v::class}")
        }
    }
    walk(value)
    return out.toFloatArray()
}
