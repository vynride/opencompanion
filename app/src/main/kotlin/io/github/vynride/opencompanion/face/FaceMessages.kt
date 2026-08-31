// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.face

import io.github.vynride.opencompanion.core.bus.Caption
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.Mouth
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.bus.Transcript
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Replace em/en dashes with commas so captions read cleanly on screen. */
private fun clean(text: String): String = text.replace(" — ", ", ").replace(" – ", ", ").replace("—", ", ").replace("–", ", ").trim()

private fun round3(value: Double): Double = (value * 1000).roundToInt() / 1000.0

/** Encodes core events into the JSON messages the face's `window.face.onMessage` hook expects. */
object FaceMessages {
    fun forEvent(e: Event): String? = when (e) {
        is StateChanged ->
            buildJsonObject {
                put("type", "state")
                put("state", e.state.wire)
            }.toString()

        is Mouth ->
            buildJsonObject {
                put("type", "mouth")
                put("level", round3(e.level.toDouble()))
            }.toString()

        is Transcript ->
            buildJsonObject {
                put("type", "caption")
                put("who", "heard")
                put("text", clean(e.text))
            }.toString()

        is Caption ->
            buildJsonObject {
                put("type", "caption")
                put("who", "said")
                put("text", clean(e.text))
                put("seconds", round3(e.seconds))
            }.toString()

        else -> null
    }
}
