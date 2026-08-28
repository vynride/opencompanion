# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import contextlib
import logging
import sys
import threading
import types

from opencompanion.bus import AudioFrame, EventBus, Wake
from opencompanion.config import Config
from opencompanion.wakeword import WakeDetector, from_config, openwakeword_predictor


def harness(scores, t, **kw):
    bus = EventBus()
    wakes = []
    bus.subscribe(Wake, wakes.append)
    it = iter(scores)
    det = WakeDetector(bus, predict=lambda pcm: next(it), clock=lambda: t[0], **kw)
    return bus, det, wakes


async def test_wake_on_threshold_with_refractory():
    t = [100.0]
    bus, det, wakes = harness([0.1, 0.9, 0.95, 0.9], t, threshold=0.5, refractory_s=1.0)
    for _ in range(3):
        await det.process(b"\x00" * 2560)
    assert len(wakes) == 1  # the third score falls inside the refractory window
    t[0] += 1.5
    await det.process(b"\x00" * 2560)
    assert len(wakes) == 2


async def test_disarmed_blocks_wake_even_above_threshold():
    t = [0.0]
    armed = {"value": False}
    bus, det, wakes = harness([0.9, 0.9], t, armed=lambda: armed["value"])
    await det.process(b"")
    assert wakes == []
    armed["value"] = True
    await det.process(b"")
    assert len(wakes) == 1


async def test_always_armed_when_no_armed_callable_given():
    t = [0.0]
    bus, det, wakes = harness([0.9], t)
    await det.process(b"")
    assert len(wakes) == 1


async def test_predict_exception_is_caught_and_detector_keeps_running(caplog):
    calls = {"n": 0}

    def flaky_predict(pcm):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ValueError("boom")
        return 0.9

    bus = EventBus()
    wakes = []
    bus.subscribe(Wake, wakes.append)
    det = WakeDetector(bus, predict=flaky_predict)

    with caplog.at_level(logging.ERROR, logger="opencompanion.wakeword"):
        score = await det.process(b"x")
    assert score == 0.0
    assert wakes == []
    assert det.predict_failures == 1
    assert "boom" in caplog.text

    score2 = await det.process(b"y")
    assert score2 == 0.9
    assert len(wakes) == 1


async def test_burst_under_max_queue_is_processed_in_order_without_drops():
    t = [0.0]
    bus = EventBus()
    wakes = []
    bus.subscribe(Wake, wakes.append)
    order = []

    def fast_predict(pcm):
        order.append(pcm)
        return 0.9

    det = WakeDetector(bus, predict=fast_predict, clock=lambda: t[0], refractory_s=1.0, max_queue=100)
    det.start()
    try:
        frames = [f"frame{i}".encode() for i in range(10)]
        for pcm in frames:
            det._on_frame(AudioFrame(pcm))

        await asyncio.sleep(0.1)  # let the worker drain the queue

        assert det.frames_dropped == 0
        assert order == frames
        assert len(wakes) == 1  # refractory suppresses the rest
    finally:
        det.stop()


async def test_overflow_drops_frames_and_logs_every_100(caplog):
    # Overflow is simulated with a predict that blocks the worker.
    bus = EventBus()
    block = threading.Event()

    def blocking_predict(pcm):
        block.wait()
        return 0.0

    det = WakeDetector(bus, predict=blocking_predict, max_queue=1)
    det.start()
    try:
        det._on_frame(AudioFrame(b"first"))
        await asyncio.sleep(0.05)  # worker dequeues "first" and blocks in predict

        with caplog.at_level(logging.WARNING, logger="opencompanion.wakeword"):
            det._on_frame(AudioFrame(b"x"))  # fills the queue
            for _ in range(100):
                det._on_frame(AudioFrame(b"x"))  # queue full, dropped
            assert det.frames_dropped == 100

        warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
        assert len(warnings) == 1
        assert "100" in warnings[0].message
    finally:
        block.set()  # release predict so the worker cancels cleanly
        det.stop()


async def test_stop_cancels_the_worker_task():
    bus = EventBus()
    det = WakeDetector(bus, predict=lambda pcm: 0.0)
    det.start()
    worker = det._worker
    assert worker is not None
    assert not worker.done()

    det.stop()
    with contextlib.suppress(asyncio.CancelledError):
        await worker
    assert worker.cancelled()


async def test_start_disables_itself_without_crashing_when_predict_is_none(caplog):
    bus = EventBus()
    det = WakeDetector(bus, predict=None)
    with caplog.at_level(logging.WARNING, logger="opencompanion.wakeword"):
        det.start()
    assert "disabled" in caplog.text.lower()
    await bus.publish(AudioFrame(b"x"))  # nothing subscribed; must not raise
    assert det.frames_dropped == 0


