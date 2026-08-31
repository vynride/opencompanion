// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponsesTest {
    @Test
    fun `system messages become instructions and turns become items`() {
        val (instructions, items) =
            toResponses(
                listOf(
                    systemMessage("be brief"),
                    userMessage("hello"),
                    assistantMessage("hi"),
                ),
            )
        assertEquals("be brief", instructions)
        assertEquals(2, items.size)
        assertEquals("user", items[0]["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `image content splits into input_text and input_image`() {
        val (_, items) = toResponses(listOf(imageMessage("what is this", byteArrayOf(1))))
        val parts = items[0]["content"]!!.jsonArray
        assertEquals("input_text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant tool calls become function_call items and tool replies function_call_output`() {
        val assistant =
            buildJsonObject {
                put("role", "assistant")
                putJsonArray("tool_calls") {
                    add(
                        buildJsonObject {
                            put("id", "c1")
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", "get_time")
                                put("arguments", "{}")
                            }
                        },
                    )
                }
            }
        val (_, items) = toResponses(listOf(assistant, toolMessage("c1", "noon")))
        assertEquals("function_call", items[0]["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", items[1]["type"]!!.jsonPrimitive.content)
        assertEquals("c1", items[1]["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses output parses back to a chat assistant message`() {
        val data =
            json
                .parseToJsonElement(
                    """{"output":[
                    {"type":"function_call","call_id":"c1","name":"get_time","arguments":"{}"},
                    {"type":"message","content":[{"type":"output_text","text":"it is "},{"type":"output_text","text":"noon"}]}
                ]}""",
                ).jsonObject
        val msg = parseResponses(data)
        assertEquals("it is noon", msg["content"]!!.jsonPrimitive.content)
        assertEquals(
            "get_time",
            msg["tool_calls"]!!
                .jsonArray[0]
                .jsonObject["function"]!!
                .jsonObject["name"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `empty output has null content and no tool calls`() {
        val msg = parseResponses(buildJsonObject { putJsonArray("output") {} })
        assertNull(msg["content"]?.jsonPrimitive?.contentOrNull)
        assertNull(msg["tool_calls"])
    }
}
