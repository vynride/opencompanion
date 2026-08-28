# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from opencompanion import audio, platform, stt, tts, wakeword
from opencompanion import main as oc_main
from opencompanion.bus import PlaybackDone, Reply, StateChanged, Transcript, Wake
from opencompanion.config import Config
from opencompanion.main import App, supervise
from opencompanion.state import State

# --- supervise -------------------------------------------------------------


async def test_supervise_restarts_with_backoff():
    runs = []
    sleeps = []

    async def factory():
        runs.append(1)
        if len(runs) < 4:
            raise RuntimeError("boom")
        raise asyncio.CancelledError

    async def fake_sleep(s):
        sleeps.append(s)

    with pytest.raises(asyncio.CancelledError):
        await supervise("x", factory, min_backoff=1, max_backoff=3, sleep=fake_sleep)
    assert runs == [1, 1, 1, 1]
    assert sleeps == [1, 2, 3]


async def test_supervise_stops_after_a_clean_return():
    runs = []
    sleeps = []

    async def factory():
        runs.append(1)

    async def fake_sleep(s):
        sleeps.append(s)
        if len(sleeps) > 3:
            raise AssertionError("supervise restarted after a clean return")

    await supervise("x", factory, sleep=fake_sleep)
    assert runs == [1]
    assert sleeps == []


async def test_supervise_resets_backoff_after_a_long_run(monkeypatch):
    sleeps = []
    # (start, end) of each run; the second lasts 100 fake seconds.
    ticks = iter([0, 0, 0, 100, 200, 210])

    monkeypatch.setattr(oc_main, "time", SimpleNamespace(monotonic=lambda: next(ticks)))

    async def factory():
        raise RuntimeError("boom")

    async def fake_sleep(s):
        sleeps.append(s)
        if len(sleeps) == 3:
            raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await supervise("x", factory, sleep=fake_sleep)
    assert sleeps == [1, 1, 2]


async def test_supervise_propagates_cancellation():
    async def factory():
        await asyncio.Event().wait()

    task = asyncio.ensure_future(supervise("x", factory))
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


# --- fakes for the App tests ---------------------------------------------


class FakeSpeaker:
    """Records Reply text and ends the turn."""

    def __init__(self, bus):
        self.bus = bus
        self.spoken: list[str] = []

    def start(self) -> None:
        self.bus.subscribe(Reply, self._speak)

    async def _speak(self, e: Reply) -> None:
        self.spoken.append(e.text)
        await self.bus.publish(PlaybackDone())


class FakeListener:
    def __init__(self, bus, **kwargs):
        self.bus = bus
        self.kwargs = kwargs

    def start(self) -> None:
        pass


class FakeBrain:
    def __init__(self, bus, runtime_info):
        self.bus = bus
        self.runtime_info = runtime_info

    def start(self) -> None:
        self.bus.subscribe(Transcript, self._reply)

    async def _reply(self, e: Transcript) -> None:
        if e.text.strip():
            await self.bus.publish(Reply("hello"))


def make_cfg(tmp_path, *, touch_device="", followup_s=0.0, **face) -> Config:
    data = {
        "face": {"host": "127.0.0.1", "port": 0, "tap_delay_s": 0, **face},
        "senses": {"touch_device": touch_device},
        "state": {"followup_s": followup_s},
    }
    return Config(data, {}, root=tmp_path)


def patch_fakes(monkeypatch) -> dict:
    """Replace the brain, speaker and listener with fakes; returns what was made."""
    made: dict = {}
    monkeypatch.setattr(tts, "from_config", lambda cfg, http: None)
    monkeypatch.setattr(stt, "from_config", lambda cfg, http: None)

    def speaker(bus, tts_client, *a, **kw):
        made["speaker"] = FakeSpeaker(bus)
        return made["speaker"]

    def listener(bus, cfg, stt_client, *a, **kw):
        made["listener"] = FakeListener(bus, **kw)
        return made["listener"]

    def brain(cfg, bus, http, runtime_info):
        made["brain"] = FakeBrain(bus, runtime_info)
        return made["brain"]

    monkeypatch.setattr(tts, "Speaker", speaker)
    monkeypatch.setattr(stt, "Listener", listener)
    monkeypatch.setattr(oc_main, "build_brain", brain)
    return made


