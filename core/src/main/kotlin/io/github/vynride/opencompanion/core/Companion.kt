// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import io.github.vynride.opencompanion.core.api.OpenAiSpeechSynth
import io.github.vynride.opencompanion.core.api.OpenAiTranscriber
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.config.CompanionConfig
import io.github.vynride.opencompanion.core.config.ServiceKind
import io.github.vynride.opencompanion.core.config.service
import io.github.vynride.opencompanion.core.listen.Listener
import io.github.vynride.opencompanion.core.memory.Memory
import io.github.vynride.opencompanion.core.ports.AudioInput
import io.github.vynride.opencompanion.core.ports.AudioOutput
import io.github.vynride.opencompanion.core.ports.Camera
import io.github.vynride.opencompanion.core.ports.Clipboard
import io.github.vynride.opencompanion.core.ports.Clock
import io.github.vynride.opencompanion.core.ports.Haptics
import io.github.vynride.opencompanion.core.ports.ModelStore
import io.github.vynride.opencompanion.core.ports.Notifications
import io.github.vynride.opencompanion.core.ports.Screen
import io.github.vynride.opencompanion.core.ports.Sensors
import io.github.vynride.opencompanion.core.speech.Speaker
import io.github.vynride.opencompanion.core.state.State
import io.github.vynride.opencompanion.core.state.StateMachine
import io.github.vynride.opencompanion.core.vad.EnergyVad
import io.github.vynride.opencompanion.core.vad.Vad
import io.github.vynride.opencompanion.core.vad.loadSileroVad
import io.github.vynride.opencompanion.core.wakeword.WakePredictor
import io.github.vynride.opencompanion.core.wakeword.WakeWordDetector
import io.github.vynride.opencompanion.core.wakeword.loadOpenWakeWord
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.nio.file.Path

class Ports(
    val audioInput: AudioInput,
    val audioOutput: AudioOutput,
    val camera: Camera?,
    val sensors: Sensors,
    val screen: Screen,
    val haptics: Haptics,
    val notifications: Notifications,
    val clipboard: Clipboard,
    val models: ModelStore,
    val clock: Clock,
)

/**
 * Everything the companion is made of, wired onto one EventBus.
 *
 * Concurrency contract:
 * - the Companion owns its coroutine scope, derived from [parentScope]; [stop] cancels only that scope and leaves the
 *   parent running
 * - a stopped Companion is dead; build a fresh instance to run again
 * - unhandled coroutine failures are logged, never taken to the process
 * - bus collectors are subscribed before [start] returns
 */
class Companion(
    val config: CompanionConfig,
    private val ports: Ports,
    private val http: OkHttpClient,
    memoryDir: Path,
    private val log: Log,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> log.error("companion", "unhandled failure in a companion coroutine", e) },
        )

    val bus = EventBus()
    val memory = Memory(memoryDir, config.memory.maxFactsLines, ports.clock)
    private val stateMachine = StateMachine(bus, scope, config.state)
    val state: StateFlow<State> = stateMachine.state

    private val stopFns = ArrayList<() -> Unit>()
    private val closeables = ArrayList<AutoCloseable>()

    fun start() {
        stateMachine.start()
        stopFns += stateMachine::stop

        val listenVad = vad(config.stt.vadThreshold)
        val transcriber = config.service(ServiceKind.TRANSCRIBE)?.let { OpenAiTranscriber(http, it, config.stt.language, log) }
        if (transcriber == null) log.warn("companion", "transcription disabled: set the transcribe model and api key")
        val listener =
            Listener(bus, ports.audioInput.frames, transcriber, listenVad, config.stt, config.state.followupS, log, scope)
        listener.start()
        stopFns += listener::stop

        val synth = config.service(ServiceKind.TTS)?.let { OpenAiSpeechSynth(http, it, config.tts, log) }
        if (synth == null) log.warn("companion", "speech disabled: set the tts model and api key")
        val speaker = Speaker(bus, synth, ports.audioOutput, log, scope)
        speaker.start()
        stopFns += speaker::stop

        wakePredictor()?.let { predictor ->
            val gate = if (config.wakeWord.vadGate) vad(0.5f) else null
            val detector = WakeWordDetector(bus, predictor, config.wakeWord, stateMachine::isArmed, gate, log, scope)
            detector.start(ports.audioInput.frames)
            stopFns += detector::stop
        }

        ports.audioInput.start()
        stopFns += ports.audioInput::stop
        log.info("companion", "running")
    }

    fun stop() {
        stopFns.asReversed().forEach { it() }
        stopFns.clear()
        // Sessions are closed only once every coroutine has finished, so no inference is still in native code.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            closeables.forEach { c -> runCatching { c.close() } }
            closeables.clear()
        }
        scope.cancel()
        log.info("companion", "stopped")
    }

    private fun vad(threshold: Float): Vad =
        try {
            loadSileroVad(ports.models, threshold).also { closeables += it }
        } catch (e: Exception) {
            log.warn("companion", "silero vad unavailable; using energy vad", e)
            EnergyVad()
        }

    private fun wakePredictor(): WakePredictor? {
        val file = config.wakeWord.modelFile
        if (file.isBlank()) {
            log.warn("companion", "wake word disabled: no wake-word model selected")
            return null
        }
        return try {
            loadOpenWakeWord(ports.models, file).also { closeables += it }
        } catch (e: Exception) {
            log.error("companion", "wake word disabled", e)
            null
        }
    }
}
