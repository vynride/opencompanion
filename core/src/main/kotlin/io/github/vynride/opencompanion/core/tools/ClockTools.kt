// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.bus.TimerDone
import io.github.vynride.opencompanion.core.ports.Clock
import io.github.vynride.opencompanion.core.ports.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

fun clockTools(
    clock: Clock,
    haptics: Haptics,
    bus: EventBus,
    scope: CoroutineScope,
): List<Tool> {
    val getTime =
        Tool("get_time", "Current local date and time.", objectSchema()) {
            val dt = clock.now().atZone(clock.zone())
            val name = dt.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
            val rest = dt.format(DateTimeFormatter.ofPattern("MMMM yyyy, HH:mm", Locale.ENGLISH))
            val zone = dt.format(DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH))
            "$name ${dt.dayOfMonth} $rest ($zone)"
        }
    val setTimer =
        Tool(
            "set_timer",
            "Start a countdown timer that alerts when done.",
            objectSchema(
                "seconds" to numberParam("Duration in seconds"),
                "label" to stringParam("What the timer is for"),
                required = listOf("seconds"),
            ),
        ) { args ->
            val seconds = args["seconds"]!!.jsonPrimitive.content.toDouble()
            val label = args["label"]?.jsonPrimitive?.content ?: "timer"
            // Timers must outlive the turn that started them, hence the companion scope.
            scope.launch {
                delay(seconds.seconds)
                runCatching { haptics.vibrate(400) }
                bus.publish(Say("Timer done: $label"))
                bus.publish(TimerDone(label))
            }
            "Timer '$label' set for ${seconds.toInt()} seconds."
        }
    return listOf(getTime, setTimer)
}
