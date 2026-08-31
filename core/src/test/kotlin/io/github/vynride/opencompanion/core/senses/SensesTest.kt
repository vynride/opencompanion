// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.senses

import io.github.vynride.opencompanion.core.FakeSensors
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Sense
import io.github.vynride.opencompanion.core.bus.SenseKind
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.config.SensesConfig
import io.github.vynride.opencompanion.core.ports.Screen
import io.github.vynride.opencompanion.core.ports.SensorReading
import io.github.vynride.opencompanion.core.state.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SensesTest {
    private class FakeScreen : Screen {
        val levels = mutableListOf<Float>()

        override fun setBrightness(level: Float) {
            levels += level
        }
    }

    private class ThrowingScreen : Screen {
        override fun setBrightness(level: Float): Unit = throw IllegalStateException("no backlight control")
    }

    private class Harness(
        scope: TestScope,
        screen: Screen = FakeScreen(),
        config: SensesConfig =
            SensesConfig(
                tapThreshold = 4f,
                darkLux = 5f,
                proximityNearCm = 5f,
                dimBrightness = 0.1f,
                normalBrightness = 0.8f,
                sleepBrightness = 0.01f,
            ),
        readings: MutableSharedFlow<SensorReading> = MutableSharedFlow(extraBufferCapacity = 16),
    ) {
        val bus = EventBus()
        val screen = screen
        val readings = readings
        val seen = mutableListOf<Sense>()
        val senses =
            Senses(bus, FakeSensors(readings), screen, config, Log.Stdout, scope.backgroundScope)

        init {
            bus.on<Sense>().onEach { seen += it }.launchIn(scope.backgroundScope)
        }
    }

    @Test
    fun `proximity edge publishes once then stays quiet on the same side`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onReading(SensorReading.Proximity(3f))
        h.senses.onReading(SensorReading.Proximity(3f))
        h.senses.onReading(SensorReading.Proximity(4f))
        assertEquals(listOf(Sense(SenseKind.PROXIMITY, 3f)), h.seen)
        assertEquals(4f, h.senses.last["proximity"])
    }

    @Test
    fun `proximity flip republishes`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onReading(SensorReading.Proximity(3f))
        h.senses.onReading(SensorReading.Proximity(10f))
        assertEquals(
            listOf(Sense(SenseKind.PROXIMITY, 3f), Sense(SenseKind.PROXIMITY, 10f)),
            h.seen,
        )
    }

    @Test
    fun `light edge dims and publishes, no republish without an edge`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onReading(SensorReading.Light(1f))
        h.senses.onReading(SensorReading.Light(1f))
        h.senses.onReading(SensorReading.Light(120f))
        assertEquals(
            listOf(Sense(SenseKind.LIGHT, 1f), Sense(SenseKind.LIGHT, 120f)),
            h.seen,
        )
        assertEquals(listOf(0.1f, 0.8f), (h.screen as FakeScreen).levels)
        assertEquals(120f, h.senses.last["light"])
    }

    @Test
    fun `light changes while asleep do not touch the backlight`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onState(StateChanged(State.SLEEPING))
        (h.screen as FakeScreen).levels.clear()
        h.senses.onReading(SensorReading.Light(1f))
        h.senses.onReading(SensorReading.Light(120f))
        assertEquals(emptyList(), h.screen.levels)
        assertEquals(listOf(Sense(SenseKind.LIGHT, 1f), Sense(SenseKind.LIGHT, 120f)), h.seen)
    }

    @Test
    fun `accel tap fires at or above threshold, not below, never on the first reading`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onReading(SensorReading.Acceleration(0f, 0f, 0f))
        h.senses.onReading(SensorReading.Acceleration(3f, 4f, 0f))
        h.senses.onReading(SensorReading.Acceleration(3f, 4f, 0f))
        assertEquals(listOf(Sense(SenseKind.ACCEL_TAP, 5f)), h.seen)
    }

    @Test
    fun `sleeping sets sleep brightness once and waking restores the ambient level`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onState(StateChanged(State.SLEEPING))
        assertEquals(listOf(0.01f), (h.screen as FakeScreen).levels)
        h.senses.onState(StateChanged(State.SLEEPING))
        assertEquals(1, h.screen.levels.size)
        h.senses.onState(StateChanged(State.IDLE))
        assertEquals(listOf(0.01f, 0.8f), h.screen.levels)
        h.senses.onState(StateChanged(State.LISTENING))
        assertEquals(2, h.screen.levels.size)
    }

    @Test
    fun `waking restores dim brightness when the room is dark`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.onReading(SensorReading.Light(1f))
        (h.screen as FakeScreen).levels.clear()
        h.senses.onState(StateChanged(State.SLEEPING))
        h.senses.onState(StateChanged(State.IDLE))
        assertEquals(listOf(0.01f, 0.1f), h.screen.levels)
    }

    @Test
    fun `start sets the ambient brightness once and wires both the sensor and bus flows`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this)
        h.senses.start()
        assertEquals(listOf(0.8f), (h.screen as FakeScreen).levels)
        h.readings.emit(SensorReading.Proximity(3f))
        assertEquals(listOf(Sense(SenseKind.PROXIMITY, 3f)), h.seen)
        h.bus.publish(StateChanged(State.SLEEPING))
        assertEquals(listOf(0.8f, 0.01f), h.screen.levels)
        h.senses.stop()
        h.readings.emit(SensorReading.Proximity(10f))
        assertEquals(listOf(Sense(SenseKind.PROXIMITY, 3f)), h.seen)
    }

    @Test
    fun `a throwing screen is caught and logged and never kills the collector loop`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, screen = ThrowingScreen())
        h.senses.start()
        h.readings.emit(SensorReading.Light(1f))
        h.readings.emit(SensorReading.Proximity(3f))
        assertEquals(
            listOf(Sense(SenseKind.LIGHT, 1f), Sense(SenseKind.PROXIMITY, 3f)),
            h.seen,
        )
    }
}
