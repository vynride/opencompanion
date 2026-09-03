// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Chat-format messages -> Responses API instructions and input items. */
fun toResponses(messages: List<JsonObject>): Pair<String, List<JsonObject>> {
    val instructions = ArrayList<String>()
    val items = ArrayList<JsonObject>()
    for (m in messages) {
        val content = m["content"]
        when (m.str("role")) {
            "system" -> {
                m.str("content")?.let { if (it.isNotEmpty()) instructions += it }
            }

            "user" -> {
                if (content is JsonArray) {
                    val text =
                        content
                            .firstOrNull { it.jsonObject.str("type") == "text" }
                            ?.jsonObject
                            ?.str("text")
                            .orEmpty()
                    val url =
                        content
                            .firstOrNull { it.jsonObject.str("type") == "image_url" }
                            ?.jsonObject
                            ?.get("image_url")
                            ?.jsonObject
                            ?.str("url")
                            .orEmpty()
                    items +=
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", text)
                                    },
                                )
                                if (url.isNotEmpty()) {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", url)
                                        },
                                    )
                                }
                            }
                        }
                } else {
                    items +=
                        buildJsonObject {
                            put("role", "user")
                            put("content", m.str("content").orEmpty())
                        }
                }
            }

            "assistant" -> {
                m.str("content")?.let {
                    if (it.isNotEmpty()) {
                        items +=
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", it)
                            }
                    }
                }
                (m["tool_calls"] as? JsonArray)?.forEach { tc ->
                    val fn = tc.jsonObject["function"]!!.jsonObject
                    items +=
                        buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tc.jsonObject.str("id").orEmpty())
                            put("name", fn.str("name").orEmpty())
                            put("arguments", fn.str("arguments").orEmpty())
                        }
                }
            }

            "tool" -> {
                items +=
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", m.str("tool_call_id").orEmpty())
                        put("output", m.str("content").orEmpty())
                    }
            }
        }
    }
    return instructions.joinToString("\n\n") to items
}

/** Responses API output -> a chat-format assistant message. */
fun parseResponses(data: JsonObject): JsonObject {
    val toolCalls = ArrayList<JsonObject>()
    val texts = ArrayList<String>()
    (data["output"] as? JsonArray)?.forEach { item ->
        val obj = item.jsonObject
        when (obj.str("type")) {
            "function_call" -> {
                toolCalls +=
                    buildJsonObject {
                        put("id", obj.str("call_id").orEmpty())
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", obj.str("name").orEmpty())
                                put("arguments", obj.str("arguments").orEmpty())
                            },
                        )
                    }
            }

            "message" -> {
                (obj["content"] as? JsonArray)?.forEach { part ->
                    if (part.jsonObject.str("type") == "output_text") texts += part.jsonObject.str("text").orEmpty()
                }
            }
        }
    }
    return buildJsonObject {
        put("role", "assistant")
        if (texts.isNotEmpty()) put("content", texts.joinToString("")) else put("content", kotlinx.serialization.json.JsonNull)
        if (toolCalls.isNotEmpty()) putJsonArray("tool_calls") { toolCalls.forEach { add(it) } }
    }
}

fun flattenTool(spec: JsonObject): JsonObject {
    val fn = spec["function"]!!.jsonObject
    return buildJsonObject {
        put("type", "function")
        put("name", fn.str("name").orEmpty())
        fn["description"]?.let { put("description", it) }
        fn["parameters"]?.let { put("parameters", it) }
    }
}
