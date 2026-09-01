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
        val d = SilenceDetector(ScriptedVad(true, true, false, false), silenceMs = 160, maxMs = 8000, minMs = 80, minSpeechMs = 160)
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
    fun `a lone voiced blip is not enough to transcribe at the default bar`() {
        val d = SilenceDetector(ScriptedVad(true), silenceMs = 160, maxMs = 8000, minMs = 80)
        assertFalse(d.feed(frame))
        assertFalse(d.feed(frame))
        assertTrue(d.feed(frame))
        assertFalse(d.hasEnough())
        assertEquals(80, d.voicedMs)
        assertEquals(3 * 80, d.totalMs)
    }

    @Test
    fun `sparse real speech passes the default voiced minimum`() {
        // The model VAD can mark only a fraction of voiced frames, so two hits must pass.
        val d = SilenceDetector(ScriptedVad(true, false, true, false, false), silenceMs = 160, maxMs = 8000, minMs = 80)
        repeat(4) { assertFalse(d.feed(frame)) }
        assertTrue(d.feed(frame))
        assertTrue(d.hasEnough())
        assertEquals(160, d.voicedMs)
    }

    @Test
    fun `onset timeout stops a silent follow-up`() {
        val d = SilenceDetector(ScriptedVad(), silenceMs = 700, maxMs = 8000, minMs = 80, onsetMs = 160)
        assertFalse(d.feed(frame))
        assertTrue(d.feed(frame))
        assertFalse(d.hasEnough())
    }
}
