// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.config.HostsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.measureTimedValue

fun parseHostPort(s: String): Pair<String, Int> {
    val host = s.substringBeforeLast(":", "")
    return if (host.isEmpty()) s to 22 else host to s.substringAfterLast(":").toInt()
}

fun hostTool(config: HostsConfig): List<Tool> {
    val known =
        config.hosts.keys
            .joinToString(", ")
            .ifEmpty { "none" }
    return listOf(
        Tool(
            "check_host",
            "Check whether a configured machine is reachable. Known names: $known.",
            objectSchema("name" to stringParam(), required = listOf("name")),
        ) { args ->
            val name = args["name"]!!.jsonPrimitive.content
            val target = config.hosts[name] ?: return@Tool "Unknown host '$name'. Known: $known."
            val (host, port) = parseHostPort(target)
            withContext(Dispatchers.IO) {
                val timed =
                    measureTimedValue {
                        try {
                            Socket().use { it.connect(InetSocketAddress(host, port), (config.timeoutS * 1000).toInt()) }
                            true
                        } catch (e: IOException) {
                            false
                        }
                    }
                if (timed.value) {
                    "$name is up (port $port answered in ${timed.duration.inWholeMilliseconds} ms)."
                } else {
                    "$name is down (no answer on port $port within ${config.timeoutS} s)."
                }
            }
        },
    )
}
