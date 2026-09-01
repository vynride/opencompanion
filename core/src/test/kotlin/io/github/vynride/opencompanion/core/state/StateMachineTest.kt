// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.state

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Sense
import io.github.vynride.opencompanion.core.bus.SenseKind
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.bus.TimerDone
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.bus.Wake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineTest {
    private class Harness(
        scope: TestScope,
        config: StateConfig,
    ) {
        val bus = EventBus()
        val changes = mutableListOf<State>()
        val sm = StateMachine(bus, scope.backgroundScope, config, Log.Stdout)

        init {
            bus.on<StateChanged>().onEach { changes += it.state }.launchIn(scope.backgroundScope)
        }
    }

    private fun config(
        sleep: Double = 0.05,
        err: Double = 0.02,
        happy: Double = 0.02,
        noticing: Double = 0.02,
        followup: Double = 0.0,
        listenTimeout: Double = 10.0,
        thinkingTimeout: Double = 60.0,
        speakingTimeout: Double = 120.0,
    ) = StateConfig(sleep, err, happy, noticing, followup, listenTimeout, thinkingTimeout, speakingTimeout)

    @Test
    fun `full turn`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config())
        h.sm.handle(Wake)
        h.sm.handle(Transcript("hello"))
        h.sm.handle(Reply("hi"))
        h.sm.handle(PlaybackDone)
        advanceTimeBy(1)
        assertEquals(listOf(State.LISTENING, State.THINKING, State.SPEAKING, State.IDLE), h.changes)
    }

    @Test
    fun `playback done opens a follow-up window then returns to idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 1.0, followup = 0.05))
        h.sm.handle(Reply("hi"))
        h.sm.handle(PlaybackDone)
        assertEquals(State.LISTENING, h.sm.state.value)
        advanceTimeBy(80)
        assertEquals(State.IDLE, h.sm.state.value)
        assertEquals(listOf(State.SPEAKING, State.LISTENING, State.IDLE), h.changes)
    }

    @Test
    fun `transcript in follow-up window cancels the timeout`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 1.0, followup = 0.05))
        h.sm.handle(Reply("hi"))
        h.sm.handle(PlaybackDone)
        h.sm.handle(Transcript("more please"))
        assertEquals(State.THINKING, h.sm.state.value)
        advanceTimeBy(80)
        assertEquals(State.THINKING, h.sm.state.value)
    }

    @Test
    fun `follow-up disabled goes straight to idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 1.0, followup = 0.0))
        h.sm.handle(Reply("hi"))
        h.sm.handle(PlaybackDone)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `empty transcript returns to idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config())
        h.sm.handle(Wake)
        h.sm.handle(Transcript(""))
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `idle falls asleep and wakes on wake word`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 0.05))
        h.sm.start()
        advanceTimeBy(60)
        assertEquals(State.SLEEPING, h.sm.state.value)
        assertEquals(listOf(State.SLEEPING), h.changes)
        h.sm.handle(Wake)
        assertEquals(State.LISTENING, h.sm.state.value)
    }

    @Test
    fun `error holds then resumes idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(err = 0.02))
        h.sm.handle(Failure("transcription", "boom"))
        assertEquals(State.ERROR, h.sm.state.value)
        advanceTimeBy(30)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `touch shows happy and returns to the previous state`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(happy = 0.02))
        h.sm.handle(Sense(SenseKind.TOUCH, 1f))
        assertEquals(State.HAPPY, h.sm.state.value)
        advanceTimeBy(30)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `proximity while idle shows noticing and rearms sleep`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 0.05, noticing = 0.02))
        h.sm.start()
        advanceTimeBy(40)
        h.sm.handle(Sense(SenseKind.PROXIMITY, 1f))
        assertEquals(State.NOTICING, h.sm.state.value)
        advanceTimeBy(30)
        assertEquals(State.IDLE, h.sm.state.value)
        advanceTimeBy(30)
        assertEquals(State.IDLE, h.sm.state.value)
        advanceTimeBy(30)
        assertEquals(State.SLEEPING, h.sm.state.value)
    }

    @Test
    fun `sense while thinking is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config())
        h.sm.handle(Wake)
        h.sm.handle(Transcript("hi"))
        h.sm.handle(Sense(SenseKind.LIGHT, 3f))
        assertEquals(State.THINKING, h.sm.state.value)
    }

    @Test
    fun `wake is ignored while speaking`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config())
        h.sm.handle(Reply("hi"))
        h.sm.handle(Wake)
        assertEquals(State.SPEAKING, h.sm.state.value)
        assertEquals(false, h.sm.isArmed())
    }

    @Test
    fun `listening times out to idle without a transcript`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(listenTimeout = 0.05))
        h.sm.handle(Wake)
        advanceTimeBy(80)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `thinking that never resolves times out to idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 10.0, thinkingTimeout = 0.05))
        h.sm.handle(Wake)
        h.sm.handle(Transcript("hi"))
        assertEquals(State.THINKING, h.sm.state.value)
        advanceTimeBy(80)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `speaking that never finishes times out to idle`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 10.0, speakingTimeout = 0.05))
        h.sm.handle(Reply("hi"))
        assertEquals(State.SPEAKING, h.sm.state.value)
        advanceTimeBy(80)
        assertEquals(State.IDLE, h.sm.state.value)
    }

    @Test
    fun `a normal transition cancels the watchdog`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(sleep = 10.0, speakingTimeout = 0.05))
        h.sm.handle(Reply("hi"))
        h.sm.handle(PlaybackDone)
        advanceTimeBy(80)
        assertEquals(listOf(State.SPEAKING, State.IDLE), h.changes)
    }

    @Test
    fun `timer done shows happy`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, config(happy = 0.02))
        h.sm.handle(TimerDone("tea"))
        assertEquals(State.HAPPY, h.sm.state.value)
    }
}
