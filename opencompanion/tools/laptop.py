# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Laptop link through the KDE Connect app: notify, clipboard, read notifications."""

from __future__ import annotations

from opencompanion import platform
from opencompanion.tools import Tool, ToolContext

KDE_PREFIX = "org.kde.kdeconnect"


def kde_connect_entries(items: list[dict]) -> list[str]:
    kept = [
        f"{x.get('title', '')}: {x.get('content', '')}".strip(": ")
        for x in items
        if str(x.get("packageName", "")).startswith(KDE_PREFIX)
    ]
    return list(reversed(kept))[:10]


def make_tools(ctx: ToolContext) -> list[Tool]:
    async def laptop_notify(title: str, text: str) -> str:
        try:
            await platform.notify(title, text)
            return f"Sent notification '{title}' to the laptop."
        except platform.PlatformError as e:
            return f"Could not notify: {e}"

    async def laptop_clipboard(text: str) -> str:
        try:
            await platform.clipboard_set(text)
            return "Copied to the shared clipboard."
        except platform.PlatformError as e:
            return f"Could not set clipboard: {e}"

    async def laptop_notifications() -> str:
        try:
            entries = kde_connect_entries(await platform.notification_list())
        except platform.PlatformError as e:
            return f"Could not read notifications: {e}"
        return "\n".join(entries) if entries else "No laptop notifications right now."

    return [
        Tool(
            "laptop_notify",
            "Show a notification on the user's laptop (via KDE Connect).",
            {
                "type": "object",
                "properties": {"title": {"type": "string"}, "text": {"type": "string"}},
                "required": ["title", "text"],
            },
            laptop_notify,
        ),
        Tool(
            "laptop_clipboard",
            "Put text on the laptop clipboard (via KDE Connect).",
            {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
            laptop_clipboard,
        ),
        Tool(
            "laptop_notifications",
            "List recent notifications mirrored from the laptop.",
            {"type": "object", "properties": {}},
            laptop_notifications,
        ),
    ]
