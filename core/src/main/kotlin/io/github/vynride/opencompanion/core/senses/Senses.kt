// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.senses

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Sense
import io.github.vynride.opencompanion.core.bus.SenseKind
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.config.SensesConfig
import io.github.vynride.opencompanion.core.ports.Screen
import io.github.vynride.opencompanion.core.ports.SensorReading
import io.github.vynride.opencompanion.core.ports.Sensors
import io.github.vynride.opencompanion.core.state.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** Publishes edge-triggered proximity/light/tap senses and follows the ambient light with the backlight. */
class Senses(
    private val bus: EventBus,
    private val sensors: Sensors,
    private val screen: Screen,
    private val config: SensesConfig,
    private val log: Log,
    private val scope: CoroutineScope,
) {
    private val _last = mutableMapOf<String, Float>()
    val last: Map<String, Float> get() = _last

    private var near: Boolean? = null
    private var dark: Boolean? = null
    private var prevMagnitude: Float? = null
    private var asleep = false

    private var readingsJob: Job? = null
    private var busJob: Job? = null

    fun start() {
        setBrightness(ambient())
        readingsJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sensors.readings.collect { r ->
                    runCatching { onReading(r) }.onFailure { e -> log.error("senses", "handler failed", e) }
                }
            }
        busJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                bus.on<StateChanged>().collect { e ->
                    runCatching { onState(e) }.onFailure { e2 -> log.error("senses", "handler failed", e2) }
                }
            }
    }

    fun stop() {
        readingsJob?.cancel()
        busJob?.cancel()
    }

    suspend fun onReading(r: SensorReading) {
        when (r) {
            is SensorReading.Proximity -> onProximity(r.cm)
            is SensorReading.Light -> onLight(r.lux)
            is SensorReading.Acceleration -> onAcceleration(r.x, r.y, r.z)
        }
    }

    suspend fun onState(e: StateChanged) {
        val nowAsleep = e.state == State.SLEEPING
        if (nowAsleep == asleep) return
        asleep = nowAsleep
        setBrightness(if (asleep) config.sleepBrightness else ambient())
    }

    private suspend fun onProximity(cm: Float) {
        _last["proximity"] = cm
        val isNear = cm < config.proximityNearCm
        if (isNear != near) {
            near = isNear
            bus.publish(Sense(SenseKind.PROXIMITY, cm))
        }
    }

    private suspend fun onLight(lux: Float) {
        _last["light"] = lux
        val isDark = lux < config.darkLux
        if (isDark != dark) {
            dark = isDark
            bus.publish(Sense(SenseKind.LIGHT, lux))
            if (!asleep) setBrightness(ambient())
        }
    }

    private suspend fun onAcceleration(
        x: Float,
        y: Float,
        z: Float,
    ) {
        val magnitude = sqrt(x * x + y * y + z * z)
        val prev = prevMagnitude
        if (prev != null) {
            val delta = abs(magnitude - prev)
            if (delta >= config.tapThreshold) bus.publish(Sense(SenseKind.ACCEL_TAP, delta))
        }
        prevMagnitude = magnitude
    }

    private fun ambient(): Float = if (dark == true) config.dimBrightness else config.normalBrightness

    private fun setBrightness(level: Float) {
        runCatching { screen.setBrightness(level) }.onFailure { e -> log.error("senses", "brightness failed", e) }
    }
}
