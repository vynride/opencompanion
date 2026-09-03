// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.vynride.opencompanion.appGraph
import io.github.vynride.opencompanion.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private const val TAG = "oc.quiet"

/**
 * Stops the companion at the start of quiet hours and starts it again at the end.
 * One inexact alarm is kept for the next boundary; inexact needs no permission and a
 * few minutes of drift is fine for an evening-off, morning-on schedule.
 *
 * The morning start only succeeds while the face is on screen: a microphone service
 * may not start from the background. Off screen, the face starts it on its next show.
 */
object QuietHoursScheduler {
    fun isQuietNow(settings: Settings): Boolean = settings.quietHours()?.isQuiet(LocalTime.now()) == true

    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(app)
        alarms.cancel(pending)
        val window = app.appGraph.settings.current().quietHours() ?: return
        val next = window.nextBoundary(LocalDateTime.now())
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Log.i(TAG, "next boundary at $next")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, QuietHoursReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class QuietHoursReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = app.appGraph.settings.current()
                if (settings.quietHours() == null) return@launch
                if (QuietHoursScheduler.isQuietNow(settings)) {
                    Log.i(TAG, "quiet hours begin, stopping")
                    runCatching { app.startService(CompanionService.stopIntent(app)) }
                        .onFailure { Log.w(TAG, "stop failed: ${it.message}") }
                } else {
                    Log.i(TAG, "quiet hours end, starting")
                    runCatching { CompanionService.start(app) }
                        .onFailure { Log.w(TAG, "start refused off screen: ${it.message}") }
                }
                QuietHoursScheduler.reschedule(app)
            } finally {
                result.finish()
            }
        }
    }
}
