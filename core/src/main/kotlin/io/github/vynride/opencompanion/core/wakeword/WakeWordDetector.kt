// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.wakeword

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.audio.FRAME_MS
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Wake
import io.github.vynride.opencompanion.core.config.WakeWordConfig
import io.github.vynride.opencompanion.core.vad.Vad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

fun monotonicNow(): Duration = origin.elapsedNow()

/**
 * Scores frames off the collector and publishes Wake on a hit. Frames are scored strictly in
 * order; inference only runs while armed and, with a gate, while speech was heard within gateHoldS.
 */
class WakeWordDetector(
    private val bus: EventBus,
    private val predictor: WakePredictor,
    private val config: WakeWordConfig,
    private val armed: () -> Boolean,
    private val gate: Vad?,
    private val log: Log,
    private val scope: CoroutineScope,
    private val clock: () -> Duration = ::monotonicNow,
) {
    private val queue = Channel<ShortArray>(config.maxQueue)
    private val preroll = ArrayDeque<ShortArray>()
    private val prerollFrames = maxOf(1, config.prerollMs / FRAME_MS)
    private var active = false
    private var lastSpeech = Duration.ZERO - 1000.seconds
    private var lastWake = Duration.ZERO - 1000.seconds
    private var failures = 0
    var framesDropped = 0
        private set
    private var jobs: List<Job> = emptyList()

    fun start(frames: SharedFlow<ShortArray>) {
        jobs =
            listOf(
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    frames.collect { if (queue.trySend(it).isFailure) framesDropped++ }
                },
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    for (frame in queue) process(frame)
                },
            )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
    }

    private fun park(frame: ShortArray): Float {
        active = false
        preroll.addLast(frame)
        while (preroll.size > prerollFrames) preroll.removeFirst()
        return 0f
    }

    suspend fun process(frame: ShortArray): Float {
        if (!armed()) return park(frame)
        val now = clock()
        if (gate != null) {
            if (gate.isSpeech(frame)) lastSpeech = now
            if (now - lastSpeech > config.gateHoldS.seconds) return park(frame)
        }
        val burst =
            if (active) {
                listOf(frame)
            } else {
                // Resuming after a gap: replay the pre-roll so the model has context.
                active = true
                (preroll + frame).also { preroll.clear() }
            }
        val score =
            try {
                withContext(Dispatchers.Default) {
                    if (burst.size > 1) predictor.reset()
                    burst.maxOf { predictor.score(it) }
                }
            } catch (e: Exception) {
                failures++
                if (failures == 1 || failures % 100 == 0) log.error("wakeword", "predict failed ($failures total)", e)
                return 0f
            }
        if (score >= config.threshold && now - lastWake >= config.refractoryS.seconds) {
            lastWake = now
            log.info("wakeword", "wake word (score ${"%.2f".format(score)})")
            bus.publish(Wake)
        }
        return score
    }
}
