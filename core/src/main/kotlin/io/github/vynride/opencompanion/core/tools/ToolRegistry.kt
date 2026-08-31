// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.ToolCall
import io.github.vynride.opencompanion.core.bus.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class ToolRegistry(
    private val bus: EventBus,
) {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun names(): List<String> = tools.keys.toList()

    fun specs(): List<JsonObject> = tools.values.map { it.spec() }

    suspend fun call(
        name: String,
        args: JsonObject,
    ): String {
        val tool = tools[name] ?: return "Unknown tool: $name"
        bus.publish(ToolCall(name, args))
        val result =
            try {
                tool.call(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Tool $name failed: ${e.message}"
            }
        bus.publish(ToolResult(name, result))
        return result
    }
}
