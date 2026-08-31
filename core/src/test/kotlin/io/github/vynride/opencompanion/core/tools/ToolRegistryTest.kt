// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.ToolCall
import io.github.vynride.opencompanion.core.bus.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolRegistryTest {
    private fun echo() = Tool("echo", "Echo the input.", objectSchema("text" to stringParam(), required = listOf("text"))) { args ->
        "echo: " + args["text"]!!.jsonPrimitive.content
    }

    @Test
    fun `spec has the openai function shape`() {
        val spec = echo().spec()
        assertEquals("function", spec["type"]!!.jsonPrimitive.content)
        val fn = spec["function"]!!.jsonObject
        assertEquals("echo", fn["name"]!!.jsonPrimitive.content)
        assertEquals("object", fn["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `call runs the tool and publishes call and result events`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val calls = mutableListOf<ToolCall>()
        val results = mutableListOf<ToolResult>()
        bus.on<ToolCall>().onEach { calls += it }.launchIn(backgroundScope)
        bus.on<ToolResult>().onEach { results += it }.launchIn(backgroundScope)
        val reg = ToolRegistry(bus)
        reg.register(echo())
        val out = reg.call("echo", buildJsonObject { put("text", "hi") })
        assertEquals("echo: hi", out)
        assertEquals(listOf("echo"), calls.map { it.name })
        assertEquals(listOf("echo: hi"), results.map { it.result })
    }

    @Test
    fun `unknown tool and throwing tool return error strings`() = runTest {
        val reg = ToolRegistry(EventBus())
        assertEquals("Unknown tool: nope", reg.call("nope", buildJsonObject {}))
        reg.register(Tool("boom", "d", objectSchema()) { error("bad") })
        assertEquals("Tool boom failed: bad", reg.call("boom", buildJsonObject {}))
    }

    @Test
    fun `disabled tool explains itself`() = runTest {
        val reg = ToolRegistry(EventBus())
        reg.register(disabledTool("web_search", "Search.", "EXA_API_KEY missing"))
        assertEquals("web_search is disabled: EXA_API_KEY missing", reg.call("web_search", buildJsonObject {}))
    }
}
