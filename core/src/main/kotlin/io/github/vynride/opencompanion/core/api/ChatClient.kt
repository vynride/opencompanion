// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.ChatApi
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.Base64

fun systemMessage(text: String): JsonObject = buildJsonObject {
    put("role", "system")
    put("content", text)
}

fun userMessage(text: String): JsonObject = buildJsonObject {
    put("role", "user")
    put("content", text)
}

fun assistantMessage(text: String): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", text)
}

fun toolMessage(
    callId: String,
    content: String,
): JsonObject = buildJsonObject {
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

/** A chat model that takes chat-format messages and returns one assistant message. */
interface Chat {
    val model: String

    /** With [onDelta] set the reply is streamed and each text fragment is passed on as it arrives. */
    suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>? = null,
        onDelta: ((String) -> Unit)? = null,
    ): JsonObject
}

/** Chat against an OpenAI-compatible endpoint; takes and returns chat-format messages either way. */
class ChatClient(
    private val http: OkHttpClient,
    private val service: Service,
    private val api: ChatApi,
    private val reasoningEffort: String,
    private val log: Log,
) : Chat {
    override val model: String get() = service.model

    private fun request(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
        stream: Boolean,
    ): Pair<String, JsonObject> = if (api == ChatApi.RESPONSES) {
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
                if (stream) put("stream", true)
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
                if (stream) put("stream", true)
            }
    }

    private fun parse(data: JsonObject): JsonObject {
        if (api == ChatApi.RESPONSES) return parseResponses(data)
        val msg = (data["choices"] as JsonArray)[0].jsonObject["message"]!!.jsonObject
        return buildJsonObject {
            put("role", "assistant")
            put("content", msg["content"] ?: JsonNull)
            msg["tool_calls"]?.let { put("tool_calls", it) }
        }
    }

    override suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
        onDelta: ((String) -> Unit)?,
    ): JsonObject {
        val startNs = System.nanoTime()
        if (onDelta != null) {
            try {
                return streamed(messages, tools, onDelta, startNs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("chat", "stream failed; falling back to a full completion", e)
            }
        }
        val (url, body) = request(messages, tools, stream = false)
        return send(url, body).use { r ->
            parse(json.parseToJsonElement(r.body.string()).jsonObject)
        }.also { log.debug("chat", "completion in ${sinceMs(startNs)}ms") }
    }

    private suspend fun streamed(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
        onDelta: (String) -> Unit,
        startNs: Long,
    ): JsonObject {
        val (url, body) = request(messages, tools, stream = true)
        val response = send(url, body)
        // SSE reads block the calling thread until the next event lands.
        return withContext(Dispatchers.IO) {
            response.use { r ->
                var first = true
                val timed: (String) -> Unit = {
                    if (first) {
                        first = false
                        log.debug("chat", "first delta in ${sinceMs(startNs)}ms")
                    }
                    onDelta(it)
                }
                val source = r.body.source()
                val msg = if (api == ChatApi.RESPONSES) readResponsesSse(source, timed) else readChatSse(source, timed)
                log.debug("chat", "stream done in ${sinceMs(startNs)}ms")
                msg
            }
        }
    }

    /** Post `body`, retrying per policy; returns the first successful response. */
    private suspend fun send(
        url: String,
        body: JsonObject,
    ): okhttp3.Response {
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
            return response.requireSuccess()
        }
        error("unreachable")
    }

    /** Responses SSE: forward text deltas, then parse the full object from `response.completed`. */
    private fun readResponsesSse(
        source: BufferedSource,
        onDelta: (String) -> Unit,
    ): JsonObject {
        var completed: JsonObject? = null
        source.forEachSseData { data ->
            val obj = json.parseToJsonElement(data).jsonObject
            when (obj.str("type")) {
                "response.output_text.delta" -> obj.str("delta")?.takeIf { it.isNotEmpty() }?.let(onDelta)
                "response.completed" -> completed = obj["response"]?.jsonObject
            }
        }
        return parseResponses(completed ?: error("stream ended without a completed response"))
    }

    /** Chat-completions SSE: accumulate delta fragments into one assistant message. */
    private fun readChatSse(
        source: BufferedSource,
        onDelta: (String) -> Unit,
    ): JsonObject {
        val content = StringBuilder()
        val calls = sortedMapOf<Int, ToolCallParts>()
        val sawData = source.forEachSseData { data ->
            val obj = json.parseToJsonElement(data).jsonObject
            val delta =
                (obj["choices"] as? JsonArray)
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("delta")
                    ?.jsonObject ?: return@forEachSseData
            delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                content.append(it)
                onDelta(it)
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { frag ->
                val f = frag.jsonObject
                val index = (f["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val parts = calls.getOrPut(index) { ToolCallParts() }
                f.str("id")?.let { parts.id = it }
                val fn = f["function"]?.jsonObject
                fn?.str("name")?.let { parts.name.append(it) }
                fn?.str("arguments")?.let { parts.arguments.append(it) }
            }
        }
        check(sawData) { "response is not an event stream" }
        return buildJsonObject {
            put("role", "assistant")
            if (content.isEmpty()) put("content", JsonNull) else put("content", content.toString())
            if (calls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    calls.values.forEach { parts ->
                        add(
                            buildJsonObject {
                                put("id", parts.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", parts.name.toString())
                                    put("arguments", parts.arguments.toString())
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private class ToolCallParts {
        var id = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Feeds each `data:` payload to [block] until `[DONE]` or EOF; false when no data line arrived. */
private fun BufferedSource.forEachSseData(block: (String) -> Unit): Boolean {
    var sawData = false
    while (true) {
        val line = readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        sawData = true
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break
        if (data.isNotEmpty()) block(data)
    }
    return sawData
}

private fun sinceMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
