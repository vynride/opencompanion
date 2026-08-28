# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Microphone capture subprocess turned into AudioFrame events."""

from __future__ import annotations

import asyncio
import logging
from time import monotonic

from opencompanion import platform
from opencompanion.bus import AudioFrame, EventBus
from opencompanion.config import Config

log = logging.getLogger("opencompanion.audio")


class AudioCapture:
    def __init__(
        self,
        bus: EventBus,
        command: list[str],
        frame_bytes: int = 2560,
        pulse_start: list[str] | None = None,
        silence_restart_s: float = 10,
    ) -> None:
        self.bus = bus
        self.command = command
        self.frame_bytes = frame_bytes
        self.pulse_start = pulse_start
        self.silence_restart_s = silence_restart_s
        self.frames_read = 0
        self._silent_frame = bytes(frame_bytes)

    async def run(self) -> None:
        if self.pulse_start:
            await platform.start_pulseaudio(self.pulse_start)
            await platform.ensure_mic_source()
        proc = await platform.spawn_stream(self.command)
        log.info("capture started: %s", " ".join(self.command))
        assert proc.stdout is not None
        silence_since: float | None = None
        try:
            while True:
                try:
                    pcm = await proc.stdout.readexactly(self.frame_bytes)
                except asyncio.IncompleteReadError:
                    log.warning("capture process ended; restarting")
                    break
                self.frames_read += 1
                await self.bus.publish(AudioFrame(pcm))

                now = monotonic()
                if pcm == self._silent_frame:
                    if silence_since is None:
                        silence_since = now
                    elif now - silence_since >= self.silence_restart_s:
                        # Android drops Termux's mic source when another app is
                        # foreground; parec then reads silence instead of failing.
                        log.warning("no audio for %.0fs; restarting capture", now - silence_since)
                        if self.pulse_start:
                            await platform.ensure_mic_source()
                        break
                else:
                    silence_since = None
        finally:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
        raise RuntimeError("capture ended")


def from_config(bus: EventBus, cfg: Config) -> AudioCapture:
    rate = int(cfg.get("audio.rate", 16000))
    frame_ms = int(cfg.get("audio.frame_ms", 80))
    return AudioCapture(
        bus,
        list(cfg.get("audio.capture_command")),
        rate * frame_ms // 1000 * 2,
        list(cfg.get("audio.pulse_start") or []) if platform.is_termux() else None,
        float(cfg.get("audio.silence_restart_s", 10)),
    )
