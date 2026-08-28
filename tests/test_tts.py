# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import io
import json
import math
import struct
import wave

import httpx
import respx

from opencompanion import platform
from opencompanion.bus import Caption, Error, EventBus, Mouth, PlaybackDone, Reply, Say
from opencompanion.config import Service
from opencompanion.tts import Speaker, Speech, caption_seconds, envelope, pcm_rms

BASE = "https://api.example/v1"
URL = f"{BASE}/audio/speech"
SERVICE = Service(BASE, "k", "tts-model")


def make_tts(http=None, voice="test-voice", instructions="be a cheerful robot"):
    return Speech(http or httpx.AsyncClient(), SERVICE, voice, instructions)


def make_wav(samples, rate=16000):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    return buf.getvalue()


def pcm(samples):
    """Raw 16-bit mono LE PCM (no WAV header) -- what stream() yields."""
    return struct.pack(f"<{len(samples)}h", *samples)


class FakeStdin:
    def __init__(self):
        self.chunks = []
        self.closed = False

    def write(self, data):
        self.chunks.append(bytes(data))

    async def drain(self):
        pass

    def close(self):
        self.closed = True


class FakePlayer:
    """Stands in for the paplay subprocess play_pcm() returns."""

    def __init__(self):
        self.stdin = FakeStdin()
        self.waited = False

    async def wait(self):
        self.waited = True
        return 0


async def async_gen(chunks):
    for c in chunks:
        yield c


