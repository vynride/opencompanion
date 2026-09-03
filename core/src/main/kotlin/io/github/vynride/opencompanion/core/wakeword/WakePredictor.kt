// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.wakeword

interface WakePredictor {
    /** Score 0..1 for the wake word ending in this 1280-sample frame. */
    fun score(frame: ShortArray): Float

    fun reset()
}
