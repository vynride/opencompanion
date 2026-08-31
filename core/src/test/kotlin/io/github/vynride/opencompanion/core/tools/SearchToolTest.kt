// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.api.json
import io.github.vynride.opencompanion.core.config.SearchConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchToolTest {
    private val exaResults =
        listOf(
            SearchResult("A", "https://example.com/a", "alpha ".repeat(400)),
            SearchResult("B", "https://example.com/b", "beta"),
        )

    private val exaBody =
        json
            .parseToJsonElement(
                """
                {"results": [
                  {"title": "A", "url": "https://example.com/a", "text": "${"alpha ".repeat(400)}"},
                  {"title": "B", "url": "https://example.com/b", "text": "beta"}
                ]}
                """.trimIndent(),
            ).jsonObject

    @Test
    fun `formatResults truncates the excerpt`() {
        val text = formatResults(exaResults, maxChars = 20)
        assertTrue(text.contains("1. A - https://example.com/a"))
        assertTrue(text.split("\n\n")[0].length < 80)
    }

    @Test
    fun `formatResults reports no results`() {
        assertEquals("No results.", formatResults(emptyList(), maxChars = 20))
    }

    @Test
    fun `web_search posts the expected body`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(body = exaBody.toString()))
                server.start()
                val config = SearchConfig(maxResults = 3, maxChars = 1500, exaApiKey = "k")
                val tools = searchTool(OkHttpClient(), config, server.url("/search").toString())
                val text = tools.first().call(buildJsonObject { put("query", "desk companions") })
                assertTrue(text.contains("https://example.com/b"))
                val req = server.takeRequest()
                assertEquals("k", req.headers["x-api-key"])
                val body = json.parseToJsonElement(req.body!!.utf8()).jsonObject
                assertEquals("desk companions", body["query"]!!.jsonPrimitive.content)
                assertEquals(3, body["numResults"]!!.jsonPrimitive.content.toInt())
                assertEquals(
                    json.parseToJsonElement("""{"text":{"maxCharacters":1500}}""").jsonObject,
                    body["contents"]!!.jsonObject,
                )
            }
        }

    @Test
    fun `web_search disabled without key`() =
        runTest {
            val tools = searchTool(OkHttpClient(), SearchConfig())
            assertEquals("web_search", tools.first().name)
            assertTrue(tools.first().call(buildJsonObject { put("query", "x") }).contains("disabled"))
        }

    @Test
    fun `web_search reports http failure`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(code = 503))
                server.start()
                val config = SearchConfig(exaApiKey = "k")
                val tools = searchTool(OkHttpClient(), config, server.url("/search").toString())
                val text = tools.first().call(buildJsonObject { put("query", "x") })
                assertTrue(text.startsWith("Search failed"))
            }
        }
}
