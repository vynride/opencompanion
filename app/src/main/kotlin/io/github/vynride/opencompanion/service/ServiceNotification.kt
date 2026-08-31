// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.vynride.opencompanion.R

private const val CHANNEL_ID = "companion"

/** The ongoing notification that keeps [CompanionService] alive in the foreground. */
object ServiceNotification {
    const val ID = 1

    fun build(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop =
            PendingIntent.getService(
                context,
                0,
                CompanionService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(context.getString(R.string.notification_running))
            .addAction(0, context.getString(R.string.notification_stop), stop)
            .build()
    }
}
