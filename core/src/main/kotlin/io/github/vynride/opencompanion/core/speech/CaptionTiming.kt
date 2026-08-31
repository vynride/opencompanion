// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.speech

/** Rough speaking time of `text`, used to pace the caption reveal. */
fun captionSeconds(
    text: String,
    wps: Double = 2.6,
): Double = maxOf(1.2, text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size / wps)
