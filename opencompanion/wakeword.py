# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Wake-word detection on the frame stream; inference runs in a worker thread."""

from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from collections.abc import Callable

from opencompanion.bus import AudioFrame, EventBus, Wake
from opencompanion.config import Config

log = logging.getLogger("opencompanion.wakeword")


def openwakeword_predictor(model_name: str) -> Callable[[bytes], float] | None:
    """Build the openWakeWord predictor, or None if openwakeword is not installed."""
    try:
        import numpy as np
        from openwakeword.model import Model
    except ImportError:
        log.warning("openwakeword not installed; wake-word detection disabled")
        return None

    model = Model(wakeword_models=[model_name], inference_framework="onnx")

    def predict(pcm: bytes) -> float:
        scores = model.predict(np.frombuffer(pcm, dtype=np.int16))
        return float(max(scores.values())) if scores else 0.0

    # Lets the detector clear the model's streaming buffers after a frame gap.
    predict.reset = getattr(model, "reset", None)  # type: ignore[attr-defined]
    return predict


class WakeDetector:
    """Scores each AudioFrame off the event loop and publishes Wake on a hit.

    Frames are scored strictly in order (openWakeWord keeps streaming state).
    Inference only runs while armed and, with a gate, while speech was heard
    within gate_hold_s.
    """

    def __init__(
        self,
        bus: EventBus,
        predict: Callable[[bytes], float] | None,
        threshold: float = 0.5,
        refractory_s: float = 1.0,
        armed: Callable[[], bool] | None = None,
        clock: Callable[[], float] = time.monotonic,
        max_queue: int = 100,
        gate: Callable[[bytes], bool] | None = None,
        gate_hold_s: float = 1.5,
        preroll_frames: int = 6,
        reset: Callable[[], None] | None = None,
    ) -> None:
        self.bus = bus
        self.predict = predict
        self.threshold = threshold
        self.refractory_s = refractory_s
        self.armed = armed
        self.clock = clock
        self.max_queue = max_queue
        self.gate = gate
        self.gate_hold_s = gate_hold_s
        self.reset = reset if reset is not None else getattr(predict, "reset", None)
        self._preroll: deque[bytes] = deque(maxlen=max(0, preroll_frames))
        self._active = False
        self._last_speech = float("-inf")
        self.frames_skipped = 0
        self._last_wake = float("-inf")
        self._queue: asyncio.Queue[bytes] | None = None
        self._worker: asyncio.Task | None = None
        self.frames_seen = 0
        self.frames_dropped = 0
        self.predict_failures = 0

    def start(self) -> None:
        if self.predict is None:
            log.warning("wake-word detector disabled: no predictor available")
            return
        self.bus.subscribe(AudioFrame, self._on_frame)
        self._queue = asyncio.Queue(maxsize=self.max_queue)
        self._worker = asyncio.get_running_loop().create_task(self._run())

    def stop(self) -> None:
        if self._worker:
            self._worker.cancel()

    def _on_frame(self, e: AudioFrame) -> None:
        self.frames_seen += 1
        try:
            self._queue.put_nowait(e.pcm)
        except asyncio.QueueFull:
            self.frames_dropped += 1
            if self.frames_dropped % 100 == 0:
                rate = self.frames_dropped / self.frames_seen
                log.warning(
                    "dropped %d frames; inference is behind realtime (%.0f%% of %d seen)",
                    self.frames_dropped,
                    rate * 100,
                    self.frames_seen,
                )

    async def _run(self) -> None:
        while True:
            pcm = await self._queue.get()
            await self.process(pcm)
            self._queue.task_done()

    def _park(self, pcm: bytes) -> float:
        """Skip inference on this frame; remember it for the pre-roll."""
        self._active = False
        self._preroll.append(pcm)
        self.frames_skipped += 1
        return 0.0

    def _predict_run(self, frames: list[bytes]) -> float:
        """Worker-thread body: score a burst of frames in order, return the max."""
        if self.reset is not None and len(frames) > 1:
            self.reset()
        return max(self.predict(f) for f in frames)

    async def process(self, pcm: bytes) -> float:
        armed = self.armed is None or self.armed()
        if not armed:
            return self._park(pcm)
        now = self.clock()
        if self.gate is not None:
            if self.gate(pcm):
                self._last_speech = now
            if now - self._last_speech > self.gate_hold_s:
                return self._park(pcm)
        if self._active:
            frames = [pcm]
        else:
            # Resuming after a gap: replay the pre-roll so the model has context.
            frames = [*self._preroll, pcm]
            self._preroll.clear()
            self._active = True
        try:
            score = await asyncio.to_thread(self._predict_run, frames)
        except Exception:
            self.predict_failures += 1
            if self.predict_failures == 1 or self.predict_failures % 100 == 0:
                log.exception("predict failed (%d total); treating as no detection", self.predict_failures)
            return 0.0
        if score >= self.threshold and now - self._last_wake >= self.refractory_s:
            self._last_wake = now
            log.info("wake word (score %.2f)", score)
            await self.bus.publish(Wake())
        return score


def from_config(bus: EventBus, cfg: Config, armed: Callable[[], bool] | None = None) -> WakeDetector:
    predict = openwakeword_predictor(cfg.get("wakeword.model", "hey_jarvis"))
    gate = None
    if bool(cfg.get("wakeword.vad_gate", True)):
        from opencompanion.stt import make_vad  # local import: opencompanion.stt's deps are optional

        gate = make_vad(int(cfg.get("stt.vad_aggressiveness", 2))).is_speech
    frame_ms = int(cfg.get("audio.frame_ms", 80))
    return WakeDetector(
        bus,
        predict,
        threshold=float(cfg.get("wakeword.threshold", 0.4)),
        refractory_s=float(cfg.get("wakeword.refractory_s", 1.0)),
        armed=armed,
        max_queue=int(cfg.get("wakeword.max_queue", 100)),
        gate=gate,
        gate_hold_s=float(cfg.get("wakeword.gate_hold_s", 1.5)),
        preroll_frames=max(1, int(cfg.get("wakeword.preroll_ms", 480)) // frame_ms),
    )
