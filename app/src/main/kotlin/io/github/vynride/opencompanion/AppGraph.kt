// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion

import android.content.Context
import io.github.vynride.opencompanion.core.Level
import io.github.vynride.opencompanion.core.Log
import android.util.Log as AndroidLog

class AppGraph(
    context: Context,
) {
    val log: Log =
        Log { level, tag, message, error ->
            val t = "oc.$tag"
            when (level) {
                Level.DEBUG -> AndroidLog.d(t, message, error)
                Level.INFO -> AndroidLog.i(t, message, error)
                Level.WARN -> AndroidLog.w(t, message, error)
                Level.ERROR -> AndroidLog.e(t, message, error)
            }
        }
}
