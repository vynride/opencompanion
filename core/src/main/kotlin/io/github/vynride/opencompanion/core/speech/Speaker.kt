// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.speech

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.PCM_RATE
import io.github.vynride.opencompanion.core.api.SpeechSynth
import io.github.vynride.opencompanion.core.audio.StreamingMouth
import io.github.vynride.opencompanion.core.audio.envelope
import io.github.vynride.opencompanion.core.bus.Caption
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.Mailbox
import io.github.vynride.opencompanion.core.bus.Mouth
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.ports.AudioOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Voices Reply/Say/Failure events. A Reply is streamed as PCM so audio starts before synthesis
 * finishes, with a buffered fallback; Say/Failure always take the buffered path. Publishes Mouth
 * levels for the face and PlaybackDone when a Reply ends.
 */
class Speaker(
    private val bus: EventBus,
    private val synth: SpeechSynth?,
    private val output: AudioOutput,
    private val log: Log,
    private val scope: CoroutineScope,
    private val rateHz: Int = 20,
) {
    private val mailbox = Mailbox(scope, bus.events, log, "tts", ::handle)

    fun start() {
        mailbox.start()
    }

    fun stop() {
        mailbox.stop()
    }

    suspend fun handle(event: Event) {
        when (event) {
            is Reply -> speak(event.text, endTurn = true)
            is Say -> speak(event.text, endTurn = false)
            is Failure -> speak("Sorry, ${event.source} is not responding.", endTurn = false)
            else -> Unit
        }
    }

    suspend fun speak(
        text: String,
        endTurn: Boolean,
    ) {
        try {
            val stripped = text.trim()
            if (synth == null) {
                log.info("tts", "speech disabled, would say: $stripped")
                return
            }
            if (stripped.isEmpty()) return
            if (endTurn) {
                bus.publish(Caption(stripped, captionSeconds(stripped)))
                if (speakStreamed(synth, stripped)) return
            }
            speakBuffered(synth, stripped)
        } finally {
            if (endTurn) bus.publish(PlaybackDone)
        }
    }

    /** True once streaming has run (even if it ended early); false if it could not start. */
    private suspend fun speakStreamed(
        synth: SpeechSynth,
        text: String,
    ): Boolean =
        coroutineScope {
            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            val producer =
                launch {
                    try {
                        synth.stream(text).collect { channel.send(it) }
                        channel.close()
                    } catch (e: IOException) {
                        channel.close(e)
                    }
                }
            val first =
                try {
                    channel.receive()
                } catch (e: ClosedReceiveChannelException) {
                    return@coroutineScope true
                } catch (e: IOException) {
                    log.error("tts", "speech stream failed for '${text.take(40)}'", e)
                    return@coroutineScope false
                }
            val mouth = StreamingMouth(rateHz, PCM_RATE)
            val chunks =
                flow {
                    emit(first)
                    try {
                        for (c in channel) emit(c)
                    } catch (e: IOException) {
                        log.warn("tts", "stream ended mid-utterance", e)
                    }
                }.onEach { chunk -> mouth.push(chunk).forEach { bus.publish(Mouth(it)) } }
            try {
                output.playPcm(PCM_RATE, chunks)
            } finally {
                mouth.flush().forEach { bus.publish(Mouth(it)) }
                bus.publish(Mouth(0f))
                producer.cancel()
            }
            true
        }

    /** Buffered fallback: synthesize the whole clip (WAV) then play it while replaying its envelope. */
    private suspend fun speakBuffered(
        synth: SpeechSynth,
        text: String,
    ) {
        val wav =
            try {
                synth.synthesize(text)
            } catch (e: IOException) {
                log.error("tts", "speech failed for '${text.take(40)}'", e)
                return
            }
        coroutineScope {
            val play = async { output.playWav(wav) }
            for (level in envelope(wav, rateHz)) {
                bus.publish(Mouth(level))
                delay((1.0 / rateHz).seconds)
            }
            bus.publish(Mouth(0f))
            play.await()
        }
    }
}
