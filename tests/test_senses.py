# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import logging
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from opencompanion import platform
from opencompanion.bus import EventBus, Sense, StateChanged
from opencompanion.config import Config
from opencompanion.senses import NEAR_CM, Senses, TouchWatcher, from_config, magnitude, parse_sensors
from opencompanion.state import State

RAW = {
    "Proximity Sensor": {"values": [5.0]},
    "Light Sensor": {"values": [120.0]},
    "Accelerometer": {"values": [0.1, 0.2, 9.8]},
}


def test_parse_sensors_by_substring():
    p = parse_sensors(RAW)
    assert p == {"proximity": [5.0], "light": [120.0], "accel": [0.1, 0.2, 9.8]}


def test_magnitude():
    assert round(magnitude([3, 4, 0]), 6) == 5


async def test_poll_publishes_and_detects_tap_and_dims():
    bus = EventBus()
    seen = []
    bus.subscribe(Sense, seen.append)
    reads = [
        dict(RAW),
        {**RAW, "Accelerometer": {"values": [0.1, 0.2, 15.0]}, "Light Sensor": {"values": [1.0]}},
        {**RAW, "Light Sensor": {"values": [1.0]}},
    ]
    read = AsyncMock(side_effect=reads)
    bright = AsyncMock()
    s = Senses(
        bus,
        tap_threshold=4.0,
        dark_lux=5,
        dim=10,
        normal=80,
        read=read,
        set_brightness=bright,
        battery=AsyncMock(return_value={"percentage": 80, "status": "CHARGING"}),
    )
    await s.poll_once()
    assert seen == [Sense("proximity", 5.0), Sense("light", 120.0)]
    assert s.last["battery"] == "80%"
    await s.poll_once()
    assert Sense("accel_tap", 5.2) in [Sense(e.kind, round(float(e.value), 1)) for e in seen]
    assert bright.await_args_list[-1].args == (10,)
    await s.poll_once()
    assert bright.await_count == 2  # unchanged darkness writes no new level
    assert s.last["light"] == 1.0


async def test_repeated_readings_publish_one_sense():
    """An unchanging reading must publish one Sense, not one per poll."""
    bus = EventBus()
    seen = []
    bus.subscribe(Sense, seen.append)
    # Same far/bright reading four times, then one near/dark reading.
    near_dark = {
        "Proximity Sensor": {"values": [0.0]},
        "Light Sensor": {"values": [1.0]},
        "Accelerometer": {"values": [0.1, 0.2, 9.8]},
    }
    read = AsyncMock(side_effect=[dict(RAW), dict(RAW), dict(RAW), dict(RAW), near_dark])
    s = Senses(
        bus,
        dark_lux=5,
        read=read,
        set_brightness=AsyncMock(),
        battery=AsyncMock(return_value={"percentage": 80}),
    )
    for _ in range(4):
        await s.poll_once()
    assert seen == [Sense("proximity", 5.0), Sense("light", 120.0)]
    assert s.last["light"] == 120.0
    await s.poll_once()
    assert seen[2:] == [Sense("proximity", 0.0), Sense("light", 1.0)]


async def test_from_config_reads_the_proximity_threshold():
    cfg = Config({"senses": {"proximity_near_cm": 2.5}}, {}, root=Path("."))
    s, _ = from_config(EventBus(), cfg)
    assert s.near_cm == 2.5
    assert from_config(EventBus(), Config({}, {}, root=Path(".")))[0].near_cm == NEAR_CM


async def test_platform_error_is_logged_not_fatal(caplog):
    s = Senses(
        EventBus(),
        read=AsyncMock(side_effect=platform.PlatformError("termux-sensor not found")),
        set_brightness=AsyncMock(),
        battery=AsyncMock(side_effect=platform.PlatformError("no battery")),
    )
    await s.poll_once()
    assert "termux-sensor" in caplog.text
    assert "battery" not in s.last


async def test_run_backoff_doubles_caps_and_resets(monkeypatch):
    fail = platform.PlatformError("boom")
    items = [fail, fail, fail, fail, fail, fail, dict(RAW), fail]

    async def fake_read(names):
        item = items.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    s = Senses(
        EventBus(),
        poll_s=1.0,
        read=fake_read,
        set_brightness=AsyncMock(),
        battery=AsyncMock(return_value={"percentage": 1}),
    )

    sleeps: list[float] = []

    async def fake_sleep(secs):
        sleeps.append(secs)
        if len(sleeps) >= 8:
            raise asyncio.CancelledError()

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)

    with pytest.raises(asyncio.CancelledError):
        await s.run()

    # Six failures double and cap at 30.0, the seventh read resets to poll_s.
    assert sleeps == [2.0, 4.0, 8.0, 16.0, 30.0, 30.0, 1.0, 2.0]


