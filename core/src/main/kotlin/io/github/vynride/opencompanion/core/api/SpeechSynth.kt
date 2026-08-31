// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import io.github.vynride.opencompanion.core.config.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Streamed PCM format: 24 kHz, 16-bit signed LE, mono, no header. */
const val PCM_RATE = 24000
private const val STREAM_CHUNK_BYTES = 4800

interface SpeechSynth {
    suspend fun synthesize(text: String): ByteArray

    /** Raw PCM chunks as they arrive. Retries apply to the initial response only. */
    fun stream(text: String): Flow<ByteArray>
}

/** Text -> audio via `audio/speech`. */
class OpenAiSpeechSynth(
    private val http: OkHttpClient,
    private val service: Service,
    private val tts: TtsConfig,
    private val log: Log,
) : SpeechSynth {
    private fun request(
        text: String,
        format: String,
    ): Request {
        val body =
            buildJsonObject {
                put("model", service.model)
                put("input", text)
                put("voice", tts.voice)
                put("speed", tts.speed)
                put("response_format", format)
                if (tts.instructions.isNotBlank()) put("instructions", tts.instructions)
            }
        return Request
            .Builder()
            .url(service.url("audio/speech"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { service.headers().forEach { (k, v) -> header(k, v) } }
            .build()
    }

    private suspend fun open(
        text: String,
        format: String,
    ): okhttp3.Response {
        for (attempt in 1..RetryPolicy.ATTEMPTS) {
            val response = http.await(request(text, format))
            if (RetryPolicy.shouldRetry(response.code, attempt)) {
                log.warn("tts", "speech ${response.code}, retrying ($attempt/${RetryPolicy.ATTEMPTS})")
                val retryAfter = response.header("retry-after")
                response.close()
                delay(RetryPolicy.delay(retryAfter, attempt))
                continue
            }
            return response.requireSuccess()
        }
        error("unreachable")
    }

    override suspend fun synthesize(text: String): ByteArray = open(text, "wav").use { it.body.bytes() }

    override fun stream(text: String): Flow<ByteArray> = flow {
        open(text, "pcm").use { response ->
            val source = response.body.source()
            val buf = ByteArray(STREAM_CHUNK_BYTES)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n <= 0) break
                emit(buf.copyOf(n))
            }
        }
    }.flowOn(Dispatchers.IO)
}
