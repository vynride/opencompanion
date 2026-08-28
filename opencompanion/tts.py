# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Text-to-speech playback with a mouth envelope for the face."""

from __future__ import annotations

import argparse
import asyncio
import io
import logging
import math
import struct
import sys
import tempfile
import wave
from collections.abc import AsyncIterator, Awaitable, Callable
from pathlib import Path

import httpx

from opencompanion import platform
from opencompanion.bus import Caption, Error, EventBus, Mouth, PlaybackDone, Reply, Say
from opencompanion.config import Config, Service, load_config
from opencompanion.retry import ATTEMPTS, retry_delay, should_retry

log = logging.getLogger("opencompanion.tts")

# Streamed PCM format: 24 kHz, 16-bit signed LE, mono, no header.
PCM_RATE = 24000
# Floor for the running mouth-envelope peak, so a quiet opening window doesn't saturate.
_MOUTH_PEAK_FLOOR = 6000.0


class Speech:
    """Text -> audio via `audio/speech`."""

    def __init__(
        self,
        http: httpx.AsyncClient,
        service: Service,
        voice: str,
        instructions: str = "",
        speed: float = 1.0,
    ) -> None:
        self.http = http
        self.service = service
        self.voice = voice
        self.instructions = instructions
        self.speed = speed

    @property
    def url(self) -> str:
        return self.service.url("audio/speech")

    def _body(self, text: str, response_format: str) -> dict:
        body = {
            "model": self.service.model,
            "input": text,
            "voice": self.voice,
            "speed": self.speed,
            "response_format": response_format,
        }
        if self.instructions:
            body["instructions"] = self.instructions
        return body

    async def synthesize(self, text: str) -> bytes:
        body = self._body(text, "wav")
        for attempt in range(1, ATTEMPTS + 1):
            r = await self.http.post(self.url, headers=self.service.headers(), json=body, timeout=30)
            if should_retry(r, attempt):
                log.warning("speech %d, retrying (%d/%d)", r.status_code, attempt, ATTEMPTS)
                await asyncio.sleep(retry_delay(r, attempt))
                continue
            r.raise_for_status()
            return r.content
        raise AssertionError("unreachable")

    async def stream(self, text: str) -> AsyncIterator[bytes]:
        """Yield raw PCM chunks as they arrive. Retries apply to the initial
        response only; a failure mid-stream ends the utterance."""
        body = self._body(text, "pcm")
        for attempt in range(1, ATTEMPTS + 1):
            async with self.http.stream(
                "POST", self.url, headers=self.service.headers(), json=body, timeout=60
            ) as r:
                if should_retry(r, attempt):
                    log.warning("speech stream %d, retrying (%d/%d)", r.status_code, attempt, ATTEMPTS)
                    await asyncio.sleep(retry_delay(r, attempt))
                    continue
                r.raise_for_status()
                async for chunk in r.aiter_bytes():
                    if chunk:
                        yield chunk
                return


def pcm_rms(pcm: bytes) -> float:
    """RMS amplitude of one raw 16-bit mono LE PCM chunk (0.0 if empty)."""
    n = len(pcm) // 2
    if n == 0:
        return 0.0
    samples = struct.unpack(f"<{n}h", pcm[: n * 2])
    return math.sqrt(sum(s * s for s in samples) / n)


def caption_seconds(text: str, wps: float = 2.6) -> float:
    """Rough speaking time of `text`, used to pace the caption reveal."""
    return max(1.2, len(text.split()) / wps)