async def test_touch_watcher_uses_device_path_directly(monkeypatch):
    class _FakeStdout:
        def __aiter__(self):
            return self

        async def __anext__(self):
            raise StopAsyncIteration

    class _FakeProc:
        stdout = _FakeStdout()

    spawn = AsyncMock(return_value=_FakeProc())
    resolve = AsyncMock()
    monkeypatch.setattr(platform, "spawn_stream", spawn)
    monkeypatch.setattr(platform, "find_input_device", resolve)
    tw = TouchWatcher(EventBus(), "/dev/input/event7")
    with pytest.raises(RuntimeError, match="getevent ended"):
        await tw.run()
    resolve.assert_not_awaited()
    assert spawn.call_args.args[0] == platform.getevent_command("/dev/input/event7")


async def test_touch_watcher_resolves_substring_to_device_path(monkeypatch):
    class _FakeStdout:
        def __aiter__(self):
            return self

        async def __anext__(self):
            raise StopAsyncIteration

    class _FakeProc:
        stdout = _FakeStdout()

    spawn = AsyncMock(return_value=_FakeProc())
    resolve = AsyncMock(return_value="/dev/input/event3")
    monkeypatch.setattr(platform, "spawn_stream", spawn)
    monkeypatch.setattr(platform, "find_input_device", resolve)
    tw = TouchWatcher(EventBus(), "fingerprint")
    with pytest.raises(RuntimeError, match="getevent ended"):
        await tw.run()
    resolve.assert_awaited_once_with("fingerprint")
    assert spawn.call_args.args[0] == platform.getevent_command("/dev/input/event3")


async def test_touch_watcher_warns_and_returns_when_device_not_found(monkeypatch, caplog):
    resolve = AsyncMock(return_value=None)
    spawn = AsyncMock()
    monkeypatch.setattr(platform, "find_input_device", resolve)
    monkeypatch.setattr(platform, "spawn_stream", spawn)
    tw = TouchWatcher(EventBus(), "no-such-device")
    await tw.run()
    spawn.assert_not_awaited()
    assert "no-such-device" in caplog.text


# --- backlight ---


async def test_sleeping_dims_backlight_and_waking_restores_ambient():
    bus = EventBus()
    bright = AsyncMock()
    Senses(
        bus,
        dim=10,
        normal=80,
        sleep=1,
        read=AsyncMock(return_value=RAW),
        set_brightness=bright,
        battery=AsyncMock(return_value={"percentage": 50, "status": "DISCHARGING"}),
    )
    await bus.publish(StateChanged(State.SLEEPING))
    assert bright.await_args_list[-1].args == (1,)
    await bus.publish(StateChanged(State.SLEEPING))  # repeated: no extra write
    assert bright.await_count == 1
    await bus.publish(StateChanged(State.IDLE))
    assert bright.await_args_list[-1].args == (80,)
    await bus.publish(StateChanged(State.LISTENING))  # already awake: no write
    assert bright.await_count == 2


async def test_light_changes_while_asleep_do_not_raise_backlight():
    bus = EventBus()
    bright = AsyncMock()
    dark = {**RAW, "Light Sensor": {"values": [1.0]}}
    s = Senses(
        bus,
        dim=10,
        normal=80,
        sleep=1,
        read=AsyncMock(side_effect=[dark, RAW]),
        set_brightness=bright,
        battery=AsyncMock(return_value={"percentage": 50, "status": "X"}),
    )
    await bus.publish(StateChanged(State.SLEEPING))
    await s.poll_once()  # dark
    await s.poll_once()  # bright room again
    assert [c.args for c in bright.await_args_list] == [(1,)]
    await bus.publish(StateChanged(State.IDLE))
    assert bright.await_args_list[-1].args == (80,)  # room is bright again


def test_from_config_reads_sleep_brightness():
    s, _ = from_config(EventBus(), Config({"senses": {"sleep_brightness": 3}}, {}))
    assert s.sleep == 3


async def test_run_restores_ambient_brightness_on_startup():
    bright = AsyncMock()
    s = Senses(
        EventBus(),
        dim=10,
        normal=80,
        read=AsyncMock(return_value=RAW),
        set_brightness=bright,
        battery=AsyncMock(return_value={"percentage": 50, "status": "X"}),
    )
    task = asyncio.create_task(s.run())
    await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert bright.await_args_list[0].args == (80,)


# --- per-device sensor names ---


async def test_sensor_names_override_which_sensors_are_read_and_matched(caplog):
    raw = {"prox chip alsprx": {"values": [0.0]}, "Accelerometer": {"values": [0.1, 0.2, 9.8]}}
    read = AsyncMock(return_value=raw)
    bus = EventBus()
    seen = []
    bus.subscribe(Sense, seen.append)
    s = Senses(
        bus,
        read=read,
        set_brightness=AsyncMock(),
        battery=AsyncMock(return_value={"percentage": 50, "status": "X"}),
        names={"proximity": "alsprx"},
    )
    with caplog.at_level(logging.INFO, logger="opencompanion.senses"):
        await s.poll_once()
    assert read.await_args.args[0] == ["alsprx", "light", "accelerometer"]
    assert seen == [Sense("proximity", 0.0)]
    assert "sensors matched: accel, proximity (no match for light" in caplog.text


def test_from_config_reads_sensor_names():
    s, _ = from_config(EventBus(), Config({"senses": {"sensor_names": {"proximity": "alsprx"}}}, {}))
    assert s.names == {"proximity": "alsprx", "light": "light", "accel": "accelerometer"}
