// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.vad

/**
 * Speech when any member says speech. Every member is fed every frame before the OR:
 * a model VAD carries recurrent state across frames, and short-circuiting a feed
 * would corrupt its temporal context.
 */
class AnyVad(
    private vararg val vads: Vad,
) : Vad {
    override fun isSpeech(frame: ShortArray): Boolean = vads.map { it.isSpeech(frame) }.any { it }

    override fun reset() = vads.forEach { it.reset() }
}
