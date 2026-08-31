// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.ChatApi
import io.github.vynride.opencompanion.core.config.Service
import io.github.vynride.opencompanion.core.tools.Tool
import io.github.vynride.opencompanion.core.tools.objectSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatClientTest {
    @Test
    fun `chat api posts messages and tools and parses the assistant message`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse(
                    body = """{"choices":[{"message":{"role":"assistant","content":"hi","tool_calls":[
                            {"id":"c1","type":"function","function":{"name":"get_time","arguments":"{}"}}]}}]}""",
                ),
            )
            server.start()
            val client =
                ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.CHAT, "low", Log.Stdout)
            val msg = client.chat(listOf(userMessage("hello")), listOf(Tool("get_time", "d", objectSchema()) { "" }.spec()))
            assertEquals("hi", msg["content"]!!.jsonPrimitive.content)
            assertEquals(1, msg["tool_calls"]!!.jsonArray.size)
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.url.encodedPath)
            val body = json.parseToJsonElement(req.body!!.utf8()).jsonObject
            assertEquals("m", body["model"]!!.jsonPrimitive.content)
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
            assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `responses api posts instructions and flattened tools`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""))
            server.start()
            val client =
                ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.RESPONSES, "", Log.Stdout)
            val msg =
                client.chat(
                    listOf(systemMessage("be brief"), userMessage("q")),
                    listOf(Tool("t", "d", objectSchema()) { "" }.spec()),
                )
            assertEquals("ok", msg["content"]!!.jsonPrimitive.content)
            val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("be brief", body["instructions"]!!.jsonPrimitive.content)
            assertEquals(
                "t",
                body["tools"]!!
                    .jsonArray[0]
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `retries a 503 then succeeds`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 503))
            server.enqueue(MockResponse(body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
            server.start()
            val client = ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.CHAT, "", Log.Stdout)
            assertEquals("ok", client.chat(listOf(userMessage("q")))["content"]!!.jsonPrimitive.content)
            assertEquals(2, server.requestCount)
        }
    }
}
