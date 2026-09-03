// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

import io.github.vynride.opencompanion.core.audio.rms

/** Fallback VAD: a frame is speech when its RMS clears a fixed threshold. */
class EnergyVad(
    private val threshold: Double = 500.0,
) : Vad {
    override fun isSpeech(frame: ShortArray): Boolean = frame.isNotEmpty() && rms(frame) > threshold
}
