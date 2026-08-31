// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.listen

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.Transcriber
import io.github.vynride.opencompanion.core.audio.SAMPLE_RATE
import io.github.vynride.opencompanion.core.audio.wavBytes
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.bus.Wake
import io.github.vynride.opencompanion.core.config.SttConfig
import io.github.vynride.opencompanion.core.vad.Vad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One turn per wake: buffer audio until silence, transcribe, publish the Transcript.
 * A PlaybackDone opens a short follow-up listen that needs no wake word; followupS <= 0 disables it.
 */
class Listener(
    private val bus: EventBus,
    private val frames: SharedFlow<ShortArray>,
    private val transcriber: Transcriber?,
    private val vad: Vad,
    private val stt: SttConfig,
    private val followupS: Double,
    private val log: Log,
    private val scope: CoroutineScope,
) {
    private var wiring: Job? = null
    private var turnInProgress = false
    private var followupPending = false

    fun start() {
        wiring =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                bus.events.collect { event ->
                    runCatching { handle(event) }.onFailure { e -> log.error("listen", "listener handler failed", e) }
                }
            }
    }

    fun stop() {
        wiring?.cancel()
    }

    suspend fun handle(event: Event) {
        when (event) {
            is Wake -> if (!turnInProgress) beginTurn(onsetMs = 0)
            is PlaybackDone -> onPlaybackDone()
            else -> Unit
        }
    }

    private fun onPlaybackDone() {
        if (followupS <= 0) return
        if (turnInProgress) followupPending = true else beginTurn(onsetMs = (followupS * 1000).toInt())
    }

    private fun beginTurn(onsetMs: Int) {
        turnInProgress = true
        scope.launch {
            try {
                recordAndTranscribe(onsetMs)
            } finally {
                turnInProgress = false
                if (followupPending) {
                    followupPending = false
                    beginTurn(onsetMs = (followupS * 1000).toInt())
                }
            }
        }
    }

    /** Collect frames until the detector says stop; empty when too short. */
    suspend fun record(onsetMs: Int): ShortArray {
        val det = SilenceDetector(vad, stt.silenceMs, stt.maxMs, stt.minMs, onsetMs)
        vad.reset()
        val finished =
            withTimeoutOrNull(stt.maxMs.milliseconds + 2.seconds) {
                frames.takeWhile { !det.feed(it) }.collect {}
            }
        if (finished == null) log.warn("listen", "no audio frames arrived while listening")
        return if (det.hasEnough()) det.pcm() else ShortArray(0)
    }

    private suspend fun recordAndTranscribe(onsetMs: Int) {
        val pcm = record(onsetMs)
        if (pcm.isEmpty()) {
            // A silent follow-up window publishes nothing.
            if (onsetMs == 0) bus.publish(Transcript(""))
            return
        }
        if (transcriber == null) {
            log.info("listen", "no transcriber configured; dropping ${pcm.size} samples")
            bus.publish(Transcript(""))
            return
        }
        val text =
            try {
                transcriber.transcribe(wavBytes(pcm, SAMPLE_RATE))
            } catch (e: IOException) {
                log.error("listen", "transcribe failed", e)
                bus.publish(Failure("transcription", e.message.orEmpty()))
                return
            }
        bus.publish(Transcript(text))
    }
}
