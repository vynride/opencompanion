# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tool definitions shared by every tool module and the brain."""

from __future__ import annotations

import importlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

import httpx

from opencompanion.bus import EventBus, ToolCall, ToolResult
from opencompanion.config import Config

if TYPE_CHECKING:
    from opencompanion.tools.memory import Memory

log = logging.getLogger("opencompanion.tools")

TOOL_MODULES = ["clock", "memory", "weather", "search", "infra", "laptop", "look"]


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    func: Callable[..., Awaitable[str]]

    def spec(self) -> dict:
        return {
            "type": "function",
            "function": {"name": self.name, "description": self.description, "parameters": self.parameters},
        }


@dataclass
class ToolContext:
    cfg: Config
    bus: EventBus
    http: httpx.AsyncClient
    memory: Memory | None = None
    ask_vision: Callable[[str, bytes], Awaitable[str]] | None = None


def disabled_tool(name: str, description: str, reason: str) -> Tool:
    async def func(**kwargs: Any) -> str:
        return f"{name} is disabled: {reason}"

    return Tool(name, description, {"type": "object", "properties": {}}, func)


class ToolRegistry:
    def __init__(self, bus: EventBus) -> None:
        self.bus = bus
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def names(self) -> list[str]:
        return list(self._tools)

    def specs(self) -> list[dict]:
        return [t.spec() for t in self._tools.values()]

    async def call(self, name: str, args: dict) -> str:
        tool = self._tools.get(name)
        if tool is None:
            return f"Unknown tool: {name}"
        await self.bus.publish(ToolCall(name, dict(args)))
        try:
            result = await tool.func(**args)
        except Exception as e:
            log.exception("tool %s failed", name)
            result = f"Tool {name} failed: {e}"
        await self.bus.publish(ToolResult(name, result))
        return result


def build_registry(ctx: ToolContext) -> ToolRegistry:
    """Build the registry from every tool module in TOOL_MODULES."""
    reg = ToolRegistry(ctx.bus)
    for mod_name in TOOL_MODULES:
        try:
            mod = importlib.import_module(f"opencompanion.tools.{mod_name}")
            tools = mod.make_tools(ctx)
        # Absent module, or make_tools refusing for a missing key or dependency.
        except (ImportError, RuntimeError) as e:
            log.warning("tool group %s disabled: %s", mod_name, e)
            continue
        for tool in tools:
            reg.register(tool)
    return reg
