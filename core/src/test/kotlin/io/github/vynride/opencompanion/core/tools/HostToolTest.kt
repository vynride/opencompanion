// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.config.HostsConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostToolTest {
    @Test
    fun `parseHostPort splits host and port and defaults to 22`() {
        assertEquals("192.0.2.10" to 22, parseHostPort("192.0.2.10"))
        assertEquals("192.0.2.10" to 2222, parseHostPort("192.0.2.10:2222"))
        assertEquals("example.invalid" to 22, parseHostPort("example.invalid"))
    }

    @Test
    fun `check_host reports up for a listening port`() = runTest {
        val server = ServerSocket(0)
        try {
            val port = server.localPort
            val hosts = HostsConfig(mapOf("box" to "127.0.0.1:$port"))
            val tool = hostTool(hosts).first { it.name == "check_host" }
            val reply = tool.call(buildJsonObject { put("name", "box") })
            assertTrue("box is up" in reply)
        } finally {
            server.close()
        }
    }

    @Test
    fun `check_host reports down for a closed port`() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()
        val hosts = HostsConfig(mapOf("box" to "127.0.0.1:$port"), timeoutS = 0.5)
        val tool = hostTool(hosts).first { it.name == "check_host" }
        val reply = tool.call(buildJsonObject { put("name", "box") })
        assertTrue("box is down" in reply)
    }

    @Test
    fun `check_host reports an unknown host with the known names list`() = runTest {
        val hosts = HostsConfig(mapOf("box" to "127.0.0.1:1"))
        val tool = hostTool(hosts).first { it.name == "check_host" }
        val reply = tool.call(buildJsonObject { put("name", "other") })
        assertTrue("Unknown host" in reply)
        assertTrue("box" in reply)
        assertTrue("box" in tool.description)
    }

    @Test
    fun `describes no known hosts when the map is empty`() {
        val tool = hostTool(HostsConfig()).first { it.name == "check_host" }
        assertTrue("none" in tool.description)
    }
}
