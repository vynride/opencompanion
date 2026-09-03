// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import io.github.vynride.opencompanion.core.ports.AudioInput
import io.github.vynride.opencompanion.core.ports.AudioOutput
import io.github.vynride.opencompanion.core.ports.Camera
import io.github.vynride.opencompanion.core.ports.Clipboard
import io.github.vynride.opencompanion.core.ports.Clock
import io.github.vynride.opencompanion.core.ports.Haptics
import io.github.vynride.opencompanion.core.ports.Lens
import io.github.vynride.opencompanion.core.ports.ModelStore
import io.github.vynride.opencompanion.core.ports.Notification
import io.github.vynride.opencompanion.core.ports.Notifications
import io.github.vynride.opencompanion.core.ports.Screen
import io.github.vynride.opencompanion.core.ports.SensorReading
import io.github.vynride.opencompanion.core.ports.Sensors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class FakeAudioInput : AudioInput {
    private val flow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 1024)
    override val frames: SharedFlow<ShortArray> = flow
    var started = false

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    suspend fun emit(frame: ShortArray) = flow.emit(frame)
}

class FakeAudioOutput : AudioOutput {
    val played = mutableListOf<ByteArray>()
    val wavs = mutableListOf<ByteArray>()

    override suspend fun playPcm(
        rateHz: Int,
        chunks: Flow<ByteArray>,
    ) {
        played += chunks.toList().fold(ByteArray(0)) { a, b -> a + b }
    }

    override suspend fun playWav(wav: ByteArray) {
        wavs += wav
    }
}

class FakeCamera(
    var jpeg: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
) : Camera {
    var captures = 0

    override suspend fun captureJpeg(lens: Lens): ByteArray {
        captures++
        return jpeg
    }
}

class FakeSensors(
    override val readings: Flow<SensorReading>,
) : Sensors

class FakeScreen : Screen {
    val levels = mutableListOf<Float>()

    override fun setBrightness(level: Float) {
        levels += level
    }
}

class FakeHaptics : Haptics {
    val buzzes = mutableListOf<Long>()

    override fun vibrate(ms: Long) {
        buzzes += ms
    }
}

class FakeNotifications(
    var mirrored: List<Notification> = emptyList(),
) : Notifications {
    val posted = mutableListOf<Pair<String, String>>()

    override fun post(
        title: String,
        text: String,
    ) {
        posted += title to text
    }

    override fun recent(): List<Notification> = mirrored
}

class FakeClipboard : Clipboard {
    var text: String? = null

    override fun set(text: String) {
        this.text = text
    }
}

/** Reads models from the repo's models/ directory; missing files throw. */
class RepoModelStore(
    private val dir: Path = Path.of("..", "models"),
) : ModelStore {
    fun has(name: String) = Files.exists(dir.resolve(name))

    override fun read(name: String): ByteArray = Files.readAllBytes(dir.resolve(name))
}

class FixedClock(
    var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock {
    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId
}
