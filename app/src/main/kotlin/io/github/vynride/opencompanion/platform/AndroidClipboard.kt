// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.vynride.opencompanion.core.ports.Clipboard

class AndroidClipboard(
    context: Context,
) : Clipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    override fun set(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("opencompanion", text))
    }
}
