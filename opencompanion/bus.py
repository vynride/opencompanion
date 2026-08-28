# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""In-process pub/sub of small frozen dataclasses."""

from __future__ import annotations

import inspect
import logging
from collections import defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from opencompanion.state import State

log = logging.getLogger("opencompanion.bus")


@dataclass(frozen=True)
class Wake:
    pass


@dataclass(frozen=True)
class AudioFrame:
    pcm: bytes


@dataclass(frozen=True)
class Transcript:
    text: str


@dataclass(frozen=True)
class Reply:
    text: str


@dataclass(frozen=True)
class Say:
    """Speech that does not end a turn."""

    text: str


@dataclass(frozen=True)
class Caption:
    """Reply text with the estimated speech duration, for word-by-word reveal."""

    text: str
    seconds: float


@dataclass(frozen=True)
class PlaybackDone:
    pass


@dataclass(frozen=True)
class ToolCall:
    name: str
    args: dict = field(default_factory=dict)


@dataclass(frozen=True)
class ToolResult:
    name: str
    result: str


@dataclass(frozen=True)
class Mouth:
    level: float


@dataclass(frozen=True)
class StateChanged:
    state: State


@dataclass(frozen=True)
class Sense:
    kind: str
    value: float | str


@dataclass(frozen=True)
class TimerDone:
    label: str


@dataclass(frozen=True)
class Error:
    source: str
    message: str


Handler = Callable[[Any], Awaitable[None] | None]


class EventBus:
    def __init__(self) -> None:
        self._handlers: dict[type, list[Handler]] = defaultdict(list)

    def subscribe(self, event_type: type, handler: Handler) -> Callable[[], None]:
        self._handlers[event_type].append(handler)

        def unsubscribe() -> None:
            try:
                self._handlers[event_type].remove(handler)
            except ValueError:
                pass

        return unsubscribe

    async def publish(self, event: Any) -> None:
        for handler in list(self._handlers.get(type(event), [])):
            try:
                result = handler(event)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                log.exception("handler %r failed on %r", handler, event)
