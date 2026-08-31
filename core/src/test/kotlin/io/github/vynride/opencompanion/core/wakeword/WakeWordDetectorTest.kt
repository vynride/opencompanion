// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.wakeword

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Wake
import io.github.vynride.opencompanion.core.config.WakeWordConfig
import io.github.vynride.opencompanion.core.vad.Vad
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WakeWordDetectorTest {
    private class ScriptedPredictor(
        private val scores: ArrayDeque<Float>,
    ) : WakePredictor {
        val seen = mutableListOf<ShortArray>()
        var resets = 0

        override fun score(frame: ShortArray): Float {
            seen += frame
            return scores.removeFirstOrNull() ?: 0f
        }

        override fun reset() {
            resets++
        }
    }

    private class ScriptedVad(
        private val answers: ArrayDeque<Boolean>,
    ) : Vad {
        override fun isSpeech(frame: ShortArray): Boolean = answers.removeFirstOrNull() ?: false
    }

    private fun frame(tag: Int) = ShortArray(FRAME_SAMPLES) { tag.toShort() }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        predictor: WakePredictor,
        vad: Vad? = null,
        armed: () -> Boolean = { true },
        config: WakeWordConfig = WakeWordConfig(threshold = 0.5f, refractoryS = 1.0, gateHoldS = 1.5, prerollMs = 240),
    ) {
        val bus = EventBus()
        var now: Duration = Duration.ZERO
        val wakes = mutableListOf<Wake>()
        val detector = WakeWordDetector(bus, predictor, config, armed, vad, Log.Stdout, scope) { now }

        init {
            bus.on<Wake>().onEach { wakes += it }.launchIn(scope)
        }
    }

    @Test
    fun `publishes wake above threshold then respects the refractory period`() = runTest {
        val h = Harness(backgroundScope, ScriptedPredictor(ArrayDeque(listOf(0.9f, 0.9f, 0.9f))))
        // Scoring runs on Dispatchers.Default, so a collected mutableList races with real
        // worker threads; await exactly the two expected events off the bus instead.
        val collected =
            async(start = CoroutineStart.UNDISPATCHED) {
                h.bus
                    .on<Wake>()
                    .take(2)
                    .toList()
            }
        h.detector.process(frame(1))
        h.now = 0.5.seconds
        h.detector.process(frame(2))
        h.now = 1.5.seconds
        h.detector.process(frame(3))
        assertEquals(2, collected.await().size)
    }

    @Test
    fun `does not run inference while not armed`() = runTest {
        val p = ScriptedPredictor(ArrayDeque(listOf(0.9f)))
        val h = Harness(backgroundScope, p, armed = { false })
        h.detector.process(frame(1))
        assertEquals(0, p.seen.size)
        assertEquals(0, h.wakes.size)
    }

    @Test
    fun `gate parks frames without speech and replays a pre-roll when speech resumes`() = runTest {
        val p = ScriptedPredictor(ArrayDeque(List(10) { 0f }))
        val vad = ScriptedVad(ArrayDeque(listOf(false, false, false, false, true)))
        val h = Harness(backgroundScope, p, vad)
        h.now = 10.seconds
        repeat(4) { h.detector.process(frame(it)) }
        assertEquals(0, p.seen.size)
        h.detector.process(frame(9))
        assertEquals(listOf(1, 2, 3, 9), p.seen.map { it[0].toInt() })
        assertEquals(1, p.resets)
    }

    @Test
    fun `gate keeps scoring during the hold after speech stops`() = runTest {
        val p = ScriptedPredictor(ArrayDeque(List(10) { 0f }))
        val vad = ScriptedVad(ArrayDeque(listOf(true, false, false)))
        val h = Harness(backgroundScope, p, vad)
        h.detector.process(frame(1))
        h.now = 1.0.seconds
        h.detector.process(frame(2))
        h.now = 2.0.seconds
        h.detector.process(frame(3))
        assertEquals(listOf(1, 2), p.seen.map { it[0].toInt() })
    }

    @Test
    fun `a failing predictor counts as no detection`() = runTest {
        val failing =
            object : WakePredictor {
                override fun score(frame: ShortArray): Float = error("nope")

                override fun reset() {}
            }
        val h = Harness(backgroundScope, failing)
        assertEquals(0f, h.detector.process(frame(1)))
        assertEquals(0, h.wakes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `drops and counts frames once the internal queue is full`() = runTest {
        val maxQueue = 4
        // Blocks the worker's single in-flight score() call for the whole test, so frames
        // queue up behind it instead of being drained.
        val block = CountDownLatch(1)
        val blockingPredictor =
            object : WakePredictor {
                override fun score(frame: ShortArray): Float {
                    block.await()
                    return 0f
                }

                override fun reset() {}
            }
        val bus = EventBus()
        val frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 16)
        // Launched on the test's own scope, not backgroundScope: its collector's resumption
        // after the first (UNDISPATCHED) suspension needs the standard test dispatcher to be
        // driven by advanceUntilIdle(), which backgroundScope's jobs do not reliably get here.
        val detector =
            WakeWordDetector(
                bus,
                blockingPredictor,
                WakeWordConfig(maxQueue = maxQueue),
                { true },
                null,
                Log.Stdout,
                this,
            ) { Duration.ZERO }
        try {
            detector.start(frames)
            // The worker takes the very first frame directly off the channel (no receiver
            // means no buffering for it), then blocks: that leaves room for exactly
            // `maxQueue` more frames to buffer, so of maxQueue + 4 sent, 3 are dropped.
            repeat(maxQueue + 4) { frames.emit(frame(it)) }
            advanceUntilIdle()
            assertEquals(3, detector.framesDropped)
        } finally {
            detector.stop()
            block.countDown()
        }
    }
}
