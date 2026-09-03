// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.api.await
import io.github.vynride.opencompanion.core.api.json
import io.github.vynride.opencompanion.core.api.requireSuccess
import io.github.vynride.opencompanion.core.config.SearchConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val EXA_URL = "https://api.exa.ai/search"
const val SEARCH_DESC = "Search the web and return the top results with a short text excerpt."

data class SearchResult(
    val title: String,
    val url: String,
    val text: String,
)

suspend fun exaSearch(
    http: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    query: String,
    maxResults: Int,
    maxChars: Int,
): List<SearchResult> {
    val body =
        buildJsonObject {
            put("query", query)
            put("numResults", maxResults)
            put("type", "auto")
            putJsonObject("contents") { putJsonObject("text") { put("maxCharacters", maxChars) } }
        }
    val request =
        Request
            .Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    val data = http.await(request).requireSuccess().use { json.parseToJsonElement(it.body.string()).jsonObject }
    val results = (data["results"] as? JsonArray) ?: JsonArray(emptyList())
    return results.map { x ->
        val obj = x.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: url
        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
        SearchResult(title, url, text)
    }
}

fun formatResults(
    results: List<SearchResult>,
    maxChars: Int,
): String {
    if (results.isEmpty()) return "No results."
    return results
        .mapIndexed { i, x ->
            val excerpt =
                x.text
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .take(maxChars)
            "${i + 1}. ${x.title} - ${x.url}\n$excerpt"
        }.joinToString("\n\n")
}

fun searchTool(
    http: OkHttpClient,
    config: SearchConfig,
    baseUrl: String = EXA_URL,
): List<Tool> {
    if (config.exaApiKey.isBlank()) {
        return listOf(disabledTool("web_search", SEARCH_DESC, "Exa API key missing"))
    }
    return listOf(
        Tool(
            "web_search",
            SEARCH_DESC,
            objectSchema("query" to stringParam(), required = listOf("query")),
        ) { args ->
            val query = args["query"]!!.jsonPrimitive.content
            try {
                formatResults(
                    exaSearch(http, baseUrl, config.exaApiKey, query, config.maxResults, config.maxChars),
                    config.maxChars,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "Search failed: ${e.message}"
            }
        },
    )
}
