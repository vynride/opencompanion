# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""check_host: TCP reachability of configured hosts."""

from __future__ import annotations

import asyncio
import time

from opencompanion.tools import Tool, ToolContext


def parse_hostport(s: str) -> tuple[str, int]:
    host, _, port = s.rpartition(":")
    if not host:
        return s, 22
    return host, int(port)


async def probe(host: str, port: int, timeout: float = 3.0) -> tuple[bool, float]:
    t0 = time.perf_counter()
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        writer.close()
        return True, time.perf_counter() - t0
    except OSError:
        return False, time.perf_counter() - t0


def make_tools(ctx: ToolContext) -> list[Tool]:
    timeout = float(ctx.cfg.get("hosts.timeout_s", 3.0))
    hosts: dict[str, str] = {k: v for k, v in (ctx.cfg.get("hosts", {}) or {}).items() if k != "timeout_s"}

    async def check_host(name: str) -> str:
        target = hosts.get(name)
        if target is None:
            return f"Unknown host '{name}'. Known: {', '.join(hosts) or 'none'}."
        host, port = parse_hostport(target)
        ok, secs = await probe(host, port, timeout)
        return (
            f"{name} is up (port {port} answered in {secs * 1000:.0f} ms)."
            if ok
            else f"{name} is down (no answer on port {port} within {timeout:g} s)."
        )

    return [
        Tool(
            "check_host",
            f"Check whether a configured machine is reachable. Known names: {', '.join(hosts) or 'none'}.",
            {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
            check_host,
        )
    ]
