// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.vynride.opencompanion.core.ports.SensorReading
import io.github.vynride.opencompanion.core.ports.Sensors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidSensors(
    context: Context,
) : Sensors {
    private val manager = context.getSystemService(SensorManager::class.java)

    override val readings: Flow<SensorReading> =
        callbackFlow {
            val listeners =
                listOf(
                    Sensor.TYPE_PROXIMITY to { event: SensorEvent -> SensorReading.Proximity(event.values[0]) },
                    Sensor.TYPE_LIGHT to { event: SensorEvent -> SensorReading.Light(event.values[0]) },
                    Sensor.TYPE_ACCELEROMETER to { event: SensorEvent ->
                        SensorReading.Acceleration(event.values[0], event.values[1], event.values[2])
                    },
                ).mapNotNull { (type, map) ->
                    val sensor = manager.getDefaultSensor(type) ?: return@mapNotNull null
                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                trySend(map(event))
                            }

                            override fun onAccuracyChanged(
                                sensor: Sensor,
                                accuracy: Int,
                            ) = Unit
                        }
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    listener
                }
            awaitClose { listeners.forEach { manager.unregisterListener(it) } }
        }
}
