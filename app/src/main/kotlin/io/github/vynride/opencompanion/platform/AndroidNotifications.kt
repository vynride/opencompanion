// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.vynride.opencompanion.R
import io.github.vynride.opencompanion.core.ports.Notification
import io.github.vynride.opencompanion.core.ports.Notifications

private const val CHANNEL_ID = "companion"

class AndroidNotifications(
    private val context: Context,
) : Notifications {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    override fun post(
        title: String,
        text: String,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(false)
                .build()
        manager.notify(title.hashCode(), notification)
    }

    override fun recent(): List<Notification> = NotificationMirror.current()
}
