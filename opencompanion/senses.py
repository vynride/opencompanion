# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Termux:API sensor polling, tap detection, touch button via getevent, screen dimming."""

from __future__ import annotations

import asyncio
import logging
import math

from opencompanion import platform
from opencompanion.bus import EventBus, Sense, StateChanged
from opencompanion.config import Config
from opencompanion.state import State

log = logging.getLogger("opencompanion.senses")
# per kind, a case-insensitive substring of the termux-sensor name
DEFAULT_NAMES = {"proximity": "proximity", "light": "light", "accel": "accelerometer"}
KINDS = {needle: kind for kind, needle in DEFAULT_NAMES.items()}
MAX_BACKOFF_S = 30.0
NEAR_CM = 5.0


def parse_sensors(data: dict, kinds: dict[str, str] = KINDS) -> dict[str, list[float]]:
    """Map termux-sensor JSON to values per kind, matching `kinds` (needle -> kind)
    case-insensitively against each sensor name.
    """
    out: dict[str, list[float]] = {}
    for name, body in data.items():
        for needle, kind in kinds.items():
            if needle in name.lower() and kind not in out:
                out[kind] = [float(x) for x in body.get("values", [])]
    return out


def magnitude(v: list[float]) -> float:
    return math.sqrt(sum(x * x for x in v))


class Senses:
    """Polls proximity, light and accelerometer, publishes `Sense` events, and
    follows the ambient light level with the backlight.

    Proximity and light events are edge-triggered; a failed read backs off up
    to MAX_BACKOFF_S.
    """

    def __init__(
        self,
        bus: EventBus,
        poll_s: float = 1.0,
        tap_threshold: float = 4.0,
        dark_lux: float = 5,
        dim: int = 10,
        normal: int = 80,
        near_cm: float = NEAR_CM,
        read=platform.sensor_read,
        set_brightness=platform.set_brightness,
        battery=platform.battery_status,
        sleep: int = 1,
        names: dict[str, str] | None = None,
    ) -> None:
        self.bus = bus
        self.names = {**DEFAULT_NAMES, **(names or {})}
        self._kinds = {needle.lower(): kind for kind, needle in self.names.items()}
        self._reported = False
        self.poll_s = poll_s
        self.tap_threshold = tap_threshold
        self.dark_lux = dark_lux
        self.dim = dim
        self.normal = normal
        self.sleep = sleep
        self._asleep = False
        # subscribed here so the backlight follows state without the poller
        bus.subscribe(StateChanged, self.on_state)
        self.near_cm = near_cm
        self.read = read
        self.set_brightness = set_brightness
        self.battery = battery
        self.last: dict[str, float | str] = {}
        self._prev_mag: float | None = None
        self._dark: bool | None = None
        self._near: bool | None = None
        self._polls = 0
        self._backoff = poll_s

    def _ambient(self) -> int:
        return self.dim if self._dark else self.normal

    async def on_state(self, e: StateChanged) -> None:
        asleep = e.state == State.SLEEPING
        if asleep == self._asleep:
            return
        self._asleep = asleep
        level = self.sleep if asleep else self._ambient()
        log.info("backlight -> %d (%s)", level, "sleeping" if asleep else e.state.value)
        try:
            await self.set_brightness(level)
        except platform.PlatformError as err:
            log.warning("brightness failed: %s", err)

    async def poll_once(self) -> None:
        if self._polls % 30 == 0:
            try:
                info = await self.battery()
                self.last["battery"] = f"{int(info.get('percentage', -1))}%"
            except (platform.PlatformError, ValueError) as e:
                log.warning("battery read failed: %s", e)
        self._polls += 1
        try:
            data = parse_sensors(await self.read(list(self.names.values())), self._kinds)
        except (platform.PlatformError, ValueError) as e:
            log.warning("sensor read failed: %s", e)
            self._backoff = min(max(self._backoff * 2, self.poll_s * 2), MAX_BACKOFF_S)
            return
        self._backoff = self.poll_s
        if not self._reported:
            self._reported = True
            missing = [k for k in self.names if k not in data]
            log.info(
                "sensors matched: %s%s",
                ", ".join(sorted(data)) or "none",
                f" (no match for {', '.join(missing)}; see senses.sensor_names)" if missing else "",
            )
        if "proximity" in data and data["proximity"]:
            cm = data["proximity"][0]
            self.last["proximity"] = cm
            near = cm < self.near_cm
            if near != self._near:
                self._near = near
                await self.bus.publish(Sense("proximity", cm))
        if "light" in data and data["light"]:
            lux = data["light"][0]
            self.last["light"] = lux
            dark = lux < self.dark_lux
            if dark != self._dark:
                self._dark = dark
                await self.bus.publish(Sense("light", lux))
                if not self._asleep:
                    try:
                        await self.set_brightness(self._ambient())
                    except platform.PlatformError as e:
                        log.warning("brightness failed: %s", e)
        if "accel" in data and data["accel"]:
            mag = magnitude(data["accel"])
            if self._prev_mag is not None and abs(mag - self._prev_mag) >= self.tap_threshold:
                await self.bus.publish(Sense("accel_tap", abs(mag - self._prev_mag)))
            self._prev_mag = mag

    async def run(self) -> None:
        # restore ambient brightness on startup
        try:
            await self.set_brightness(self._ambient())
        except platform.PlatformError as e:
            log.warning("brightness failed: %s", e)
        while True:
            try:
                await self.poll_once()
            except platform.PlatformError as e:
                # backstop: the poll loop must never die
                log.warning("poll failed: %s", e)
            await asyncio.sleep(self._backoff)


class TouchWatcher:
    """Publishes `Sense("touch", 1)` for each key-down on a getevent input device.

    `device` is either a `/dev/input/...` path or a substring of the device name
    to resolve. An empty or unresolvable `device` makes `run()` return instead of
    raising.
    """

    def __init__(self, bus: EventBus, device: str) -> None:
        self.bus = bus
        self.device = device

    async def run(self) -> None:
        if not self.device:
            return
        device = self.device
        if not device.startswith("/dev/"):
            resolved = await platform.find_input_device(device)
            if resolved is None:
                log.warning("no input device matching %r found", device)
                return
            device = resolved
        proc = await platform.spawn_stream(platform.getevent_command(device))
        assert proc.stdout is not None
        async for raw in proc.stdout:
            line = raw.decode(errors="replace")
            if "EV_KEY" in line and "DOWN" in line:
                await self.bus.publish(Sense("touch", 1))
        raise RuntimeError("getevent ended")


def from_config(bus: EventBus, cfg: Config) -> tuple[Senses, TouchWatcher]:
    s = Senses(
        bus,
        float(cfg.get("senses.poll_s", 1.0)),
        float(cfg.get("senses.tap_threshold", 4.0)),
        float(cfg.get("senses.dark_lux", 5)),
        int(cfg.get("senses.dim_brightness", 10)),
        int(cfg.get("senses.normal_brightness", 80)),
        float(cfg.get("senses.proximity_near_cm", NEAR_CM)),
        sleep=int(cfg.get("senses.sleep_brightness", 1)),
        names=dict(cfg.get("senses.sensor_names") or {}),
    )
    return s, TouchWatcher(bus, cfg.get("senses.touch_device", "") or "")
