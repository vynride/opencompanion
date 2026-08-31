// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.ChatApi
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

fun systemMessage(text: String): JsonObject =
    buildJsonObject {
        put("role", "system")
        put("content", text)
    }

fun userMessage(text: String): JsonObject =
    buildJsonObject {
        put("role", "user")
        put("content", text)
    }

fun assistantMessage(text: String): JsonObject =
    buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

fun toolMessage(
    callId: String,
    content: String,
): JsonObject =
    buildJsonObject {
        put("role", "tool")
        put("tool_call_id", callId)
        put("content", content)
    }

fun imageMessage(
    question: String,
    jpeg: ByteArray,
): JsonObject {
    val b64 = Base64.getEncoder().encodeToString(jpeg)
    return buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", question)
                },
            )
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$b64") }
                },
            )
        }
    }
}

/** Chat against an OpenAI-compatible endpoint; takes and returns chat-format messages either way. */
class ChatClient(
    private val http: OkHttpClient,
    private val service: Service,
    private val api: ChatApi,
    private val reasoningEffort: String,
    private val log: Log,
) {
    val model: String get() = service.model

    private fun request(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
    ): Pair<String, JsonObject> =
        if (api == ChatApi.RESPONSES) {
            val (instructions, items) = toResponses(messages)
            service.url("responses") to
                buildJsonObject {
                    put("model", model)
                    putJsonArray("input") { items.forEach { add(it) } }
                    if (instructions.isNotEmpty()) put("instructions", instructions)
                    if (!tools.isNullOrEmpty()) {
                        putJsonArray("tools") { tools.forEach { add(flattenTool(it)) } }
                        put("tool_choice", "auto")
                    }
                    if (reasoningEffort.isNotEmpty()) putJsonObject("reasoning") { put("effort", reasoningEffort) }
                }
        } else {
            service.url("chat/completions") to
                buildJsonObject {
                    put("model", model)
                    putJsonArray("messages") { messages.forEach { add(it) } }
                    if (!tools.isNullOrEmpty()) {
                        putJsonArray("tools") { tools.forEach { add(it) } }
                        put("tool_choice", "auto")
                    }
                    if (reasoningEffort.isNotEmpty()) put("reasoning_effort", reasoningEffort)
                }
        }

    private fun parse(data: JsonObject): JsonObject {
        if (api == ChatApi.RESPONSES) return parseResponses(data)
        val msg = (data["choices"] as JsonArray)[0].jsonObject["message"]!!.jsonObject
        return buildJsonObject {
            put("role", "assistant")
            put("content", msg["content"] ?: kotlinx.serialization.json.JsonNull)
            msg["tool_calls"]?.let { put("tool_calls", it) }
        }
    }

    suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>? = null,
    ): JsonObject {
        val (url, body) = request(messages, tools)
        for (attempt in 1..RetryPolicy.ATTEMPTS) {
            val req =
                Request
                    .Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .apply { service.headers().forEach { (k, v) -> header(k, v) } }
                    .build()
            val response =
                try {
                    http.await(req)
                } catch (e: java.io.IOException) {
                    if (attempt == RetryPolicy.ATTEMPTS) throw e
                    log.warn("chat", "network error, retrying", e)
                    delay(RetryPolicy.delay(null, attempt))
                    continue
                }
            if (RetryPolicy.shouldRetry(response.code, attempt)) {
                log.warn("chat", "chat ${response.code}, retrying ($attempt/${RetryPolicy.ATTEMPTS})")
                val retryAfter = response.header("retry-after")
                response.close()
                delay(RetryPolicy.delay(retryAfter, attempt))
                continue
            }
            response.requireSuccess().use { r ->
                return parse(json.parseToJsonElement(r.body.string()).jsonObject)
            }
        }
        error("unreachable")
    }
}
