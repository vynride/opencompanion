// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.memory.Memory
import kotlinx.serialization.json.jsonPrimitive

fun memoryTools(memory: Memory): List<Tool> = listOf(
    Tool(
        "remember",
        "Save a fact to long-term memory.",
        objectSchema("text" to stringParam(), required = listOf("text")),
    ) { args ->
        val text = args["text"]!!.jsonPrimitive.content
        memory.remember(text)
        "Remembered: $text"
    },
    Tool(
        "recall",
        "Search long-term memory for a keyword.",
        objectSchema("query" to stringParam(), required = listOf("query")),
    ) { args ->
        val query = args["query"]!!.jsonPrimitive.content
        val hits = memory.recall(query)
        if (hits.isEmpty()) "Nothing remembered about '$query'." else hits.joinToString("\n")
    },
)
