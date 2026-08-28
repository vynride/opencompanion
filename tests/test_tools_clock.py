# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
from datetime import UTC, datetime
from unittest.mock import AsyncMock

import httpx

from opencompanion import platform
from opencompanion.bus import EventBus, Say, TimerDone
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.clock import make_tools, now_text


def ctx(bus=None):
    return ToolContext(
        cfg=Config({"location": {"timezone": "UTC"}}, {}), bus=bus or EventBus(), http=httpx.AsyncClient()
    )


def test_now_text_formats():
    fixed = datetime(2026, 8, 28, 14, 5, tzinfo=UTC)
    assert now_text("UTC", fixed) == "Friday 28 August 2026, 14:05 (UTC)"


def test_now_text_does_not_zero_pad_single_digit_day():
    fixed = datetime(2026, 8, 5, 9, 0, tzinfo=UTC)
    assert now_text("UTC", fixed) == "Wednesday 5 August 2026, 09:00 (UTC)"


async def test_get_time_tool_uses_config_timezone():
    tools = {t.name: t for t in make_tools(ctx())}
    text = await tools["get_time"].func()
    assert "(UTC)" in text


async def test_set_timer_fires_events(monkeypatch):
    vib = AsyncMock()
    monkeypatch.setattr(platform, "vibrate", vib)
    bus = EventBus()
    seen = []
    bus.subscribe(Say, seen.append)
    bus.subscribe(TimerDone, seen.append)
    tools = {t.name: t for t in make_tools(ctx(bus))}
    reply = await tools["set_timer"].func(seconds=0.01, label="tea")
    assert "tea" in reply
    await asyncio.sleep(0.05)
    assert seen == [Say("Timer done: tea"), TimerDone("tea")]
    vib.assert_awaited_once()
