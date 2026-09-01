// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.audio.parseWav
import io.github.vynride.opencompanion.core.audio.toLittleEndianBytes
import io.github.vynride.opencompanion.core.ports.AudioOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// Abort playback when the head stops advancing for this long; the mixer can
// deactivate a track (focus loss, another app) and a blocked track never recovers.
private const val STALL_MS = 3_000
private const val POLL_MS = 20L

class AndroidAudioOutput(
    context: Context,
    private val log: Log,
) : AudioOutput {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun track(rateHz: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack
            .Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rateHz))
            .build()
    }

    // Focus improves behavior around other audio apps but must never gate speech.
    private fun requestFocus(): AudioFocusRequest? {
        val manager = audioManager ?: return null
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            log.info("audio", "audio focus denied; playing anyway")
        }
        return request
    }

    override suspend fun playPcm(
        rateHz: Int,
        chunks: Flow<ByteArray>,
    ) = withContext(Dispatchers.IO) {
        val focus = requestFocus()
        val track = track(rateHz)
        var framesWritten = 0L
        var aborted = false
        track.play()
        try {
            chunks.collect { chunk ->
                if (aborted) return@collect
                if (!writeChunk(track, chunk)) {
                    aborted = true
                    return@collect
                }
                framesWritten += chunk.size / 2
            }
            // Drain: wait until the playback head reaches everything written,
            // bailing if the head stops advancing.
            var lastHead = track.playbackHeadPosition
            var stalledMs = 0
            while (!aborted &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition.toLong() < framesWritten
            ) {
                delay(POLL_MS)
                val head = track.playbackHeadPosition
                if (head == lastHead) {
                    stalledMs += POLL_MS.toInt()
                    if (stalledMs >= STALL_MS) {
                        log.warn("audio", "playback stalled while draining; aborting")
                        break
                    }
                } else {
                    lastHead = head
                    stalledMs = 0
                }
            }
        } catch (e: CancellationException) {
            // Barge-in: drop the queued audio so playback stops immediately.
            runCatching {
                track.pause()
                track.flush()
            }
            throw e
        } finally {
            runCatching { track.stop() }
            track.release()
            focus?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }

    /** Writes without ever blocking in native code; false means playback is dead and must abort. */
    private suspend fun writeChunk(
        track: AudioTrack,
        chunk: ByteArray,
    ): Boolean {
        var offset = 0
        var lastHead = track.playbackHeadPosition
        var stalledMs = 0
        while (offset < chunk.size) {
            val n = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            when {
                n == AudioTrack.ERROR_DEAD_OBJECT -> {
                    log.warn("audio", "audio track died; aborting playback")
                    return false
                }

                n < 0 -> {
                    log.warn("audio", "audio write failed ($n); aborting playback")
                    return false
                }

                n == 0 -> {
                    // Buffer full: wait for the head to advance; a track the mixer
                    // deactivated never advances, so give up after the stall limit.
                    delay(POLL_MS)
                    val head = track.playbackHeadPosition
                    if (head == lastHead) {
                        stalledMs += POLL_MS.toInt()
                        if (stalledMs >= STALL_MS) {
                            log.warn("audio", "playback made no progress; aborting")
                            return false
                        }
                    } else {
                        lastHead = head
                        stalledMs = 0
                    }
                }

                else -> {
                    offset += n
                    stalledMs = 0
                }
            }
        }
        return true
    }

    override suspend fun playWav(wav: ByteArray) {
        val data = parseWav(wav)
        playPcm(data.rateHz, kotlinx.coroutines.flow.flowOf(data.samples.toLittleEndianBytes()))
    }
}
