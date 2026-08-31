// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.config.CompanionConfig
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
import io.github.vynride.opencompanion.core.state.State
import io.github.vynride.opencompanion.core.state.StateMachine
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

    fun start() {
        stateMachine.start()
        log.info("companion", "running")
    }

    fun stop() {
        stateMachine.stop()
        scope.cancel()
        log.info("companion", "stopped")
    }
}
