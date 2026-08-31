// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.vynride.opencompanion.core.audio.parseWav
import io.github.vynride.opencompanion.core.audio.toLittleEndianBytes
import io.github.vynride.opencompanion.core.ports.AudioOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AndroidAudioOutput : AudioOutput {
    private fun track(rateHz: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setAudioFormat(
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

    override suspend fun playPcm(
        rateHz: Int,
        chunks: Flow<ByteArray>,
    ) = withContext(Dispatchers.IO) {
        val track = track(rateHz)
        var framesWritten = 0L
        track.play()
        try {
            chunks.collect { chunk ->
                var offset = 0
                while (offset < chunk.size) {
                    val n = track.write(chunk, offset, chunk.size - offset)
                    if (n < 0) return@collect
                    offset += n
                }
                framesWritten += chunk.size / 2
            }
            // Drain: wait until the playback head reaches everything written.
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition.toLong() < framesWritten
            ) {
                Thread.sleep(20)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    override suspend fun playWav(wav: ByteArray) {
        val data = parseWav(wav)
        playPcm(data.rateHz, kotlinx.coroutines.flow.flowOf(data.samples.toLittleEndianBytes()))
    }
}
