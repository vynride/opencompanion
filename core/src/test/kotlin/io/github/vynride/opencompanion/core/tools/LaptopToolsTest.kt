// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.FakeClipboard
import io.github.vynride.opencompanion.core.FakeNotifications
import io.github.vynride.opencompanion.core.ports.Notification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaptopToolsTest {
    @Test
    fun `kdeConnectEntries filters by package prefix, orders newest first and caps at 10`() {
        val items =
            listOf(Notification("com.example.other", "x", "y")) +
                (1..12).map { Notification("org.kde.kdeconnect_tp", "T$it", "C$it") }
        val entries = kdeConnectEntries(items)
        assertEquals(10, entries.size)
        assertEquals("T12: C12", entries.first())
        assertEquals("T3: C3", entries.last())
    }

    @Test
    fun `kdeConnectEntries example matches the reference ordering`() {
        val items =
            listOf(
                Notification("com.example.other", "x", "y"),
                Notification("org.kde.kdeconnect_tp", "Mail", "3 new"),
                Notification("org.kde.kdeconnect_tp", "Build", "passed"),
            )
        assertEquals(listOf("Build: passed", "Mail: 3 new"), kdeConnectEntries(items))
    }

    @Test
    fun `laptop_notify posts and confirms`() =
        runTest {
            val notifications = FakeNotifications()
            val tools = laptopTools(notifications, FakeClipboard())
            val reply =
                tools.first { it.name == "laptop_notify" }.call(
                    buildJsonObject {
                        put("title", "Hi")
                        put("text", "there")
                    },
                )
            assertTrue("Sent" in reply)
            assertEquals(listOf("Hi" to "there"), notifications.posted)
        }

    @Test
    fun `laptop_clipboard sets and confirms`() =
        runTest {
            val clipboard = FakeClipboard()
            val tools = laptopTools(FakeNotifications(), clipboard)
            val reply = tools.first { it.name == "laptop_clipboard" }.call(buildJsonObject { put("text", "abc") })
            assertTrue("Copied" in reply)
            assertEquals("abc", clipboard.text)
        }

    @Test
    fun `laptop_notifications lists mirrored entries and reports none when empty`() =
        runTest {
            val notifications = FakeNotifications(listOf(Notification("org.kde.kdeconnect_tp", "T", "C")))
            val tools = laptopTools(notifications, FakeClipboard())
            val list = tools.first { it.name == "laptop_notifications" }
            assertEquals("T: C", list.call(buildJsonObject {}))
            notifications.mirrored = emptyList()
            assertTrue("No laptop notifications" in list.call(buildJsonObject {}))
        }
}