def envelope(wav: bytes, rate_hz: int = 20) -> list[float]:
    """RMS per window of 16-bit mono PCM, normalized 0..1 by the peak window."""
    with wave.open(io.BytesIO(wav), "rb") as w:
        rate, width, channels = w.getframerate(), w.getsampwidth(), w.getnchannels()
        frames = w.readframes(w.getnframes())
    if width != 2:
        return []
    samples = struct.unpack(f"<{len(frames) // 2}h", frames)[::channels]
    window = max(1, rate // rate_hz)
    rms = []
    for i in range(0, len(samples), window):
        chunk = samples[i : i + window]
        rms.append(math.sqrt(sum(s * s for s in chunk) / len(chunk)))
    peak = max(rms) if rms else 0.0
    if peak == 0:
        return [0.0] * len(rms)
    return [round(x / peak, 3) for x in rms]


def from_config(cfg: Config, http: httpx.AsyncClient) -> Speech | None:
    service = cfg.service("tts")
    if service is None:
        log.warning("speech disabled: set openai.tts_model and OPENAI_API_KEY")
        return None
    return Speech(
        http,
        service,
        cfg.get("tts.voice"),
        cfg.get("tts.instructions") or "",
        float(cfg.get("tts.speed", 1.0)),
    )


class Speaker:
    """Voices Reply/Say/Error events.

    A Reply is streamed as PCM into paplay so audio starts before synthesis
    finishes, with a buffered synthesize()+play fallback; Say/Error always take
    the buffered path. Publishes Mouth levels for the face and PlaybackDone when
    a Reply ends.
    """

    def __init__(
        self,
        bus: EventBus,
        tts: Speech | None,
        play: Callable[[str], Awaitable[None]] = platform.play_wav,
        rate_hz: int = 20,
    ) -> None:
        self.bus = bus
        self.tts = tts
        self.play = play
        self.rate_hz = rate_hz

    def start(self) -> None:
        self.bus.subscribe(Reply, lambda e: self.speak(e.text, end_turn=True))
        self.bus.subscribe(Say, lambda e: self.speak(e.text, end_turn=False))
        self.bus.subscribe(
            Error, lambda e: self.speak(f"Sorry, {e.source} is not responding.", end_turn=False)
        )

    async def speak(self, text: str, *, end_turn: bool) -> None:
        try:
            if self.tts is None:
                log.info("tts disabled, would say: %s", text)
                return
            stripped = text.strip()
            if not stripped:
                return
            if end_turn:
                await self.bus.publish(Caption(stripped, caption_seconds(stripped)))
                if await self._speak_streamed(stripped):
                    return
            await self._speak_synth(stripped)
        finally:
            if end_turn:
                await self.bus.publish(PlaybackDone())

    async def _speak_streamed(self, text: str) -> bool:
        """Stream the reply as PCM into `paplay`, driving the mouth from the
        bytes as they flow. Returns True once streaming has run (even if it ended
        early mid-utterance); False if it could not start, so `speak` falls back.
        """
        agen = self.tts.stream(text)
        try:
            try:
                first = await agen.__anext__()
            except StopAsyncIteration:
                return True  # stream opened but produced no audio; nothing to play
            except (httpx.HTTPError, platform.PlatformError) as e:
                log.error("speech stream failed for %r: %s", text[:40], e)
                return False
            try:
                proc = await platform.play_pcm(PCM_RATE)
            except platform.PlatformError as e:
                log.error("play_pcm unavailable, falling back to buffered play: %s", e)
                return False
            await self._pump(agen, first, proc)
            return True
        finally:
            await agen.aclose()

    async def _pump(self, agen: AsyncIterator[bytes], first: bytes, proc: asyncio.subprocess.Process) -> None:
        """Write PCM chunks to the player in order, emitting ~rate_hz Mouth events
        from a running-peak RMS."""
        stdin = proc.stdin
        buf = bytearray()
        peak = _MOUTH_PEAK_FLOOR
        chunk = first
        try:
            while True:
                if stdin is not None:
                    stdin.write(chunk)
                    try:
                        await stdin.drain()
                    except (ConnectionResetError, BrokenPipeError):
                        break
                buf += chunk
                peak = await self._emit_mouth(buf, peak, final=False)
                try:
                    chunk = await agen.__anext__()
                except StopAsyncIteration:
                    break
                except httpx.HTTPError as e:
                    log.warning("tts stream ended mid-utterance: %s", e)
                    break
        finally:
            await self._emit_mouth(buf, peak, final=True)
            await self.bus.publish(Mouth(0.0))
            if stdin is not None:
                try:
                    stdin.close()
                except Exception:
                    pass
            try:
                await proc.wait()
            except Exception:
                pass

    async def _emit_mouth(self, buf: bytearray, peak: float, *, final: bool) -> float:
        """Drain `buf` into ~1/rate_hz-second windows, publishing one Mouth(level)
        per window (level = window RMS over the running peak). Leftover bytes stay
        in `buf` for the next call unless `final`."""
        window = 2 * max(1, PCM_RATE // self.rate_hz)  # bytes: 2 per 16-bit sample
        while len(buf) >= window or (final and buf):
            n = window if len(buf) >= window else len(buf)
            r = pcm_rms(bytes(buf[:n]))
            del buf[:n]
            peak = max(peak, r)
            await self.bus.publish(Mouth(round(min(1.0, r / peak), 3)))
        return peak

    async def _speak_synth(self, text: str) -> None:
        """Buffered fallback: synthesize the whole clip (WAV) then play it."""
        try:
            wav = await self.tts.synthesize(text)
        except httpx.HTTPError as e:
            log.error("speech failed for %r: %s", text[:40], e)
            return
        await self._play_with_mouth(wav)

    async def _play_with_mouth(self, wav: bytes) -> None:
        env = envelope(wav, self.rate_hz)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(wav)
            path = f.name
        try:
            result = self.play(path)
            play_task = asyncio.ensure_future(result) if asyncio.iscoroutine(result) else None
            for level in env:
                await self.bus.publish(Mouth(level))
                await asyncio.sleep(1 / self.rate_hz)
            await self.bus.publish(Mouth(0.0))
            if play_task is not None:
                await play_task
        finally:
            Path(path).unlink(missing_ok=True)


async def _cli(text: str) -> None:
    cfg = load_config()
    async with httpx.AsyncClient() as http:
        tts = from_config(cfg, http)
        if tts is None:
            sys.exit("speech not configured (openai.tts_model, OPENAI_API_KEY)")
        wav = await tts.synthesize(text)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(wav)
            path = f.name
        try:
            await platform.play_wav(path)
        finally:
            Path(path).unlink(missing_ok=True)


def main() -> None:
    p = argparse.ArgumentParser(description="Speak text through the configured speech model")
    p.add_argument("text")
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO)
    asyncio.run(_cli(a.text))


if __name__ == "__main__":
    main()
