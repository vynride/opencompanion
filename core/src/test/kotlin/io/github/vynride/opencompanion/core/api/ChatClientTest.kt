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
    fun `chat api streams deltas and assembles the final message`() = runTest {
        MockWebServer().use { server ->
            val sse =
                """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo."}}]}

                data: [DONE]
                """.trimIndent()
            server.enqueue(MockResponse(body = sse))
            server.start()
            val client = ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.CHAT, "", Log.Stdout)
            val deltas = mutableListOf<String>()
            val msg = client.chat(listOf(userMessage("q")), onDelta = deltas::add)
            assertEquals(listOf("Hel", "lo."), deltas)
            assertEquals("Hello.", msg["content"]!!.jsonPrimitive.content)
            val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `chat api assembles streamed tool call fragments by index`() = runTest {
        MockWebServer().use { server ->
            val sse =
                """
                data: {"choices":[{"delta":{"content":"On it."}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"get_time","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"zo"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ne\":\"UTC\"}"}}]}}]}

                data: [DONE]
                """.trimIndent()
            server.enqueue(MockResponse(body = sse))
            server.start()
            val client = ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.CHAT, "", Log.Stdout)
            val msg = client.chat(listOf(userMessage("q")), onDelta = {})
            assertEquals("On it.", msg["content"]!!.jsonPrimitive.content)
            val call = msg["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("c1", call["id"]!!.jsonPrimitive.content)
            val fn = call["function"]!!.jsonObject
            assertEquals("get_time", fn["name"]!!.jsonPrimitive.content)
            assertEquals("""{"zone":"UTC"}""", fn["arguments"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `responses api streams deltas and takes the final object from the completed event`() = runTest {
        MockWebServer().use { server ->
            val sse =
                """
                data: {"type":"response.created"}

                data: {"type":"response.output_text.delta","delta":"Hi "}

                data: {"type":"response.output_text.delta","delta":"there."}

                data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"Hi there."}]},{"type":"function_call","call_id":"c9","name":"t","arguments":"{}"}]}}
                """.trimIndent()
            server.enqueue(MockResponse(body = sse))
            server.start()
            val client = ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.RESPONSES, "", Log.Stdout)
            val deltas = mutableListOf<String>()
            val msg = client.chat(listOf(userMessage("q")), onDelta = deltas::add)
            assertEquals(listOf("Hi ", "there."), deltas)
            assertEquals("Hi there.", msg["content"]!!.jsonPrimitive.content)
            assertEquals(
                "c9",
                msg["tool_calls"]!!
                    .jsonArray
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.content,
            )
            val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `an unparseable stream falls back to one non-streaming request`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = "data: {not json at all"))
            server.enqueue(MockResponse(body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
            server.start()
            val client = ChatClient(OkHttpClient(), Service(server.url("/v1").toString(), "k", "m"), ChatApi.CHAT, "", Log.Stdout)
            val msg = client.chat(listOf(userMessage("q")), onDelta = {})
            assertEquals("ok", msg["content"]!!.jsonPrimitive.content)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val second = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals(null, second["stream"])
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
