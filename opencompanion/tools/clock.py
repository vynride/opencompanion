# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""get_time and set_timer."""

from __future__ import annotations

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

from opencompanion import platform
from opencompanion.bus import Say, TimerDone
from opencompanion.tools import Tool, ToolContext

# Timers must outlive the turn that started them.
ACTIVE_TIMERS: set[asyncio.Task] = set()


def now_text(tz_name: str, now: datetime | None = None) -> str:
    tz = ZoneInfo(tz_name)
    dt = (now or datetime.now(tz)).astimezone(tz)
    # bionic libc (Android) has no %-d, so the day is formatted by hand.
    return f"{dt.strftime('%A')} {dt.day} {dt.strftime('%B %Y, %H:%M')} ({dt.tzname()})"


async def run_timer(ctx: ToolContext, seconds: float, label: str) -> None:
    await asyncio.sleep(seconds)
    try:
        await platform.vibrate(400)
    except platform.PlatformError:
        pass
    await ctx.bus.publish(Say(f"Timer done: {label}"))
    await ctx.bus.publish(TimerDone(label))


def make_tools(ctx: ToolContext) -> list[Tool]:
    tz_name = ctx.cfg.get("location.timezone", "UTC")

    async def get_time() -> str:
        return now_text(tz_name)

    async def set_timer(seconds: float, label: str = "timer") -> str:
        task = asyncio.get_running_loop().create_task(run_timer(ctx, float(seconds), label))
        ACTIVE_TIMERS.add(task)
        task.add_done_callback(ACTIVE_TIMERS.discard)
        return f"Timer '{label}' set for {int(seconds)} seconds."

    return [
        Tool("get_time", "Current local date and time.", {"type": "object", "properties": {}}, get_time),
        Tool(
            "set_timer",
            "Start a countdown timer that alerts when done.",
            {
                "type": "object",
                "properties": {
                    "seconds": {"type": "number", "description": "Duration in seconds"},
                    "label": {"type": "string", "description": "What the timer is for"},
                },
                "required": ["seconds"],
            },
            set_timer,
        ),
    ]
