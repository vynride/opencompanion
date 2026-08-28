# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import io
import struct
import sys
import wave

import httpx
import respx

from opencompanion.bus import AudioFrame, Error, EventBus, PlaybackDone, Reply, Transcript, Wake
from opencompanion.config import Config, Service
from opencompanion.state import State, StateMachine
from opencompanion.stt import (
    EnergyVAD,
    Listener,
    SilenceDetector,
    Transcriber,
    WebrtcVAD,
    make_vad,
    wav_bytes,
)

BASE = "https://api.example/v1"
URL = f"{BASE}/audio/transcriptions"
SERVICE = Service(BASE, "k", "stt-model")
FRAME = 1280 * 2  # 80 ms @ 16 kHz s16le


def make_stt(http):
    return Transcriber(http, SERVICE)


def loud():
    return struct.pack("<1280h", *([8000, -8000] * 640))


def quiet():
    return b"\x00" * FRAME


def test_energy_vad():
    v = EnergyVAD(500)
    assert v.is_speech(loud()) and not v.is_speech(quiet())


def test_energy_vad_ignores_empty_frame():
    assert not EnergyVAD().is_speech(b"")


class StubVad:
    """Stands in for webrtcvad.Vad: speech on any sub-frame that is not all zeros."""

    def __init__(self, aggressiveness):
        self.aggressiveness = aggressiveness
        self.seen: list[tuple[int, int]] = []

    def is_speech(self, chunk, rate):
        self.seen.append((len(chunk), rate))
        return any(chunk)


class StubWebrtcvad:
    def __init__(self):
        self.made: list[StubVad] = []

    def Vad(self, aggressiveness):  # noqa: N802 - mirrors the real webrtcvad API
        v = StubVad(aggressiveness)
        self.made.append(v)
        return v


def test_webrtc_vad_splits_into_20ms_subframes(monkeypatch):
    stub = StubWebrtcvad()
    monkeypatch.setitem(sys.modules, "webrtcvad", stub)
    v = WebrtcVAD(3, 16000)
    assert stub.made[0].aggressiveness == 3
    assert v.is_speech(loud()) and not v.is_speech(quiet())
    # 80 ms frame -> four 20 ms sub-frames of 640 bytes at 16 kHz
    assert stub.made[0].seen[:4] == [(640, 16000)] * 4


def test_webrtc_vad_speech_if_any_subframe_is_speech(monkeypatch):
    monkeypatch.setitem(sys.modules, "webrtcvad", StubWebrtcvad())
    v = WebrtcVAD()
    assert v.is_speech(b"\x00" * (FRAME - 640) + b"\x11" * 640)


def test_make_vad_prefers_webrtcvad(monkeypatch):
    monkeypatch.setitem(sys.modules, "webrtcvad", StubWebrtcvad())
    assert isinstance(make_vad(2), WebrtcVAD)


def test_make_vad_falls_back_to_energy(monkeypatch):
    monkeypatch.setitem(sys.modules, "webrtcvad", None)  # forces ImportError
    assert isinstance(make_vad(2), EnergyVAD)


def test_silence_detector_stops_after_silence():
    d = SilenceDetector(EnergyVAD(), frame_ms=80, silence_ms=800, max_ms=12000, min_ms=300)
    for _ in range(5):
        assert d.feed(loud()) is False
    stops = [d.feed(quiet()) for _ in range(10)]
    assert stops.index(True) == 9  # 800 ms = 10 frames of silence
    assert d.has_enough()
    assert len(d.pcm()) == FRAME * 15


def test_silence_detector_caps_length():
    d = SilenceDetector(EnergyVAD(), frame_ms=80, max_ms=800)
    stops = [d.feed(loud()) for _ in range(12)]
    assert stops.index(True) == 9


def test_silence_only_is_not_enough():
    d = SilenceDetector(EnergyVAD(), frame_ms=80, silence_ms=160, min_ms=300)
    while not d.feed(quiet()):
        pass
    assert not d.has_enough()


def test_quiet_lead_in_does_not_end_the_turn_before_speech():
    # A slow speaker (or a mistimed wake) starts with more than silence_ms of
    # quiet: the recorder must keep listening until speech arrives, not stop.
    d = SilenceDetector(EnergyVAD(), frame_ms=80, silence_ms=800, max_ms=12000, min_ms=300)
    for _ in range(20):  # 1.6 s of quiet, twice the silence window
        assert d.feed(quiet()) is False
    for _ in range(5):  # then speech
        assert d.feed(loud()) is False
    stops = [d.feed(quiet()) for _ in range(10)]  # trailing silence now ends it
    assert stops.index(True) == 9
    assert d.has_enough()


