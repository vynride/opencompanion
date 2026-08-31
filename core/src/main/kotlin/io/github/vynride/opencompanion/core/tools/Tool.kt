// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val call: suspend (JsonObject) -> String,
) {
    fun spec(): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            }
        }
}

fun disabledTool(
    name: String,
    description: String,
    reason: String,
): Tool = Tool(name, description, objectSchema()) { "$name is disabled: $reason" }

fun objectSchema(
    vararg props: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }

fun stringParam(description: String = ""): JsonObject =
    buildJsonObject {
        put("type", "string")
        if (description.isNotEmpty()) put("description", description)
    }

fun numberParam(description: String = ""): JsonObject =
    buildJsonObject {
        put("type", "number")
        if (description.isNotEmpty()) put("description", description)
    }

fun enumParam(vararg values: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
