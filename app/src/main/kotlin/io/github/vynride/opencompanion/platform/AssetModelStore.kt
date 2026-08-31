// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.content.Context
import io.github.vynride.opencompanion.core.ports.ModelStore
import java.io.File

/** Reads a downloaded model from filesDir/models if present, else falls back to the bundled asset. */
class AssetModelStore(
    private val context: Context,
) : ModelStore {
    override fun read(name: String): ByteArray {
        val downloaded = File(context.filesDir, "models/$name")
        if (downloaded.exists()) return downloaded.readBytes()
        return context.assets.open(name).use { it.readBytes() }
    }
}