def test_envelope_tracks_loudness():
    rate = 16000
    quiet = [0] * (rate // 20)
    loud = [int(20000 * math.sin(i / 5)) for i in range(rate // 20)]
    env = envelope(make_wav(quiet + loud + quiet, rate), rate_hz=20)
    assert len(env) == 3
    assert env[0] == 0.0 and env[1] == 1.0 and env[2] == 0.0


def test_envelope_of_silence_is_zero():
    assert envelope(make_wav([0] * 1600)) == [0.0, 0.0]


def test_pcm_rms_of_raw_chunk():
    assert pcm_rms(b"") == 0.0
    assert pcm_rms(pcm([0] * 100)) == 0.0
    assert pcm_rms(pcm([10000] * 100)) == 10000.0


def test_caption_seconds_estimate():
    assert caption_seconds("hi") == 1.2  # floored for short text
    # ~2.6 words/sec: ten words is a bit under four seconds
    assert math.isclose(caption_seconds("a b c d e f g h i j"), 10 / 2.6)
    assert caption_seconds("a much longer reply " * 5) > 1.2


@respx.mock
async def test_synthesize_posts_and_returns_raw_wav():
    wav = make_wav([0] * 100)
    route = respx.post(URL).mock(return_value=httpx.Response(200, content=wav))
    tts = make_tts(voice="test-voice", instructions="cheerful robot")
    assert await tts.synthesize("hello") == wav
    req = route.calls[0].request
    assert req.headers["authorization"] == "Bearer k"
    assert str(req.url) == URL
    body = json.loads(req.content)
    assert body["input"] == "hello"
    assert body["voice"] == "test-voice"
    assert body["instructions"] == "cheerful robot"
    assert body["model"] == "tts-model"
    assert body["response_format"] == "wav"


@respx.mock
async def test_stream_posts_pcm_and_yields_chunks():
    audio = pcm([1000] * 3000)
    route = respx.post(URL).mock(return_value=httpx.Response(200, content=audio))
    tts = make_tts()
    got = b"".join([chunk async for chunk in tts.stream("hello")])
    assert got == audio
    req = route.calls[0].request
    assert req.headers["authorization"] == "Bearer k"
    body = json.loads(req.content)
    assert body["response_format"] == "pcm"
    assert body["input"] == "hello"


@respx.mock
async def test_stream_retries_transient_status_before_first_chunk():
    audio = pcm([500] * 100)
    route = respx.post(URL).mock(
        side_effect=[
            httpx.Response(404),
            httpx.Response(200, content=audio),
        ]
    )
    tts = make_tts()
    got = b"".join([chunk async for chunk in tts.stream("hi")])
    assert got == audio
    assert route.call_count == 2


async def test_reply_streams_pcm_to_player_with_mouth_and_caption(monkeypatch):
    bus = EventBus()
    chunks = [pcm([8000] * 1200), pcm([12000] * 1200), pcm([4000] * 1200)]
    player = FakePlayer()
    monkeypatch.setattr(platform, "play_pcm", lambda rate=24000: _return(player))

    tts = make_tts()
    tts.stream = lambda text: async_gen(chunks)

    captions, mouths, done = [], [], []
    bus.subscribe(Caption, captions.append)
    bus.subscribe(Mouth, mouths.append)
    bus.subscribe(PlaybackDone, done.append)

    sp = Speaker(bus, tts)
    sp.start()
    await bus.publish(Reply("stream this reply out loud"))

    assert player.stdin.chunks == chunks  # every chunk, in order
    assert player.stdin.closed and player.waited  # stdin closed, process awaited
    assert len(captions) == 1 and captions[0].text == "stream this reply out loud"
    assert captions[0].seconds >= 1.2
    assert sum(isinstance(m, Mouth) for m in mouths) >= len(chunks)
    assert mouths[-1] == Mouth(0.0)  # mouth closes at the end
    assert done == [PlaybackDone()]  # end of turn, exactly once


async def test_stream_start_failure_falls_back_to_synthesize(monkeypatch):
    bus = EventBus()
    # play_pcm must never be reached: the stream fails to start first.
    monkeypatch.setattr(platform, "play_pcm", lambda rate=24000: _raise())
    tts = make_tts()

    async def boom(text):
        raise httpx.HTTPError("no first byte")
        yield b""  # make it an async generator

    synth_calls, played = [], []

    async def fake_synth(text):
        synth_calls.append(text)
        return make_wav([0] * 200)

    tts.stream = boom
    tts.synthesize = fake_synth

    async def play(path):
        played.append(path)

    done = []
    bus.subscribe(PlaybackDone, done.append)
    sp = Speaker(bus, tts, play=play)
    sp.start()
    await bus.publish(Reply("please still say this"))

    assert synth_calls == ["please still say this"]  # fell back to buffered synth
    assert len(played) == 1 and played[0].endswith(".wav")
    assert done == [PlaybackDone()]  # turn still ended once


async def test_streamed_reply_playback_done_fires_once(monkeypatch):
    bus = EventBus()
    player = FakePlayer()
    monkeypatch.setattr(platform, "play_pcm", lambda rate=24000: _return(player))
    tts = make_tts()
    tts.stream = lambda text: async_gen([pcm([6000] * 1200)])

    done = []
    bus.subscribe(PlaybackDone, lambda e: done.append(e))
    sp = Speaker(bus, tts)
    sp.start()
    await bus.publish(Reply("one turn"))
    assert done == [PlaybackDone()]


@respx.mock
async def test_say_and_error_do_not_stream_or_end_turn(monkeypatch):
    # Say/Error take the buffered synth path, never the PCM stream, and don't
    # end a turn or emit a Caption.
    wav = make_wav([0] * 160)
    respx.post(URL).mock(return_value=httpx.Response(200, content=wav))
    monkeypatch.setattr(platform, "play_pcm", lambda rate=24000: _raise())  # must not be called
    bus = EventBus()
    texts = []

    async def play(path):
        pass

    tts = make_tts()
    orig = tts.synthesize

    async def spy(text):
        texts.append(text)
        return await orig(text)

    tts.synthesize = spy
    sp = Speaker(bus, tts, play=play)
    sp.start()
    done, captions = [], []
    bus.subscribe(PlaybackDone, done.append)
    bus.subscribe(Caption, captions.append)
    await bus.publish(Say("one moment"))
    await bus.publish(Error("speech", "timeout"))
    assert texts == ["one moment", "Sorry, speech is not responding."]
    assert done == [] and captions == []


@respx.mock
async def test_tts_failure_still_ends_turn():
    # Both the stream and the buffered fallback 500: reply is lost but the turn
    # still ends (no Error event -- just logs).
    respx.post(URL).mock(return_value=httpx.Response(500))
    bus = EventBus()
    sp = Speaker(bus, make_tts(), play=lambda p: None)
    sp.start()
    done = []
    bus.subscribe(PlaybackDone, done.append)
    await bus.publish(Reply("hi"))
    assert done == [PlaybackDone()]


async def test_no_tts_configured_ends_turn():
    bus = EventBus()
    Speaker(bus, None).start()
    done = []
    bus.subscribe(PlaybackDone, done.append)
    await bus.publish(Reply("hi"))
    assert done == [PlaybackDone()]


def _return(value):
    async def coro(*a, **k):
        return value

    return coro()


def _raise():
    async def coro():
        raise platform.PlatformError("paplay not found")

    return coro()


@respx.mock
async def test_empty_instructions_omitted_for_plain_voice():
    route = respx.post(URL).mock(return_value=httpx.Response(200, content=b"RIFFwav"))
    tts = make_tts(voice="other-voice", instructions="")
    await tts.synthesize("hi")
    body = json.loads(route.calls[0].request.content)
    assert "instructions" not in body and body["voice"] == "other-voice"