def test_wav_bytes_header():
    data = wav_bytes(quiet(), 16000)
    with wave.open(io.BytesIO(data)) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()) == (16000, 1, 2, 1280)


@respx.mock
async def test_transcribe_multipart():
    route = respx.post(URL).mock(return_value=httpx.Response(200, json={"text": " hello opencompanion "}))
    async with httpx.AsyncClient() as http:
        stt = make_stt(http)
        assert await stt.transcribe(wav_bytes(quiet())) == "hello opencompanion"
    req = route.calls[0].request
    assert req.headers["authorization"] == "Bearer k"
    assert str(req.url) == URL
    assert b'name="model"' in req.content and b"stt-model" in req.content
    assert b'name="response_format"' in req.content and b"json" in req.content
    assert b'name="language"' in req.content and b"en" in req.content
    assert b'name="file"' in req.content and b"RIFF" in req.content


@respx.mock
async def test_transcribe_raises_on_http_error():
    respx.post(URL).mock(return_value=httpx.Response(502))
    async with httpx.AsyncClient() as http:
        try:
            await make_stt(http).transcribe(wav_bytes(quiet()))
        except httpx.HTTPError:
            return
    raise AssertionError("expected an httpx.HTTPError")


def stt_config():
    return Config({"stt": {"silence_ms": 160, "max_ms": 2000, "min_ms": 160}, "audio": {"frame_ms": 80}}, {})


async def drive(listener_frames, stt_response):
    bus = EventBus()
    cfg = stt_config()
    out = []
    async with httpx.AsyncClient() as http:
        stt = make_stt(http) if stt_response is not None else None
        # Short frame timeout: a quiet-only capture no longer ends on trailing
        # silence (it waits for speech), so it finalizes via the frame timeout.
        Listener(bus, cfg, stt, vad=EnergyVAD(), frame_timeout_s=0.05).start()
        bus.subscribe(Transcript, out.append)
        bus.subscribe(Error, out.append)
        await bus.publish(Wake())
        for f in listener_frames:
            await bus.publish(AudioFrame(f))
            await asyncio.sleep(0)
        await asyncio.sleep(0.2)
    return out


@respx.mock
async def test_listener_records_then_publishes_transcript():
    respx.post(URL).mock(return_value=httpx.Response(200, json={"text": "what time is it"}))
    out = await drive([loud()] * 4 + [quiet()] * 3, "ok")
    assert out == [Transcript("what time is it")]


async def test_listener_short_recording_is_empty_transcript():
    out = await drive([quiet()] * 3, "ok")
    assert out == [Transcript("")]


@respx.mock
async def test_listener_transcribe_failure_publishes_error(monkeypatch):
    monkeypatch.setattr("opencompanion.retry.BACKOFF_BASE", 0)
    respx.post(URL).mock(return_value=httpx.Response(502))
    out = await drive([loud()] * 4 + [quiet()] * 3, "ok")
    assert isinstance(out[0], Error) and out[0].source == "transcription"


async def test_listener_without_stt():
    out = await drive([loud()] * 4 + [quiet()] * 3, None)
    assert out == [Transcript("")]


async def test_second_wake_during_transcription_does_not_start_a_second_turn():
    bus = EventBus()
    release = asyncio.Event()
    calls: list[bytes] = []

    class SlowSTT:
        async def transcribe(self, wav):
            calls.append(wav)
            await release.wait()
            return "one turn only"

    Listener(bus, stt_config(), SlowSTT(), vad=EnergyVAD()).start()
    out: list = []
    bus.subscribe(Transcript, out.append)
    bus.subscribe(Error, out.append)

    async def wake_and_speak():
        await bus.publish(Wake())
        for f in [loud()] * 4 + [quiet()] * 3:
            await bus.publish(AudioFrame(f))
            await asyncio.sleep(0)
        await asyncio.sleep(0.01)

    await wake_and_speak()
    assert len(calls) == 1 and out == []  # blocked inside the transcription call
    await wake_and_speak()  # a wake mid-transcription must be ignored
    assert len(calls) == 1 and out == []
    release.set()
    await asyncio.sleep(0.01)
    assert out == [Transcript("one turn only")]
    # once the transcript is out, the listener takes a new turn again
    await wake_and_speak()
    assert len(calls) == 2


async def test_listener_gives_up_when_no_frames_arrive():
    bus = EventBus()
    cfg = Config({"stt": {"silence_ms": 160, "max_ms": 100, "min_ms": 160}, "audio": {"frame_ms": 80}}, {})
    out: list = []
    listener = Listener(bus, cfg, None, vad=EnergyVAD(), frame_timeout_s=0.05)
    listener.start()
    bus.subscribe(Transcript, out.append)
    await bus.publish(Wake())
    await asyncio.sleep(0.15)
    assert out == [Transcript("")]


