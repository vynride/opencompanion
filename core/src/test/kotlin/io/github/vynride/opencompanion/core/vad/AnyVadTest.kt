// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnyVadTest {
    private class RecordingVad(
        private val answer: Boolean,
    ) : Vad {
        var feeds = 0
        var resets = 0

        override fun isSpeech(frame: ShortArray): Boolean {
            feeds++
            return answer
        }

        override fun reset() {
            resets++
        }
    }

    private val frame = ShortArray(1280)

    @Test
    fun `any member firing means speech and none means silence`() {
        assertTrue(AnyVad(RecordingVad(true), RecordingVad(false)).isSpeech(frame))
        assertTrue(AnyVad(RecordingVad(false), RecordingVad(true)).isSpeech(frame))
        assertTrue(AnyVad(RecordingVad(true), RecordingVad(true)).isSpeech(frame))
        assertFalse(AnyVad(RecordingVad(false), RecordingVad(false)).isSpeech(frame))
    }

    @Test
    fun `every member is fed even when the first already said speech`() {
        val first = RecordingVad(true)
        val second = RecordingVad(false)
        val vad = AnyVad(first, second)
        repeat(3) { vad.isSpeech(frame) }
        assertEquals(3, first.feeds)
        assertEquals(3, second.feeds)
    }

    @Test
    fun `reset fans out to every member`() {
        val first = RecordingVad(false)
        val second = RecordingVad(false)
        AnyVad(first, second).reset()
        assertEquals(1, first.resets)
        assertEquals(1, second.resets)
    }
}
