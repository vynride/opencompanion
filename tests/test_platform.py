# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import json
import os
from unittest.mock import AsyncMock, MagicMock

import pytest

from opencompanion import platform


async def test_run_executes_and_returns_output():
    code, out, err = await platform.run(["python", "-c", "print('hi')"])
    assert code == 0
    assert out.strip() == b"hi"


async def test_run_times_out():
    with pytest.raises(platform.PlatformError, match="timed out"):
        await platform.run(["python", "-c", "import time; time.sleep(5)"], timeout=0.1)


async def test_run_missing_executable():
    with pytest.raises(platform.PlatformError, match="not found"):
        await platform.run(["definitely-not-a-real-binary-xyz"])


async def test_sensor_read_parses_json(monkeypatch):
    fake = AsyncMock(return_value=(0, json.dumps({"light": {"values": [12.0]}}).encode(), b""))
    monkeypatch.setattr(platform, "run", fake)
    data = await platform.sensor_read(["light", "proximity"])
    assert data == {"light": {"values": [12.0]}}
    assert fake.call_args.args[0] == ["termux-sensor", "-s", "light,proximity", "-n", "1"]


async def test_notify_and_clipboard_commands(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.notify("T", "body")
    assert fake.call_args.args[0] == ["termux-notification", "--title", "T", "--content", "body"]
    await platform.clipboard_set("clip")
    assert fake.call_args.args[0] == ["termux-clipboard-set"]
    assert fake.call_args.kwargs["stdin"] == b"clip"


async def test_set_brightness_clamps(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.set_brightness(999)
    assert fake.call_args.args[0] == ["su", "-c", "settings put system screen_brightness 255"]


def test_is_termux_false_on_laptop(monkeypatch):
    monkeypatch.delenv("PREFIX", raising=False)
    assert platform.is_termux() is False


# --- root-backed commands ---


async def test_stay_on_while_charging_uses_su(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.stay_on_while_charging()
    assert fake.call_args.args[0] == ["su", "-c", "svc power stayon usb"]


async def test_wake_lock_and_unlock_use_termux_wake_commands(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.wake_lock()
    assert fake.call_args.args[0] == ["termux-wake-lock"]
    await platform.wake_unlock()
    assert fake.call_args.args[0] == ["termux-wake-unlock"]


async def test_tap_uses_su(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.tap(12, 34)
    assert fake.call_args.args[0] == ["su", "-c", "input tap 12 34"]


async def test_launch_face_uses_the_passed_browser_package(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.launch_face("http://127.0.0.1:8080/", "org.example.browser")
    # am runs through `su`: Android 12+ denies `am start` from the Termux uid.
    assert fake.call_args.args[0] == [
        "su",
        "-c",
        "am start -a android.intent.action.VIEW -d http://127.0.0.1:8080/ org.example.browser",
    ]
    await platform.launch_face("http://127.0.0.1:8080/", "com.example.otherbrowser")
    assert fake.call_args.args[0][2].endswith("com.example.otherbrowser")


async def test_launch_face_without_a_package_omits_the_argument(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.launch_face("http://127.0.0.1:8080/", "")
    assert fake.call_args.args[0] == [
        "su",
        "-c",
        "am start -a android.intent.action.VIEW -d http://127.0.0.1:8080/",
    ]


async def test_vibrate_uses_root_vibrator_manager_when_it_succeeds(monkeypatch):
    fake = AsyncMock(return_value=(0, b"", b""))
    monkeypatch.setattr(platform, "run", fake)
    await platform.vibrate(400)
    fake.assert_awaited_once()
    assert fake.call_args.args[0] == ["su", "-c", "cmd vibrator_manager synced -f oneshot 400"]


async def test_vibrate_falls_back_to_termux_vibrate_on_root_failure(monkeypatch):
    fake = AsyncMock(side_effect=[(1, b"", b"no root"), (0, b"", b"")])
    monkeypatch.setattr(platform, "run", fake)
    await platform.vibrate(400)
    assert fake.await_count == 2
    first, second = fake.await_args_list
    assert first.args[0] == ["su", "-c", "cmd vibrator_manager synced -f oneshot 400"]
    assert second.args[0] == ["termux-vibrate", "-d", "400"]


async def test_ensure_mic_source_returns_true_when_already_loaded(monkeypatch):
    fake = AsyncMock(return_value=(0, b"1\tmodule-sles-source\t...\n", b""))
    monkeypatch.setattr(platform, "run", fake)
    assert await platform.ensure_mic_source() is True
    fake.assert_awaited_once()
    assert fake.call_args.args[0] == ["pactl", "list", "short", "modules"]


async def test_ensure_mic_source_loads_module_when_missing(monkeypatch):
    fake = AsyncMock(side_effect=[(0, b"1\tmodule-suspend-on-idle\t...\n", b""), (0, b"", b"")])
    monkeypatch.setattr(platform, "run", fake)
    assert await platform.ensure_mic_source() is True
    assert fake.await_count == 2
    first, second = fake.await_args_list
    assert first.args[0] == ["pactl", "list", "short", "modules"]
    assert second.args[0] == ["pactl", "load-module", "module-sles-source"]


GETEVENT_PL_SAMPLE = (
    b"add device 1: /dev/input/event1\n"
    b"  bus:      0019\n"
    b"  vendor    0000 product 0000 version 0000\n"
    b'  name:     "gpio_keys"\n'
    b'  location: ""\n'
    b'  id:       ""\n'
    b"  version:  0.0.1\n"
    b"add device 2: /dev/input/event2\n"
    b"  bus:      0018\n"
    b"  vendor    0000 product 0000 version 0000\n"
    b'  name:     "uinput-fp"\n'
    b'  location: ""\n'
    b'  id:       ""\n'
    b"  version:  0.0.1\n"
)


async def test_find_input_device_matches_by_substring_case_insensitive(monkeypatch):
    fake = AsyncMock(return_value=(0, GETEVENT_PL_SAMPLE, b""))
    monkeypatch.setattr(platform, "run", fake)
    assert await platform.find_input_device("FP") == "/dev/input/event2"
    assert fake.call_args.args[0] == ["su", "-c", "getevent -pl"]


async def test_find_input_device_matches_earlier_device_too(monkeypatch):
    fake = AsyncMock(return_value=(0, GETEVENT_PL_SAMPLE, b""))
    monkeypatch.setattr(platform, "run", fake)
    assert await platform.find_input_device("gpio_keys") == "/dev/input/event1"


async def test_find_input_device_returns_none_when_no_match(monkeypatch):
    fake = AsyncMock(return_value=(0, GETEVENT_PL_SAMPLE, b""))
    monkeypatch.setattr(platform, "run", fake)
    assert await platform.find_input_device("nonexistent") is None


# --- pulseaudio ---


async def test_start_pulseaudio_reuses_a_running_server(monkeypatch):
    monkeypatch.setattr(platform, "pulseaudio_running", AsyncMock(return_value=True))
    spawn = AsyncMock()
    monkeypatch.setattr("asyncio.create_subprocess_exec", spawn)
    await platform.start_pulseaudio(["pulseaudio", "--start"])
    spawn.assert_not_awaited()


async def test_start_pulseaudio_spawns_foreground_child_and_stop_kills_it(monkeypatch):
    up = {"v": False}

    async def running():
        return up["v"]

    monkeypatch.setattr(platform, "pulseaudio_running", running)
    proc = MagicMock()
    proc.returncode = None
    proc.wait = AsyncMock()

    async def spawn(*argv, **kw):
        up["v"] = True
        spawn.argv = argv
        return proc

    monkeypatch.setattr("asyncio.create_subprocess_exec", spawn)
    monkeypatch.setattr(platform, "_pulse_proc", None)
    await platform.start_pulseaudio(["pulseaudio", "--start", "--exit-idle-time=-1"])
    assert spawn.argv == ("pulseaudio", "--daemonize=no", "--exit-idle-time=-1")
    await platform.stop_pulseaudio()
    proc.terminate.assert_called_once()
    assert platform._pulse_proc is None


async def test_start_pulseaudio_raises_if_server_never_answers(monkeypatch):
    monkeypatch.setattr(platform, "pulseaudio_running", AsyncMock(return_value=False))
    proc = MagicMock()
    proc.returncode = 1  # child died immediately
    proc.wait = AsyncMock()
    monkeypatch.setattr("asyncio.create_subprocess_exec", AsyncMock(return_value=proc))
    monkeypatch.setattr(platform, "_pulse_proc", None)
    with pytest.raises(platform.PlatformError):
        await platform.start_pulseaudio(["pulseaudio", "--daemonize=no"])


async def test_pulseaudio_running_probes_without_autospawn(monkeypatch, tmp_path):
    monkeypatch.setattr("tempfile.gettempdir", lambda: str(tmp_path))
    seen = {}

    async def fake_run(cmd, *, timeout=10.0, stdin=None, env=None):
        seen["cmd"], seen["env"] = cmd, env
        return 1, b"", b"Connection refused"

    monkeypatch.setattr(platform, "run", fake_run)
    assert await platform.pulseaudio_running() is False
    assert seen["cmd"] == ["pactl", "info"]
    conf = seen["env"]["PULSE_CLIENTCONFIG"]
    assert os.path.dirname(conf) == str(tmp_path)
    assert open(conf).read().strip() == "autospawn = no"


# --- rotation ---


def test_rotate_point_maps_natural_coords_to_each_rotation():
    w, h = 720, 1280
    assert platform.rotate_point(100, 200, w, h, 0) == (100, 200)
    assert platform.rotate_point(100, 200, w, h, 1) == (1080, 100)
    assert platform.rotate_point(100, 200, w, h, 2) == (620, 1080)
    assert platform.rotate_point(100, 200, w, h, 3) == (200, 620)
    # the centre stays the centre at any rotation
    assert platform.rotate_point(360, 640, w, h, 3) == (640, 360)


async def test_tap_natural_reads_geometry_and_taps_rotated(monkeypatch):
    su_calls = []

    async def fake_su(cmd, **kw):
        su_calls.append(cmd)
        return b"Physical size: 720x1280\n"

    async def fake_run(cmd, **kw):
        assert cmd == ["su", "-c", "dumpsys window"]
        return 0, b"  mCurrentRotation=ROTATION_270\n  mRotation=3", b""

    monkeypatch.setattr(platform, "_su", fake_su)
    monkeypatch.setattr(platform, "run", fake_run)
    await platform.tap_natural(360, 640)
    assert su_calls == ["wm size", "input tap 640 360"]
