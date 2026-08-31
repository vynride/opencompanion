// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.bus

import io.github.vynride.opencompanion.core.state.State
import kotlinx.serialization.json.JsonObject

sealed interface Event

data object Wake : Event

data class Transcript(
    val text: String,
) : Event

data class Reply(
    val text: String,
) : Event

/** Speech that does not end a turn. */
data class Say(
    val text: String,
) : Event

/** Reply text with the estimated speech duration, for the word-by-word caption. */
data class Caption(
    val text: String,
    val seconds: Double,
) : Event

data object PlaybackDone : Event

data class ToolCall(
    val name: String,
    val args: JsonObject,
) : Event

data class ToolResult(
    val name: String,
    val result: String,
) : Event

data class Mouth(
    val level: Float,
) : Event

data class StateChanged(
    val state: State,
) : Event

enum class SenseKind { PROXIMITY, LIGHT, ACCEL_TAP, TOUCH }

data class Sense(
    val kind: SenseKind,
    val value: Float,
) : Event

data class TimerDone(
    val label: String,
) : Event

data class Failure(
    val source: String,
    val message: String,
) : Event
