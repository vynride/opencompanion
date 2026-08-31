// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.memory

import io.github.vynride.opencompanion.core.ports.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Plain-markdown memory: personality.md, facts.md, journal/YYYY-MM-DD.md. */
class Memory(
    private val root: Path,
    private val maxFactsLines: Int,
    private val clock: Clock,
) {
    private val factsPath: Path get() = root.resolve("facts.md")

    private fun read(path: Path): String = if (Files.exists(path)) Files.readString(path) else ""

    private fun append(
        path: Path,
        text: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, CREATE, APPEND)
    }

    fun today(): LocalDate = clock.now().atZone(clock.zone()).toLocalDate()

    fun personality(): String = read(root.resolve("personality.md"))

    fun facts(): String = read(factsPath)

    fun remember(text: String) {
        append(factsPath, "- ${text.trim()}\n")
        pruneFacts()
    }

    fun recall(query: String): List<String> {
        val q = query.lowercase()
        return facts().lines().filter { it.isNotBlank() && q in it.lowercase() }
    }

    /** Drop the oldest lines from facts.md until it is at the cap; returns how many. */
    fun pruneFacts(): Int {
        val lines = facts().lines().dropLastWhile { it.isEmpty() }
        val excess = lines.size - maxFactsLines
        if (excess <= 0) return 0
        Files.writeString(factsPath, lines.drop(excess).joinToString("\n") + "\n")
        return excess
    }

    fun journalPath(day: LocalDate): Path = root.resolve("journal").resolve("$day.md")

    fun journalAppend(
        role: String,
        text: String,
    ) {
        val time = clock.now().atZone(clock.zone()).format(DateTimeFormatter.ofPattern("HH:mm"))
        append(journalPath(today()), "- $time $role: ${text.trim()}\n")
    }

    fun journalFor(day: LocalDate): String = read(journalPath(day))

    fun journalToday(): String = journalFor(today())

    fun hasSummary(day: LocalDate): Boolean = "## $day" in facts()

    fun addDailySummary(
        day: LocalDate,
        bullets: List<String>,
    ) {
        val body = bullets.take(5).joinToString("\n") { "- " + it.trim().trimStart('-').trim() }
        append(factsPath, "\n## $day\n$body\n")
        pruneFacts()
    }
}
