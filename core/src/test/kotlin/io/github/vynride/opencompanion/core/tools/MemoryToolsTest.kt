// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.FixedClock
import io.github.vynride.opencompanion.core.memory.Memory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryToolsTest {
    @TempDir
    lateinit var dir: Path

    private fun memory() = Memory(dir, 150, FixedClock(Instant.parse("2026-08-28T00:00:00Z")))

    @Test
    fun `remember saves a fact and confirms it`() =
        runTest {
            val tools = memoryTools(memory())
            val reply = tools.first { it.name == "remember" }.call(buildJsonObject { put("text", "keys on the hook") })
            assertTrue("Remembered" in reply)
            assertTrue("keys on the hook" in reply)
        }

    @Test
    fun `recall finds a remembered fact and reports nothing for a miss`() =
        runTest {
            val mem = memory()
            val tools = memoryTools(mem)
            tools.first { it.name == "remember" }.call(buildJsonObject { put("text", "keys on the hook") })
            val hit = tools.first { it.name == "recall" }.call(buildJsonObject { put("query", "keys") })
            assertTrue("keys on the hook" in hit)
            val miss = tools.first { it.name == "recall" }.call(buildJsonObject { put("query", "zebra") })
            assertTrue("Nothing" in miss)
        }
}
