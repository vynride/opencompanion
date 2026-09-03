// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.listen

import io.github.vynride.opencompanion.core.FakeAudioInput
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.Transcriber
import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.bus.Wake
import io.github.vynride.opencompanion.core.config.SttConfig
import io.github.vynride.opencompanion.core.vad.Vad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ListenerTest {
    private class ScriptedVad(
        vararg answers: Boolean,
    ) : Vad {
        private val q = ArrayDeque(answers.toList())

        override fun isSpeech(frame: ShortArray): Boolean = q.removeFirstOrNull() ?: false
    }

    private class Harness(
        scope: TestScope,
        vad: Vad,
        transcriber: Transcriber?,
        followupS: Double = 0.0,
    ) {
        val bus = EventBus()
        val input = FakeAudioInput()
        val transcripts = mutableListOf<String>()
        val failures = mutableListOf<Failure>()
        val listener =
            Listener(
                bus,
                input.frames,
                transcriber,
                vad,
                SttConfig(silenceMs = 160, maxMs = 800, minMs = 80, minSpeechMs = 160, leadInMs = 0),
                followupS,
                Log.Stdout,
                scope.backgroundScope,
            )

        init {
            bus.on<Transcript>().onEach { transcripts += it.text }.launchIn(scope.backgroundScope)
            bus.on<Failure>().onEach { failures += it }.launchIn(scope.backgroundScope)
            listener.start()
        }
    }

    private val frame = ShortArray(FRAME_SAMPLES) { 100 }

    @Test
    fun `wake records until silence and publishes the transcript`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(true, true, false, false), Transcriber { "hello there" })
        h.listener.handle(Wake)
        repeat(4) { h.input.emit(frame) }
        assertEquals(listOf("hello there"), h.transcripts)
    }

    @Test
    fun `too little speech publishes an empty transcript`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(), Transcriber { "never" })
        h.listener.handle(Wake)
        repeat(12) { h.input.emit(frame) }
        assertEquals(listOf(""), h.transcripts)
    }

    @Test
    fun `transcription failure publishes a failure event`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(true, true, false, false), Transcriber { throw IOException("down") })
        h.listener.handle(Wake)
        repeat(4) { h.input.emit(frame) }
        assertEquals(listOf("transcription"), h.failures.map { it.source })
        assertEquals(0, h.transcripts.size)
    }

    @Test
    fun `playback done opens a follow-up that stays quiet when nobody speaks`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(), Transcriber { "never" }, followupS = 0.16)
        h.listener.handle(PlaybackDone)
        repeat(3) { h.input.emit(frame) }
        assertEquals(0, h.transcripts.size)
    }

    @Test
    fun `a second wake during a turn is ignored`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val h =
            Harness(
                this,
                ScriptedVad(true, true, false, false, true, true, false, false),
                Transcriber {
                    calls++
                    "x"
                },
            )
        h.listener.handle(Wake)
        h.listener.handle(Wake)
        repeat(4) { h.input.emit(frame) }
        assertEquals(1, calls)
    }

    @Test
    fun `a majority-cjk transcript is dropped as a hallucination`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(true, true, false, false), Transcriber { "你好世界" })
        h.listener.handle(Wake)
        repeat(4) { h.input.emit(frame) }
        assertEquals(listOf(""), h.transcripts)
    }

    @Test
    fun `script filter drops pure cjk for a latin language`() {
        assertEquals(true, isUnexpectedScript("你好世界", "en"))
        assertEquals(true, isUnexpectedScript("こんにちは", "en"))
        assertEquals(true, isUnexpectedScript("안녕하세요", "en"))
    }

    @Test
    fun `script filter passes latin, mixed-minority and empty text`() {
        assertEquals(false, isUnexpectedScript("hello there", "en"))
        assertEquals(false, isUnexpectedScript("hello 世界 friend", "en"))
        assertEquals(false, isUnexpectedScript("", "en"))
        assertEquals(false, isUnexpectedScript("!? 42", "en"))
    }

    @Test
    fun `script filter is disabled for cjk languages`() {
        assertEquals(false, isUnexpectedScript("你好世界", "zh"))
        assertEquals(false, isUnexpectedScript("こんにちは", "ja"))
        assertEquals(false, isUnexpectedScript("안녕하세요", "ko"))
    }

    @Test
    fun `no transcriber drops audio with an empty transcript`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, ScriptedVad(true, true, false, false), null)
        h.listener.handle(Wake)
        repeat(4) { h.input.emit(frame) }
        assertEquals(listOf(""), h.transcripts)
    }
}
