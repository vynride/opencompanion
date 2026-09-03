// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion

import android.app.Application
import io.github.vynride.opencompanion.service.QuietHoursScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Alarms do not survive a reboot; the first launch after one re-arms the schedule.
        CoroutineScope(Dispatchers.Default).launch { QuietHoursScheduler.reschedule(this@App) }
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as App).graph
