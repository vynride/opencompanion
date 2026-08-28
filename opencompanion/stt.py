# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Record until silence after a wake, then transcribe."""

from __future__ import annotations

import argparse
import asyncio
import io
import logging
import math
import struct
import sys
import wave
from pathlib import Path

import httpx

from opencompanion.bus import AudioFrame, Error, EventBus, PlaybackDone, Transcript, Wake
from opencompanion.config import Config, Service, load_config
from opencompanion.retry import ATTEMPTS, retry_delay, should_retry

log = logging.getLogger("opencompanion.stt")


class EnergyVAD:
    """Fallback VAD: a frame is speech when its RMS clears a fixed threshold."""

    def __init__(self, threshold: int = 500) -> None:
        self.threshold = threshold

    def is_speech(self, frame: bytes) -> bool:
        n = len(frame) // 2
        if n == 0:
            return False
        samples = struct.unpack(f"<{n}h", frame[: n * 2])
        return math.sqrt(sum(s * s for s in samples) / n) > self.threshold


class WebrtcVAD:
    """webrtcvad over 20 ms sub-frames; the frame is speech if any sub-frame is."""

    def __init__(self, aggressiveness: int = 2, rate: int = 16000) -> None:
        import webrtcvad  # C extension; optional, hence the local import

        self.vad = webrtcvad.Vad(aggressiveness)
        self.rate = rate
        self.sub = int(rate * 0.02) * 2  # 20 ms of s16le

    def is_speech(self, frame: bytes) -> bool:
        return any(
            self.vad.is_speech(frame[i : i + self.sub], self.rate)
            for i in range(0, len(frame) - self.sub + 1, self.sub)
        )


def make_vad(aggressiveness: int) -> EnergyVAD | WebrtcVAD:
    try:
        return WebrtcVAD(aggressiveness)
    except ImportError:
        log.warning("webrtcvad not installed; using energy VAD")
        return EnergyVAD()


