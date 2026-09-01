// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceAssemblerTest {
    @Test
    fun `a sentence completes only once the following whitespace arrives`() {
        val a = SentenceAssembler()
        assertEquals(emptyList(), a.push("Hello there"))
        assertEquals(emptyList(), a.push("."))
        assertEquals(listOf("Hello there."), a.push(" How"))
        assertEquals("How", a.flush())
    }

    @Test
    fun `one delta can complete several sentences`() {
        val a = SentenceAssembler()
        assertEquals(listOf("One.", "Two!"), a.push("One. Two! Three"))
        assertEquals("Three", a.flush())
    }

    @Test
    fun `question and ellipsis terminators split too`() {
        val a = SentenceAssembler()
        assertEquals(listOf("Really?", "Well…"), a.push("Really? Well… maybe"))
    }

    @Test
    fun `an ellipsis of dots does not split mid-run`() {
        val a = SentenceAssembler()
        assertEquals(listOf("Hmm..."), a.push("Hmm... right"))
        assertEquals("right", a.flush())
    }

    @Test
    fun `a decimal number is not a sentence boundary`() {
        val a = SentenceAssembler()
        assertEquals(emptyList(), a.push("It is 3.5 degrees"))
        assertEquals("It is 3.5 degrees", a.flush())
    }

    @Test
    fun `tiny fragments ride along with the next sentence`() {
        val a = SentenceAssembler()
        assertEquals(listOf(". Yes."), a.push(". Yes. "))
    }

    @Test
    fun `flush empties the assembler`() {
        val a = SentenceAssembler()
        a.push("Leftover text")
        assertEquals("Leftover text", a.flush())
        assertEquals("", a.flush())
    }

    @Test
    fun `flush keeps a trailing terminator that never saw whitespace`() {
        val a = SentenceAssembler()
        assertEquals(emptyList(), a.push("Sure thing."))
        assertEquals("Sure thing.", a.flush())
    }
}
