# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging
import sys
import types

import httpx

import opencompanion.tools as tools_module
from opencompanion.bus import EventBus, ToolCall, ToolResult
from opencompanion.config import Config
from opencompanion.tools import Tool, ToolContext, ToolRegistry, build_registry, disabled_tool


def ctx():
    return ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient())


async def test_spec_shape():
    t = Tool(
        "echo",
        "Echo text",
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
        func=lambda text: _ret(text),
    )
    assert t.spec() == {
        "type": "function",
        "function": {"name": "echo", "description": "Echo text", "parameters": t.parameters},
    }


async def _ret(x):
    return x


async def test_call_publishes_events_and_returns_result():
    bus = EventBus()
    reg = ToolRegistry(bus)
    reg.register(Tool("echo", "d", {"type": "object", "properties": {}}, func=_ret))
    events = []
    bus.subscribe(ToolCall, events.append)
    bus.subscribe(ToolResult, events.append)
    assert await reg.call("echo", {"x": "hi"}) == "hi"
    assert events == [ToolCall("echo", {"x": "hi"}), ToolResult("echo", "hi")]
    assert reg.names() == ["echo"]
    assert reg.specs()[0]["function"]["name"] == "echo"


async def test_unknown_and_failing_tools_do_not_raise():
    reg = ToolRegistry(EventBus())

    async def boom(**kw):
        raise ValueError("bad input")

    reg.register(Tool("boom", "d", {"type": "object", "properties": {}}, func=boom))
    assert await reg.call("nope", {}) == "Unknown tool: nope"
    assert await reg.call("boom", {}) == "Tool boom failed: bad input"


async def test_disabled_tool():
    t = disabled_tool("web_search", "Search the web", "EXA_API_KEY missing")
    assert await t.func(query="x") == "web_search is disabled: EXA_API_KEY missing"


async def test_build_registry_skips_missing_tool_modules(monkeypatch):
    monkeypatch.setattr(tools_module, "TOOL_MODULES", ["does_not_exist"])
    reg = build_registry(ctx())
    assert reg.names() == []


async def test_build_registry_catches_runtime_error_from_make_tools(monkeypatch, caplog):
    fake = types.ModuleType("opencompanion.tools.fake")

    def make_tools(ctx):
        raise RuntimeError("EXA_API_KEY missing")

    fake.make_tools = make_tools
    monkeypatch.setitem(sys.modules, "opencompanion.tools.fake", fake)
    monkeypatch.setattr(tools_module, "TOOL_MODULES", ["fake"])

    with caplog.at_level(logging.WARNING, logger="opencompanion.tools"):
        reg = build_registry(ctx())

    assert reg.names() == []
    assert "tool group fake disabled: EXA_API_KEY missing" in caplog.text
