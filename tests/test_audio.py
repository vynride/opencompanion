# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import sys
from unittest.mock import AsyncMock

import pytest

from opencompanion.audio import AudioCapture, from_config
from opencompanion.bus import AudioFrame, EventBus
from opencompanion.config import Config


class _FakeStdout:
    """Hands out pre-baked frames, then raises IncompleteReadError like a closed pipe."""

    def __init__(self, frames: list[bytes]) -> None:
        self._frames = list(frames)

    async def readexactly(self, n: int) -> bytes:
        if not self._frames:
            raise asyncio.IncompleteReadError(partial=b"", expected=n)
        return self._frames.pop(0)


class _FakeProcess:
    def __init__(self, frames: list[bytes]) -> None:
        self.stdout = _FakeStdout(frames)
        self.returncode: int | None = None
        self.killed = False

    def kill(self) -> None:
        self.killed = True
        self.returncode = -9

    async def wait(self) -> int:
        return self.returncode or 0


async def test_reads_fixed_frames_from_command(tmp_path):
    raw = tmp_path / "in.raw"
    raw.write_bytes(bytes(range(256)) * 25)  # 6400 bytes = 2.5 frames of 2560
    bus = EventBus()
    frames = []
    bus.subscribe(AudioFrame, frames.append)
    cap = AudioCapture(
        bus, [sys.executable, "-c", f"import sys; sys.stdout.buffer.write(open({str(raw)!r},'rb').read())"]
    )
    with pytest.raises(RuntimeError, match="capture ended"):
        await cap.run()
    assert len(frames) == 2 and all(len(f.pcm) == 2560 for f in frames)
    assert cap.frames_read == 2


async def test_pulse_start_is_invoked(monkeypatch):
    calls = []

    async def fake_pulse(cmd):
        calls.append(cmd)

    monkeypatch.setattr("opencompanion.platform.start_pulseaudio", fake_pulse)
    ensure = AsyncMock(return_value=True)
    monkeypatch.setattr("opencompanion.platform.ensure_mic_source", ensure)
    cap = AudioCapture(EventBus(), [sys.executable, "-c", "pass"], pulse_start=["pulseaudio", "--start"])
    with pytest.raises(RuntimeError):
        await cap.run()
    assert calls == [["pulseaudio", "--start"]]
    ensure.assert_awaited_once()


def test_from_config_frame_size():
    cfg = Config({"audio": {"rate": 16000, "frame_ms": 80, "capture_command": ["cat"]}}, {})
    cap = from_config(EventBus(), cfg)
    assert cap.frame_bytes == 2560 and cap.command == ["cat"]
    assert cap.silence_restart_s == 10


def test_from_config_reads_silence_restart_s():
    cfg = Config(
        {"audio": {"rate": 16000, "frame_ms": 80, "capture_command": ["cat"], "silence_restart_s": 3}}, {}
    )
    cap = from_config(EventBus(), cfg)
    assert cap.silence_restart_s == 3


async def test_process_exit_without_pulse_start_skips_ensure_mic_source(monkeypatch):
    ensure = AsyncMock(return_value=True)
    monkeypatch.setattr("opencompanion.platform.ensure_mic_source", ensure)
    cap = AudioCapture(EventBus(), [sys.executable, "-c", "pass"])
    with pytest.raises(RuntimeError, match="capture ended"):
        await cap.run()
    ensure.assert_not_awaited()


async def test_silence_watchdog_restarts_capture(monkeypatch):
    zero = bytes(2560)
    proc = _FakeProcess([zero, zero, zero, zero])
    monkeypatch.setattr("opencompanion.platform.spawn_stream", AsyncMock(return_value=proc))
    monkeypatch.setattr("opencompanion.platform.start_pulseaudio", AsyncMock())
    ensure = AsyncMock(return_value=True)
    monkeypatch.setattr("opencompanion.platform.ensure_mic_source", ensure)
    times = iter([0.0, 3.0, 6.0])
    monkeypatch.setattr("opencompanion.audio.monotonic", lambda: next(times))

    bus = EventBus()
    frames = []
    bus.subscribe(AudioFrame, frames.append)
    cap = AudioCapture(bus, ["parec"], pulse_start=["pulseaudio", "--start"], silence_restart_s=5)
    with pytest.raises(RuntimeError, match="capture ended"):
        await cap.run()

    assert cap.frames_read == 3
    assert len(frames) == 3  # silent frames are still published
    assert ensure.await_count == 2  # once before spawn, once when the watchdog trips
    assert proc.killed is True


async def test_intermittent_silence_does_not_accumulate(monkeypatch):
    zero = bytes(2560)
    loud = bytes([1]) * 2560
    proc = _FakeProcess([zero, loud, zero, zero])
    monkeypatch.setattr("opencompanion.platform.spawn_stream", AsyncMock(return_value=proc))
    monkeypatch.setattr("opencompanion.platform.start_pulseaudio", AsyncMock())
    ensure = AsyncMock(return_value=True)
    monkeypatch.setattr("opencompanion.platform.ensure_mic_source", ensure)
    times = iter([0.0, 1.0, 1.5, 2.4])
    monkeypatch.setattr("opencompanion.audio.monotonic", lambda: next(times))

    cap = AudioCapture(EventBus(), ["parec"], pulse_start=["pulseaudio", "--start"], silence_restart_s=2)
    with pytest.raises(RuntimeError, match="capture ended"):
        await cap.run()

    assert cap.frames_read == 4  # EOF, not the watchdog
    assert ensure.await_count == 1  # pre-spawn only
