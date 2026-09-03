// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.ports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant
import java.time.ZoneId

/** 16 kHz mono PCM, one ShortArray of 1280 samples (80 ms) per emission. */
interface AudioInput {
    val frames: SharedFlow<ShortArray>

    fun start()

    fun stop()
}

interface AudioOutput {
    /** Plays raw s16le mono chunks as they arrive; returns when playback has drained. */
    suspend fun playPcm(
        rateHz: Int,
        chunks: Flow<ByteArray>,
    )

    suspend fun playWav(wav: ByteArray)
}

enum class Lens { FRONT, BACK }

interface Camera {
    suspend fun captureJpeg(lens: Lens): ByteArray
}

sealed interface SensorReading {
    data class Proximity(
        val cm: Float,
    ) : SensorReading

    data class Light(
        val lux: Float,
    ) : SensorReading

    data class Acceleration(
        val x: Float,
        val y: Float,
        val z: Float,
    ) : SensorReading
}

interface Sensors {
    val readings: Flow<SensorReading>
}

interface Screen {
    /** 0f..1f window brightness. */
    fun setBrightness(level: Float)
}

interface Haptics {
    fun vibrate(ms: Long)
}

data class Notification(
    val packageName: String,
    val title: String,
    val text: String,
)

interface Notifications {
    fun post(
        title: String,
        text: String,
    )

    fun recent(): List<Notification>
}

interface Clipboard {
    fun set(text: String)
}

interface ModelStore {
    fun read(name: String): ByteArray
}

interface Clock {
    fun now(): Instant

    fun zone(): ZoneId
}

class SystemClock(
    private val zoneId: ZoneId,
) : Clock {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = zoneId
}
