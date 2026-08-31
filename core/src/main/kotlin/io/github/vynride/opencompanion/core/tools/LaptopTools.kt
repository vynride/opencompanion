// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.ports.Clipboard
import io.github.vynride.opencompanion.core.ports.Notification
import io.github.vynride.opencompanion.core.ports.Notifications
import kotlinx.serialization.json.jsonPrimitive

private const val KDE_PREFIX = "org.kde.kdeconnect"

fun kdeConnectEntries(items: List<Notification>): List<String> =
    items
        .filter { it.packageName.startsWith(KDE_PREFIX) }
        .map { "${it.title}: ${it.text}".trim(':', ' ') }
        .reversed()
        .take(10)

fun laptopTools(
    notifications: Notifications,
    clipboard: Clipboard,
): List<Tool> =
    listOf(
        Tool(
            "laptop_notify",
            "Show a notification on the user's laptop (via KDE Connect).",
            objectSchema("title" to stringParam(), "text" to stringParam(), required = listOf("title", "text")),
        ) { args ->
            val title = args["title"]!!.jsonPrimitive.content
            notifications.post(title, args["text"]!!.jsonPrimitive.content)
            "Sent notification '$title' to the laptop."
        },
        Tool(
            "laptop_clipboard",
            "Put text on the laptop clipboard (via KDE Connect).",
            objectSchema("text" to stringParam(), required = listOf("text")),
        ) { args ->
            clipboard.set(args["text"]!!.jsonPrimitive.content)
            "Copied to the shared clipboard."
        },
        Tool("laptop_notifications", "List recent notifications mirrored from the laptop.", objectSchema()) {
            val entries = kdeConnectEntries(notifications.recent())
            if (entries.isEmpty()) "No laptop notifications right now." else entries.joinToString("\n")
        },
    )
