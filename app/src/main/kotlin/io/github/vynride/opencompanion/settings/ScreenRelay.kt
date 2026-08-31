// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.view.Window
import io.github.vynride.opencompanion.core.ports.Screen

/** Forwards brightness changes to whichever activity window is currently attached. */
object ScreenRelay : Screen {
    @Volatile
    private var window: Window? = null

    fun attach(window: Window) {
        this.window = window
    }

    fun detach() {
        window = null
    }

    override fun setBrightness(level: Float) {
        val w = window ?: return
        w.decorView.post {
            w.attributes = w.attributes.also { it.screenBrightness = level.coerceIn(0f, 1f) }
        }
    }
}