# --- follow-up listening (no wake word between turns) -----------------------


def followup_cfg(followup_s):
    return Config(
        {
            "stt": {"silence_ms": 160, "max_ms": 2000, "min_ms": 160},
            "audio": {"frame_ms": 80},
            "state": {"followup_s": followup_s, "idle_to_sleep_s": 100},
        },
        {},
    )


class CountingSTT:
    """Returns 'utterance-1', 'utterance-2', ... one per recorded turn."""

    def __init__(self):
        self.n = 0

    async def transcribe(self, wav):
        self.n += 1
        return f"utterance-{self.n}"


async def utter(bus):
    for f in [loud()] * 4 + [quiet()] * 3:
        await bus.publish(AudioFrame(f))
        await asyncio.sleep(0)
    await asyncio.sleep(0.05)


async def test_followup_listen_after_reply_needs_no_wake_word():
    # First turn uses the wake word; after the companion speaks its reply, the follow-up
    # window records the next utterance with NO second Wake, then chains again.
    bus = EventBus()
    sm = StateMachine(bus, idle_to_sleep_s=100, followup_s=0.3)
    sm.start()

    async def brain(e):  # any non-empty transcript -> a spoken reply
        if e.text.strip():
            await bus.publish(Reply(f"ack {e.text}"))

    async def speaker(e):  # a reply finishes playing -> PlaybackDone
        await bus.publish(PlaybackDone())

    bus.subscribe(Transcript, brain)
    bus.subscribe(Reply, speaker)

    # Always-armed, like the real app: the wake *detector* is what the state
    # machine gates, and here the Wake is published directly. The frame
    # timeout is generous because a real follow-up sees a continuous mic stream;
    # here the utterances are fed in bursts.
    Listener(bus, followup_cfg(0.3), CountingSTT(), vad=EnergyVAD(), frame_timeout_s=0.3).start()

    transcripts, wakes = [], []
    bus.subscribe(Transcript, transcripts.append)
    bus.subscribe(Wake, wakes.append)

    await bus.publish(Wake())  # only the first turn is wake-triggered
    await utter(bus)  # -> utterance-1 -> reply -> PlaybackDone -> follow-up
    await asyncio.sleep(0)  # let the follow-up turn start
    await utter(bus)  # follow-up, no wake -> utterance-2 -> reply -> follow-up
    await asyncio.sleep(0.5)  # third follow-up gets no speech: times out to IDLE

    assert [t.text for t in transcripts] == ["utterance-1", "utterance-2"]
    assert len(wakes) == 1
    assert sm.state == State.IDLE
    sm.stop()


async def test_followup_window_with_no_speech_publishes_no_transcript():
    # Silence during the follow-up window: no Transcript at all, machine -> IDLE.
    bus = EventBus()
    sm = StateMachine(bus, idle_to_sleep_s=100, followup_s=0.2)
    sm.start()
    Listener(bus, followup_cfg(0.2), CountingSTT(), vad=EnergyVAD(), frame_timeout_s=0.05).start()

    out: list = []
    bus.subscribe(Transcript, out.append)

    await bus.publish(PlaybackDone())  # a reply just finished; open the window
    assert sm.state == State.LISTENING
    for _ in range(6):  # live mic, but only silence
        await bus.publish(AudioFrame(quiet()))
        await asyncio.sleep(0)
    await asyncio.sleep(0.3)

    assert out == []  # nothing transcribed, no empty Transcript
    assert sm.state == State.IDLE
    sm.stop()


async def test_followup_disabled_starts_no_listen_after_reply():
    # followup_s == 0: a PlaybackDone must not start any recording, even with
    # speech on the wire.
    bus = EventBus()
    Listener(bus, followup_cfg(0.0), CountingSTT(), vad=EnergyVAD(), frame_timeout_s=0.05).start()
    out: list = []
    bus.subscribe(Transcript, out.append)

    await bus.publish(PlaybackDone())
    await utter(bus)
    await asyncio.sleep(0.1)
    assert out == []


@respx.mock
async def test_transcribe_retries_transient_404(monkeypatch):
    monkeypatch.setattr("opencompanion.retry.BACKOFF_BASE", 0)
    route = respx.post(URL)
    route.side_effect = [httpx.Response(404), httpx.Response(200, json={"text": "hello"})]
    async with httpx.AsyncClient() as http:
        stt = Transcriber(http, SERVICE)
        assert await stt.transcribe(wav_bytes(quiet())) == "hello"
    assert route.call_count == 2
