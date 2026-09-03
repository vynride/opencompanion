// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.vynride.opencompanion.core.ports.Notification

/** Mirrors the system notification shade; requires the user to grant the listener permission. */
class NotificationMirror : NotificationListenerService() {
    companion object {
        @Volatile private var items: List<Notification> = emptyList()

        fun current(): List<Notification> = items
    }

    private fun refresh() {
        runCatching {
            items = activeNotifications.map { it.toNotification() }
        }
    }

    override fun onListenerConnected() = refresh()

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()
}

private fun StatusBarNotification.toNotification(): Notification {
    val extras = notification.extras
    return Notification(
        packageName = packageName,
        title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
        text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "",
    )
}
