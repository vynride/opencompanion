// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CompanionService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
}