def patch_termux(monkeypatch) -> SimpleNamespace:
    """Fake a Termux host: every phone command is a mock and supervised loops are
    recorded by name instead of running.
    """
    monkeypatch.setattr(platform, "is_termux", lambda: True)
    monkeypatch.setattr(wakeword, "openwakeword_predictor", lambda model: None)
    cmd = {
        name: AsyncMock()
        for name in (
            "stay_on_while_charging",
            "wake_lock",
            "wake_unlock",
            "wake_screen",
            "launch_face",
            "tap_natural",
        )
    }
    for name, mock in cmd.items():
        monkeypatch.setattr(platform, name, mock)

    supervised: list[str] = []

    async def fake_supervise(name, factory, **kwargs):
        supervised.append(name)
        await asyncio.Event().wait()

    monkeypatch.setattr(oc_main, "supervise", fake_supervise)
    return SimpleNamespace(cmd=cmd, supervised=supervised)


async def start_app(app: App) -> asyncio.Task:
    task = asyncio.ensure_future(app.run())
    await asyncio.wait_for(app.ready.wait(), 5)
    return task


async def stop_app(app: App, task: asyncio.Task) -> None:
    app.stop()
    await asyncio.wait_for(task, 5)


# --- App wiring ------------------------------------------------------------


async def test_app_wiring(tmp_path, monkeypatch):
    """A Transcript flows through brain, state machine and speaker back to idle."""
    made = patch_fakes(monkeypatch)
    monkeypatch.setattr(platform, "is_termux", lambda: False)

    app = App(make_cfg(tmp_path))
    task = await start_app(app)
    states: list[State] = []
    replies: list[str] = []
    app.bus.subscribe(StateChanged, lambda e: states.append(e.state))
    app.bus.subscribe(Reply, lambda e: replies.append(e.text))
    try:
        await app.bus.publish(Transcript("hi"))
    finally:
        await stop_app(app, task)

    assert replies == ["hello"]
    assert made["speaker"].spoken == ["hello"]
    assert states == [State.THINKING, State.SPEAKING, State.IDLE]


async def test_app_leaves_the_listener_always_armed(tmp_path, monkeypatch):
    made = patch_fakes(monkeypatch)
    monkeypatch.setattr(platform, "is_termux", lambda: False)

    app = App(make_cfg(tmp_path))
    task = await start_app(app)
    await stop_app(app, task)
    assert "armed" not in made["listener"].kwargs


async def test_app_runtime_info_without_senses(tmp_path):
    app = App(make_cfg(tmp_path))
    assert app.runtime_info() == {"battery": "unknown", "last presence": "unknown"}


# --- App on Termux ---------------------------------------------------------


@pytest.mark.parametrize(
    "device, expected", [("", ["audio", "senses"]), ("fingerprint", ["audio", "senses", "touch"])]
)
async def test_app_starts_the_touch_watcher_only_when_configured(tmp_path, monkeypatch, device, expected):
    patch_fakes(monkeypatch)
    phone = patch_termux(monkeypatch)

    app = App(make_cfg(tmp_path, touch_device=device))
    task = await start_app(app)
    await stop_app(app, task)
    assert phone.supervised == expected


async def test_app_runtime_info_from_senses(tmp_path, monkeypatch):
    patch_fakes(monkeypatch)
    patch_termux(monkeypatch)

    app = App(make_cfg(tmp_path))
    task = await start_app(app)
    try:
        app.senses.last.update({"battery": "77%", "proximity": 0.0})
        assert app.runtime_info() == {"battery": "77%", "last presence": "near"}
    finally:
        await stop_app(app, task)


