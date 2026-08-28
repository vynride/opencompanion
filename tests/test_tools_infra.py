# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import time

import httpx
import pytest

from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.infra import make_tools, parse_hostport, probe


def test_parse_hostport():
    assert parse_hostport("192.0.2.10:22") == ("192.0.2.10", 22)
    assert parse_hostport("example.invalid") == ("example.invalid", 22)
    with pytest.raises(ValueError):
        parse_hostport("host:notaport")


async def test_probe_open_and_closed_port():
    server = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    ok, secs = await probe("127.0.0.1", port)
    assert ok and secs < 1
    server.close()
    await server.wait_closed()
    ok, _ = await probe("127.0.0.1", port, timeout=0.5)
    assert not ok


async def test_tool_uses_config_map():
    server = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    cfg = Config({"hosts": {"box": f"127.0.0.1:{port}"}}, {})
    tool = make_tools(ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient()))[0]
    assert "box is up" in await tool.func(name="box")
    assert "Unknown host" in await tool.func(name="other")
    assert "box" in tool.description
    server.close()
    await server.wait_closed()


async def test_check_host_uses_configured_timeout():
    # TEST-NET-1 (RFC 5737) is never routed, so only the timeout ends the connect.
    cfg = Config({"hosts": {"box": "192.0.2.1:9", "timeout_s": 0.05}}, {})
    ctx = ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient())
    tool = make_tools(ctx)[0]
    t0 = time.perf_counter()
    result = await tool.func(name="box")
    elapsed = time.perf_counter() - t0
    assert "down" in result
    assert elapsed < 1.0


async def test_timeout_s_is_not_a_host():
    cfg = Config({"hosts": {"box": "127.0.0.1:1"}}, {})
    ctx = ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient())
    tool = make_tools(ctx)[0]
    assert "Unknown host" in await tool.func(name="timeout_s")
    assert "timeout_s" not in tool.description
