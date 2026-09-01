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
import io.github.vynride.opencompanion.core.bus.ReplyDelta
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.ports.AudioOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private var assembler = SentenceAssembler()
    private var deltasSeen = false
    private var turn: TurnPipeline? = null

    fun start() {
        mailbox.start()
    }

    fun stop() {
        mailbox.stop()
        turn?.cancel()
        turn = null
    }

    suspend fun handle(event: Event) {
        when (event) {
            is ReplyDelta -> onDelta(event.text)

            is Reply -> onReply(event.text)

            is Say -> speak(event.text, endTurn = false)

            is Failure -> {
                // A failed turn ends without a Reply; voice what was already assembled,
                // drop the half-assembled tail, then apologize.
                finishTurn()
                speak("Sorry, ${event.source} is not responding.", endTurn = false)
            }

            else -> Unit
        }
    }

    private suspend fun onDelta(text: String) {
        deltasSeen = true
        for (sentence in assembler.push(text)) {
            val synth = synth
            if (synth == null) {
                log.info("tts", "speech disabled, would say: $sentence")
                continue
            }
            val pipeline = turn ?: TurnPipeline(synth).also { turn = it }
            pipeline.sentences.send(sentence)
        }
    }

    /** Voice the unspoken remainder of a streamed turn, or the whole text when nothing streamed. */
    private suspend fun onReply(text: String) {
        if (!deltasSeen) {
            speak(text, endTurn = true)
            return
        }
        try {
            val rest = assembler.flush()
            val pipeline = turn
            if (pipeline != null) {
                if (rest.isNotBlank()) pipeline.sentences.send(rest)
                pipeline.finish()
            } else if (rest.isNotBlank()) {
                speakSentence(rest)
            }
        } finally {
            resetTurn()
            bus.publish(PlaybackDone)
        }
    }

    private suspend fun finishTurn() {
        turn?.finish()
        resetTurn()
    }

    private fun resetTurn() {
        assembler = SentenceAssembler()
        deltasSeen = false
        turn = null
    }

    /**
     * One streamed turn's playback: sentences play strictly in order while the next one's
     * synthesis already downloads, so sentence boundaries do not pay TTS first-byte latency.
     * Cancelling the job cancels the in-flight prefetch with it.
     */
    private inner class TurnPipeline(
        private val synth: SpeechSynth,
    ) {
        val sentences = Channel<String>(Channel.UNLIMITED)
        private val job = scope.launch { run() }

        private suspend fun run() = coroutineScope {
            // Rendezvous: exactly one synthesis runs ahead of the sentence playing.
            val fetched = Channel<Fetch>()
            launch {
                for (text in sentences) fetched.send(startFetch(this, synth, text))
                fetched.close()
            }
            for (fetch in fetched) {
                bus.publish(Caption(fetch.text, captionSeconds(fetch.text)))
                if (!playStreamed(fetch)) speakBuffered(synth, fetch.text)
            }
        }

        suspend fun finish() {
            sentences.close()
            job.join()
        }

        fun cancel() {
            sentences.close()
            job.cancel()
        }
    }

    private class Fetch(
        val text: String,
        val chunks: Channel<ByteArray>,
        val producer: Job,
    )

    /** Begin downloading one utterance's PCM into a buffered channel. */
    private fun startFetch(
        scope: CoroutineScope,
        synth: SpeechSynth,
        text: String,
    ): Fetch {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val producer =
            scope.launch {
                try {
                    synth.stream(text).collect { chunks.send(it) }
                    chunks.close()
                } catch (e: IOException) {
                    chunks.close(e)
                }
            }
        return Fetch(text, chunks, producer)
    }

    /** Caption then voice one sentence of a streamed reply; the turn stays open. */
    private suspend fun speakSentence(text: String) {
        val stripped = text.trim()
        if (stripped.isEmpty()) return
        if (synth == null) {
            log.info("tts", "speech disabled, would say: $stripped")
            return
        }
        bus.publish(Caption(stripped, captionSeconds(stripped)))
        if (!speakStreamed(synth, stripped)) speakBuffered(synth, stripped)
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
    ): Boolean = coroutineScope {
        playStreamed(startFetch(this, synth, text))
    }

    /** Plays one fetched utterance; false when its stream never produced a byte. */
    private suspend fun playStreamed(fetch: Fetch): Boolean {
        val first =
            try {
                fetch.chunks.receive()
            } catch (e: ClosedReceiveChannelException) {
                return true
            } catch (e: IOException) {
                log.error("tts", "speech stream failed for '${fetch.text.take(40)}'", e)
                return false
            }
        val mouth = StreamingMouth(rateHz, PCM_RATE)
        val chunks =
            flow {
                emit(first)
                try {
                    for (c in fetch.chunks) emit(c)
                } catch (e: IOException) {
                    log.warn("tts", "stream ended mid-utterance", e)
                }
            }.onEach { chunk -> mouth.push(chunk).forEach { bus.publish(Mouth(it)) } }
        try {
            output.playPcm(PCM_RATE, chunks)
        } finally {
            mouth.flush().forEach { bus.publish(Mouth(it)) }
            bus.publish(Mouth(0f))
            fetch.producer.cancel()
        }
        return true
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