def test_openwakeword_predictor_returns_none_when_openwakeword_missing(monkeypatch, caplog):
    monkeypatch.setitem(sys.modules, "openwakeword", None)  # forces ImportError
    with caplog.at_level(logging.WARNING, logger="opencompanion.wakeword"):
        predict = openwakeword_predictor("hey_jarvis")
    assert predict is None
    assert "openwakeword" in caplog.text.lower()


def test_openwakeword_predictor_wraps_model_and_returns_max_score(monkeypatch):
    built = []

    class FakeModel:
        def __init__(self, wakeword_models, inference_framework):
            built.append((wakeword_models, inference_framework))

        def predict(self, frame):  # noqa: ARG002 - fake model
            return {"hey_jarvis": 0.83, "other_model": 0.10}

    fake_pkg = types.ModuleType("openwakeword")
    fake_model_mod = types.ModuleType("openwakeword.model")
    fake_model_mod.Model = FakeModel
    monkeypatch.setitem(sys.modules, "openwakeword", fake_pkg)
    monkeypatch.setitem(sys.modules, "openwakeword.model", fake_model_mod)

    predict = openwakeword_predictor("hey_jarvis")

    assert built == [(["hey_jarvis"], "onnx")]
    assert predict(b"\x00" * 2560) == 0.83


def test_from_config_wires_threshold_refractory_model_and_armed(monkeypatch):
    import opencompanion.wakeword as wakeword_mod

    built = []

    def fake_predictor(model_name):
        built.append(model_name)
        return lambda pcm: 1.0

    monkeypatch.setattr(wakeword_mod, "openwakeword_predictor", fake_predictor)
    bus = EventBus()
    cfg = Config(
        {"wakeword": {"model": "hey_jarvis", "threshold": 0.7, "refractory_s": 2.0, "max_queue": 42}}, {}
    )
    armed = lambda: True  # noqa: E731 - the identity check below needs the object

    det = from_config(bus, cfg, armed=armed)

    assert built == ["hey_jarvis"]
    assert det.threshold == 0.7
    assert det.refractory_s == 2.0
    assert det.armed is armed
    assert det.max_queue == 42


# --- gating: inference only when armed and, with a gate, speech is near ---


async def test_disarmed_frames_skip_inference_entirely():
    calls = []
    bus = EventBus()
    det = WakeDetector(
        bus, predict=lambda pcm: calls.append(pcm) or 0.0, armed=lambda: False, clock=lambda: 0.0
    )
    for _ in range(5):
        await det.process(b"x")
    assert calls == [] and det.frames_skipped == 5


async def test_vad_gate_skips_quiet_frames_and_replays_preroll_after_reset():
    calls, resets = [], []
    t = [0.0]
    speech = {"on": False}
    bus = EventBus()
    det = WakeDetector(
        bus,
        predict=lambda pcm: calls.append(pcm) or 0.0,
        clock=lambda: t[0],
        gate=lambda pcm: speech["on"],
        gate_hold_s=1.0,
        preroll_frames=2,
        reset=lambda: resets.append(1),
    )
    for f in (b"q1", b"q2", b"q3"):
        await det.process(f)
        t[0] += 0.08
    assert calls == [] and det.frames_skipped == 3
    speech["on"] = True
    await det.process(b"s1")
    assert calls == [b"q2", b"q3", b"s1"] and resets == [1]
    await det.process(b"s2")
    assert calls[-1] == b"s2" and resets == [1]  # no reset while active
    # The gate closes gate_hold_s after the last speech frame.
    speech["on"] = False
    t[0] += 0.5
    await det.process(b"s3")
    assert calls[-1] == b"s3"
    t[0] += 1.0
    await det.process(b"q4")
    assert calls[-1] == b"s3" and det.frames_skipped == 4


async def test_gated_wake_still_fires_on_replayed_burst():
    t = [10.0]
    bus, det, wakes = harness([0.1, 0.9], t, gate=lambda pcm: True, preroll_frames=1)
    await det.process(b"a")
    await det.process(b"b")
    assert len(wakes) == 1


def test_from_config_builds_a_vad_gate_by_default(monkeypatch):
    monkeypatch.setattr("opencompanion.wakeword.openwakeword_predictor", lambda name: lambda pcm: 0.0)
    det = from_config(EventBus(), Config({"wakeword": {"preroll_ms": 400}, "audio": {"frame_ms": 80}}, {}))
    assert det.gate is not None and det._preroll.maxlen == 5


def test_from_config_can_disable_the_gate(monkeypatch):
    monkeypatch.setattr("opencompanion.wakeword.openwakeword_predictor", lambda name: lambda pcm: 0.0)
    assert from_config(EventBus(), Config({"wakeword": {"vad_gate": False}}, {})).gate is None
