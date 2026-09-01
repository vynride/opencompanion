// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.speech

import io.github.vynride.opencompanion.core.FakeAudioOutput
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.SpeechSynth
import io.github.vynride.opencompanion.core.audio.toLittleEndianBytes
import io.github.vynride.opencompanion.core.audio.wavBytes
import io.github.vynride.opencompanion.core.bus.Caption
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.Mouth
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.ReplyDelta
import io.github.vynride.opencompanion.core.bus.Say
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerTest {
    private val pcm = ShortArray(4800) { 5000 }.toLittleEndianBytes()

    private class FakeSynth(
        private val streamChunks: List<ByteArray>? = null,
        private val streamFails: Boolean = false,
        private val wav: ByteArray = wavBytes(ShortArray(1600) { 3000 }, 16000),
    ) : SpeechSynth {
        var synthCalls = 0
        val streamedTexts = mutableListOf<String>()

        override suspend fun synthesize(text: String): ByteArray {
            synthCalls++
            return wav
        }

        override fun stream(text: String): Flow<ByteArray> = flow {
            if (streamFails) throw IOException("no stream")
            streamedTexts += text
            streamChunks?.forEach { emit(it) }
        }
    }

    private class Harness(
        scope: TestScope,
        synth: SpeechSynth?,
    ) {
        val bus = EventBus()
        val output = FakeAudioOutput()
        val events = mutableListOf<Event>()
        val speaker = Speaker(bus, synth, output, Log.Stdout, scope.backgroundScope)

        init {
            bus.events.onEach { events += it }.launchIn(scope.backgroundScope)
        }
    }

    @Test
    fun `reply streams pcm, captions, moves the mouth and ends the turn`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, FakeSynth(streamChunks = listOf(pcm, pcm)))
        h.speaker.speak("Hello there friend", endTurn = true)
        assertEquals(1, h.output.played.size)
        assertEquals(pcm.size * 2, h.output.played[0].size)
        assertTrue(h.events.first() is Caption)
        assertTrue(h.events.any { it is Mouth && it.level > 0f })
        assertEquals(Mouth(0f), h.events.filterIsInstance<Mouth>().last())
        assertTrue(h.events.last() is PlaybackDone)
    }

    @Test
    fun `streamed deltas speak sentence by sentence and reply closes the turn once`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth(streamChunks = listOf(pcm))
        val h = Harness(this, synth)
        h.speaker.handle(ReplyDelta("Hello there. How"))
        h.speaker.handle(ReplyDelta(" are you?"))
        h.speaker.handle(Reply("Hello there. How are you?"))
        assertEquals(listOf("Hello there.", "How are you?"), synth.streamedTexts)
        assertEquals(listOf("Hello there.", "How are you?"), h.events.filterIsInstance<Caption>().map { it.text })
        assertEquals(1, h.events.count { it is PlaybackDone })
        assertTrue(h.events.last() is PlaybackDone)
    }

    @Test
    fun `a fully spoken stream leaves no remainder and the next turn starts fresh`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth(streamChunks = listOf(pcm))
        val h = Harness(this, synth)
        h.speaker.handle(ReplyDelta("All done. "))
        h.speaker.handle(Reply("All done."))
        assertEquals(listOf("All done."), synth.streamedTexts)
        assertEquals(1, h.events.count { it is PlaybackDone })
        // The delta bookkeeping is gone: a plain Reply speaks its whole text again.
        h.speaker.handle(Reply("Bye now."))
        assertEquals(listOf("All done.", "Bye now."), synth.streamedTexts)
        assertEquals(2, h.events.count { it is PlaybackDone })
    }

    @Test
    fun `stream failure falls back to buffered synthesis`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth(streamFails = true)
        val h = Harness(this, synth)
        h.speaker.speak("hi", endTurn = true)
        assertEquals(1, synth.synthCalls)
        assertEquals(1, h.output.wavs.size)
        assertTrue(h.events.last() is PlaybackDone)
    }

    @Test
    fun `say uses the buffered path and does not end a turn`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth(streamChunks = listOf(pcm))
        val h = Harness(this, synth)
        h.speaker.handle(Say("One moment."))
        assertEquals(1, synth.synthCalls)
        assertTrue(h.events.none { it is PlaybackDone })
        assertTrue(h.events.none { it is Caption })
    }

    @Test
    fun `failure event is voiced as an apology`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth()
        val h = Harness(this, synth)
        h.speaker.handle(Failure("the chat model", "500"))
        assertEquals(1, synth.synthCalls)
    }

    @Test
    fun `without a synth a reply still ends the turn`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, null)
        h.speaker.handle(Reply("hi"))
        assertEquals(listOf<Event>(PlaybackDone), h.events)
    }

    @Test
    fun `a throwing handler does not kill the collector`() = runTest(UnconfinedTestDispatcher()) {
        val synth =
            object : SpeechSynth {
                var calls = 0

                override suspend fun synthesize(text: String): ByteArray {
                    calls++
                    require(text != "bad") { "malformed audio" }
                    return wavBytes(ShortArray(160) { 100 }, 16000)
                }

                override fun stream(text: String): Flow<ByteArray> = flow {}
            }
        val h = Harness(this, synth)
        h.speaker.start()
        h.bus.publish(Say("bad"))
        h.bus.publish(Say("good"))
        assertEquals(2, synth.calls)
        h.speaker.stop()
    }

    @Test
    fun `blank text is skipped`() = runTest(UnconfinedTestDispatcher()) {
        val synth = FakeSynth()
        val h = Harness(this, synth)
        h.speaker.speak("   ", endTurn = false)
        assertEquals(0, synth.synthCalls)
    }
}
