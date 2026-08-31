// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.face

import io.github.vynride.opencompanion.core.bus.Caption
import io.github.vynride.opencompanion.core.bus.Mouth
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.state.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaceMessagesTest {
    @Test
    fun `state mouth and captions serialise like the face expects`() {
        assertEquals("""{"type":"state","state":"thinking"}""", FaceMessages.forEvent(StateChanged(State.THINKING)))
        assertEquals("""{"type":"mouth","level":0.123}""", FaceMessages.forEvent(Mouth(0.12345f)))
        assertEquals(
            """{"type":"caption","who":"heard","text":"hi, there"}""",
            FaceMessages.forEvent(Transcript("hi — there")),
        )
        assertEquals(
            """{"type":"caption","who":"said","text":"hello","seconds":1.2}""",
            FaceMessages.forEvent(Caption("hello", 1.2)),
        )
    }

    @Test
    fun `other events do not reach the face`() {
        assertNull(FaceMessages.forEvent(PlaybackDone))
    }
}