class SilenceDetector:
    """Decides when a recording is over: trailing silence, or the hard length cap."""

    def __init__(
        self,
        vad,
        *,
        frame_ms: int = 80,
        silence_ms: int = 700,
        max_ms: int = 8000,
        min_ms: int = 300,
        onset_ms: int = 0,
    ) -> None:
        self.vad = vad
        self.frame_ms = frame_ms
        self.silence_frames = max(1, silence_ms // frame_ms)
        self.max_frames = max(1, max_ms // frame_ms)
        self.min_frames = max(1, min_ms // frame_ms)
        # 0 disables the speech-onset timeout; only follow-up turns set it
        self.onset_frames = max(1, onset_ms // frame_ms) if onset_ms > 0 else 0
        self._frames: list[bytes] = []
        self._quiet_run = 0
        self.speech_seen = False

    def feed(self, frame: bytes) -> bool:
        """Add a frame; True means recording should stop."""
        self._frames.append(frame)
        if self.vad.is_speech(frame):
            self.speech_seen = True
            self._quiet_run = 0
        else:
            self._quiet_run += 1
        if self.onset_frames and not self.speech_seen and len(self._frames) >= self.onset_frames:
            return True
        # Only end on trailing silence once speech has started.
        stop_on_silence = self.speech_seen and self._quiet_run >= self.silence_frames
        return stop_on_silence or len(self._frames) >= self.max_frames

    def pcm(self) -> bytes:
        return b"".join(self._frames)

    def has_enough(self) -> bool:
        return self.speech_seen and len(self._frames) >= self.min_frames


def wav_bytes(pcm: bytes, rate: int = 16000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


class Transcriber:
    """WAV bytes -> text via `audio/transcriptions`."""

    def __init__(self, http: httpx.AsyncClient, service: Service, language: str = "en") -> None:
        self.http = http
        self.service = service
        self.language = language

    async def transcribe(self, wav: bytes) -> str:
        url = self.service.url("audio/transcriptions")
        for attempt in range(1, ATTEMPTS + 1):
            r = await self.http.post(
                url,
                headers=self.service.headers(),
                files={"file": ("audio.wav", wav, "audio/wav")},
                data={"model": self.service.model, "response_format": "json", "language": self.language},
                timeout=30,
            )
            if should_retry(r, attempt):
                log.warning("transcribe %d, retrying (%d/%d)", r.status_code, attempt, ATTEMPTS)
                await asyncio.sleep(retry_delay(r, attempt))
                continue
            r.raise_for_status()
            return (r.json().get("text") or "").strip()
        raise AssertionError("unreachable")


def from_config(cfg: Config, http: httpx.AsyncClient) -> Transcriber | None:
    service = cfg.service("transcribe")
    if service is None:
        log.warning("transcription disabled: set openai.transcribe_model and OPENAI_API_KEY")
        return None
    return Transcriber(http, service, cfg.get("stt.language"))


class Listener:
    """One turn per wake: buffer audio until silence, transcribe, publish the Transcript.

    A PlaybackDone opens a short follow-up listen that needs no wake word;
    state.followup_s <= 0 disables it.
    """

    def __init__(
        self,
        bus: EventBus,
        cfg: Config,
        stt: Transcriber | None,
        vad=None,
        frame_timeout_s: float | None = None,
    ) -> None:
        self.bus = bus
        self.cfg = cfg
        self.stt = stt
        self.vad = vad or make_vad(int(cfg.get("stt.vad_aggressiveness", 3)))
        self.rate = int(cfg.get("audio.rate", 16000))
        self.max_ms = int(cfg.get("stt.max_ms", 8000))
        self.followup_s = float(cfg.get("state.followup_s", 6.0))
        # How long to wait for a frame before giving up on a silent capture.
        self.frame_timeout_s = frame_timeout_s if frame_timeout_s is not None else self.max_ms / 1000 + 2
        self._queue: asyncio.Queue[bytes] | None = None
        self._turn_in_progress = False
        self._followup_pending = False

    def start(self) -> None:
        self.bus.subscribe(Wake, self._on_wake)
        self.bus.subscribe(AudioFrame, self._on_frame)
        self.bus.subscribe(PlaybackDone, self._on_playback_done)

    def _on_frame(self, e: AudioFrame) -> None:
        if self._queue is not None:
            self._queue.put_nowait(e.pcm)

    async def record(self, onset_ms: int = 0) -> bytes:
        """Collect frames until the detector says stop; PCM, or b"" if too short.

        `onset_ms > 0` (follow-up turns) gives up if no speech starts in time.
        """
        det = SilenceDetector(
            self.vad,
            frame_ms=int(self.cfg.get("audio.frame_ms", 80)),
            silence_ms=int(self.cfg.get("stt.silence_ms", 700)),
            max_ms=self.max_ms,
            min_ms=int(self.cfg.get("stt.min_ms", 300)),
            onset_ms=onset_ms,
        )
        if self._queue is None:
            self._queue = asyncio.Queue()
        try:
            while True:
                frame = await asyncio.wait_for(self._queue.get(), timeout=self.frame_timeout_s)
                if det.feed(frame):
                    break
        except TimeoutError:
            log.warning("no audio frames arrived while listening")
        finally:
            self._queue = None  # stop buffering frames during transcription
        return det.pcm() if det.has_enough() else b""

    def _on_wake(self, e: Wake) -> None:
        # Do not await here: publish() would block the frame handlers that feed the queue.
        if self._turn_in_progress:
            return  # recording, or still transcribing the previous turn
        self._begin_turn(onset_ms=0)

    def _on_playback_done(self, e: PlaybackDone) -> None:
        """A reply just finished playing: open a follow-up listen (no wake word)."""
        if self.followup_s <= 0:
            return
        if self._turn_in_progress:
            # PlaybackDone arrives while the turn is still in progress, so defer.
            self._followup_pending = True
        else:
            self._begin_turn(onset_ms=int(self.followup_s * 1000))

    def _begin_turn(self, *, onset_ms: int) -> None:
        self._turn_in_progress = True
        self._queue = asyncio.Queue()
        asyncio.get_running_loop().create_task(self._turn(onset_ms))

    async def _turn(self, onset_ms: int) -> None:
        # Hold the guard until the Transcript/Error is published.
        try:
            await self._record_and_transcribe(onset_ms)
        finally:
            self._turn_in_progress = False
            if self._followup_pending:
                self._followup_pending = False
                self._begin_turn(onset_ms=int(self.followup_s * 1000))

    async def _record_and_transcribe(self, onset_ms: int = 0) -> None:
        pcm = await self.record(onset_ms)
        if not pcm:
            # a silent follow-up window publishes nothing
            if onset_ms:
                return
            await self.bus.publish(Transcript(""))
            return
        if self.stt is None:
            log.info("no stt configured; dropping %d bytes", len(pcm))
            await self.bus.publish(Transcript(""))
            return
        try:
            text = await self.stt.transcribe(wav_bytes(pcm, self.rate))
        except httpx.HTTPError as ex:
            log.error("transcribe failed: %s", ex)
            await self.bus.publish(Error("transcription", str(ex)))
            return
        await self.bus.publish(Transcript(text))


async def _cli(path: str) -> None:
    cfg = load_config()
    async with httpx.AsyncClient() as http:
        stt = from_config(cfg, http)
        if stt is None:
            sys.exit("transcription not configured (openai.transcribe_model, OPENAI_API_KEY)")
        print(await stt.transcribe(Path(path).read_bytes()))


def main() -> None:
    p = argparse.ArgumentParser(description="Transcribe a WAV file")
    p.add_argument("wav")
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO)
    asyncio.run(_cli(a.wav))


if __name__ == "__main__":
    main()
