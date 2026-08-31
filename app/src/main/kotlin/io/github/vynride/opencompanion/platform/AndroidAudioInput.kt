// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.audio.FRAME_SAMPLES
import io.github.vynride.opencompanion.core.audio.FrameBuffer
import io.github.vynride.opencompanion.core.audio.Resampler
import io.github.vynride.opencompanion.core.audio.SAMPLE_RATE
import io.github.vynride.opencompanion.core.ports.AudioInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.concurrent.thread

private val CANDIDATE_RATES = intArrayOf(SAMPLE_RATE, 48000, 44100)

/** Microphone capture on a dedicated thread, resampled to 16 kHz and framed at 80 ms. */
class AndroidAudioInput(
    private val log: Log,
) : AudioInput {
    private val flow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 128)
    override val frames: SharedFlow<ShortArray> = flow

    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service starts.
    private fun open(): AudioRecord {
        for (rate in CANDIDATE_RATES) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record =
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    ).setBufferSizeInBytes(maxOf(min, rate / 2 * 2))
                    .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                log.info("audio", "capturing at ${record.sampleRate} Hz")
                return record
            }
            record.release()
        }
        error("no supported capture rate")
    }

    override fun start() {
        if (running) return
        running = true
        worker =
            thread(name = "audio-input") {
                try {
                    capture()
                } catch (e: Throwable) {
                    log.error("audio", "capture failed", e)
                } finally {
                    running = false
                }
            }
    }

    private fun capture() {
        val record = open()
        val resampler = Resampler(record.sampleRate, SAMPLE_RATE)
        val framer = FrameBuffer(FRAME_SAMPLES)
        val buf = ShortArray(record.sampleRate / 10)
        record.startRecording()
        try {
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (frame in framer.push(resampler.process(buf.copyOf(n)))) {
                    flow.tryEmit(frame)
                }
            }
        } finally {
            record.stop()
            record.release()
        }
    }

    override fun stop() {
        running = false
        worker?.join(1000)
        worker = null
    }
}
