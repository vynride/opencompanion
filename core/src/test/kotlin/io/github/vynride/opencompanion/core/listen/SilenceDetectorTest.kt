// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.listen

import io.github.vynride.opencompanion.core.vad.Vad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceDetectorTest {
    private class ScriptedVad(
        vararg answers: Boolean,
    ) : Vad {
        private val q = ArrayDeque(answers.toList())

        override fun isSpeech(frame: ShortArray): Boolean = q.removeFirstOrNull() ?: false
    }

    private val frame = ShortArray(1280)

    @Test
    fun `stops after trailing silence once speech started`() {
        val d = SilenceDetector(ScriptedVad(true, true, false, false), silenceMs = 160, maxMs = 8000, minMs = 80)
        assertFalse(d.feed(frame))
        assertFalse(d.feed(frame))
        assertFalse(d.feed(frame))
        assertTrue(d.feed(frame))
        assertTrue(d.hasEnough())
        assertEquals(4 * 1280, d.pcm().size)
    }

    @Test
    fun `silence before any speech does not stop`() {
        val d = SilenceDetector(ScriptedVad(), silenceMs = 160, maxMs = 8000, minMs = 80)
        repeat(5) { assertFalse(d.feed(frame)) }
        assertFalse(d.hasEnough())
    }

    @Test
    fun `hard cap stops even mid speech`() {
        val d = SilenceDetector(ScriptedVad(true, true, true), silenceMs = 700, maxMs = 240, minMs = 80)
        assertFalse(d.feed(frame))
        assertFalse(d.feed(frame))
        assertTrue(d.feed(frame))
    }

    @Test
    fun `onset timeout stops a silent follow-up`() {
        val d = SilenceDetector(ScriptedVad(), silenceMs = 700, maxMs = 8000, minMs = 80, onsetMs = 160)
        assertFalse(d.feed(frame))
        assertTrue(d.feed(frame))
        assertFalse(d.hasEnough())
    }
}
