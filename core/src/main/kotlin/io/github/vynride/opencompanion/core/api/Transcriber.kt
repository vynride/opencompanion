// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

fun interface Transcriber {
    suspend fun transcribe(wav: ByteArray): String
}

/** WAV bytes -> text via `audio/transcriptions`. */
class OpenAiTranscriber(
    private val http: OkHttpClient,
    private val service: Service,
    private val language: String,
    private val log: Log,
) : Transcriber {
    override suspend fun transcribe(wav: ByteArray): String {
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", service.model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart("language", language)
                .build()
        for (attempt in 1..RetryPolicy.ATTEMPTS) {
            val request =
                Request
                    .Builder()
                    .url(service.url("audio/transcriptions"))
                    .post(body)
                    .apply {
                        service.headers().forEach { (k, v) -> header(k, v) }
                    }.build()
            val response = http.await(request)
            if (RetryPolicy.shouldRetry(response.code, attempt)) {
                log.warn("stt", "transcribe ${response.code}, retrying ($attempt/${RetryPolicy.ATTEMPTS})")
                val retryAfter = response.header("retry-after")
                response.close()
                delay(RetryPolicy.delay(retryAfter, attempt))
                continue
            }
            response.requireSuccess().use { r ->
                val text =
                    json
                        .parseToJsonElement(r.body.string())
                        .jsonObject["text"]
                        ?.jsonPrimitive
                        ?.content
                return text.orEmpty().trim()
            }
        }
        error("unreachable")
    }
}
