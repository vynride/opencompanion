# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
from unittest.mock import AsyncMock

import httpx

from opencompanion import platform
from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.laptop import kde_connect_entries, make_tools


def tools(monkeypatch, **fakes):
    for name, fake in fakes.items():
        monkeypatch.setattr(platform, name, fake)
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient())
    return {t.name: t for t in make_tools(ctx)}


def test_kde_connect_entries_filters_and_formats():
    items = [
        {"packageName": "com.example.other", "title": "x", "content": "y"},
        {"packageName": "org.kde.kdeconnect_tp", "title": "Mail", "content": "3 new"},
        {"packageName": "org.kde.kdeconnect_tp", "title": "Build", "content": "passed"},
    ]
    assert kde_connect_entries(items) == ["Build: passed", "Mail: 3 new"]


async def test_notify_and_clipboard(monkeypatch):
    notify = AsyncMock()
    clip = AsyncMock()
    t = tools(monkeypatch, notify=notify, clipboard_set=clip)
    assert "Sent" in await t["laptop_notify"].func(title="Hi", text="there")
    notify.assert_awaited_once_with("Hi", "there")
    assert "Copied" in await t["laptop_clipboard"].func(text="abc")
    clip.assert_awaited_once_with("abc")


async def test_notifications_list(monkeypatch):
    lst = AsyncMock(return_value=[{"packageName": "org.kde.kdeconnect_tp", "title": "T", "content": "C"}])
    t = tools(monkeypatch, notification_list=lst)
    assert await t["laptop_notifications"].func() == "T: C"
    lst.return_value = []
    assert "No laptop notifications" in await t["laptop_notifications"].func()


async def test_platform_error_is_reported(monkeypatch):
    t = tools(
        monkeypatch, notify=AsyncMock(side_effect=platform.PlatformError("termux-notification not found"))
    )
    assert "not found" in await t["laptop_notify"].func(title="a", text="b")
