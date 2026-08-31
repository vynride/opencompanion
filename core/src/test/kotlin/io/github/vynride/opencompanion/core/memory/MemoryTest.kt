// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.memory

import io.github.vynride.opencompanion.core.FixedClock
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FixedClock(Instant.parse("2026-03-04T10:30:00Z"))

    private fun memory(max: Int = 150) = Memory(dir, max, clock)

    @Test
    fun `remember appends and recall matches case-insensitively`() {
        val m = memory()
        m.remember("Likes green tea")
        m.remember("Cat is called Mo")
        assertEquals(listOf("- Likes green tea"), m.recall("TEA"))
    }

    @Test
    fun `facts prune keeps the newest lines`() {
        val m = memory(max = 2)
        m.remember("a")
        m.remember("b")
        m.remember("c")
        assertEquals("- b\n- c\n", m.facts())
    }

    @Test
    fun `journal appends with time and role under today's file`() {
        val m = memory()
        m.journalAppend("user", "hello")
        assertEquals("- 10:30 user: hello\n", m.journalToday())
        assertTrue(Files.exists(dir.resolve("journal/2026-03-04.md")))
    }

    @Test
    fun `daily summary is recorded once`() {
        val m = memory()
        val day = LocalDate.of(2026, 3, 3)
        m.addDailySummary(day, listOf("- one", "two"))
        assertTrue(m.hasSummary(day))
        assertEquals("\n## 2026-03-03\n- one\n- two\n", m.facts())
    }

    @Test
    fun `missing files read as empty`() {
        val m = memory()
        assertEquals("", m.personality())
        assertEquals("", m.facts())
        assertEquals("", m.journalFor(LocalDate.of(2020, 1, 1)))
    }
}
