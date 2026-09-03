// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyVadTest {
    @Test
    fun `loud frames are speech and silence is not`() {
        val vad = EnergyVad(500.0)
        assertFalse(vad.isSpeech(ShortArray(1280)))
        assertTrue(vad.isSpeech(ShortArray(1280) { if (it % 2 == 0) 3000 else -3000 }))
        assertFalse(vad.isSpeech(ShortArray(0)))
    }
}