async def test_app_takes_power_and_shows_the_face_on_the_phone(tmp_path, monkeypatch):
    patch_fakes(monkeypatch)
    phone = patch_termux(monkeypatch)

    app = App(make_cfg(tmp_path, browser_package="org.example.browser", tap_xy=[100, 200]))
    task = await start_app(app)
    phone.cmd["wake_unlock"].assert_not_called()
    await stop_app(app, task)

    phone.cmd["stay_on_while_charging"].assert_awaited_once()
    phone.cmd["wake_lock"].assert_awaited_once()
    phone.cmd["launch_face"].assert_awaited_once()
    assert phone.cmd["launch_face"].await_args.args[1] == "org.example.browser"
    phone.cmd["tap_natural"].assert_awaited_once_with(100, 200)
    phone.cmd["wake_unlock"].assert_awaited_once()


async def test_app_wake_detector_is_armed_by_the_state_machine(tmp_path, monkeypatch):
    patch_fakes(monkeypatch)
    patch_termux(monkeypatch)
    real_from_config = wakeword.from_config
    made: dict = {}

    def spy(bus, cfg, armed=None):
        made["wake"] = real_from_config(bus, cfg, armed)
        return made["wake"]

    monkeypatch.setattr(wakeword, "from_config", spy)

    app = App(make_cfg(tmp_path))
    task = await start_app(app)
    try:
        assert made["wake"].armed() is True
        await app.bus.publish(Wake())
        assert made["wake"].armed() is False
    finally:
        await stop_app(app, task)


async def test_app_tears_down_when_the_face_port_is_taken(tmp_path, monkeypatch):
    """A face server that cannot bind must not leave the state machine's timers running."""
    patch_fakes(monkeypatch)
    monkeypatch.setattr(platform, "is_termux", lambda: False)
    made: dict = {}

    class DeadFace:
        def __init__(self, bus, root, host, port):
            self.url = f"http://{host}:{port}/"
            self.stopped = 0
            made["face"] = self

        async def start(self):
            raise OSError("address already in use")

        async def stop(self):
            self.stopped += 1

    class SpyStateMachine(oc_main.StateMachine):
        def __init__(self, *a, **kw):
            super().__init__(*a, **kw)
            self.stops = 0
            made["sm"] = self

        def stop(self):
            self.stops += 1
            super().stop()

    monkeypatch.setattr(oc_main, "FaceServer", DeadFace)
    monkeypatch.setattr(oc_main, "StateMachine", SpyStateMachine)

    app = App(make_cfg(tmp_path))
    with pytest.raises(OSError, match="address already in use"):
        await app.run()

    assert made["face"].stopped == 1
    assert made["sm"].stops == 1
    # Unsubscribed: a Wake on the bus no longer moves the machine.
    await app.bus.publish(Wake())
    assert made["sm"].state == State.IDLE


async def test_app_releases_the_wake_lock_when_startup_fails(tmp_path, monkeypatch):
    patch_fakes(monkeypatch)
    phone = patch_termux(monkeypatch)
    monkeypatch.setattr(audio, "from_config", Mock(side_effect=RuntimeError("no mic")))

    app = App(make_cfg(tmp_path))
    with pytest.raises(RuntimeError, match="no mic"):
        await app.run()

    phone.cmd["wake_lock"].assert_awaited_once()
    phone.cmd["wake_unlock"].assert_awaited_once()


async def test_app_survives_a_failing_termux_command(tmp_path, monkeypatch):
    patch_fakes(monkeypatch)
    phone = patch_termux(monkeypatch)
    phone.cmd["launch_face"].side_effect = platform.PlatformError("am not found")
    phone.cmd["wake_lock"].side_effect = platform.PlatformError("termux-wake-lock not found")

    app = App(make_cfg(tmp_path))
    task = await start_app(app)
    await stop_app(app, task)
    phone.cmd["tap_natural"].assert_not_awaited()
    phone.cmd["wake_unlock"].assert_not_awaited()  # nothing was locked
