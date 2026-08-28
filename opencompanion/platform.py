# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Termux and Android commands.

Every subprocess and rooted shell the daemon runs goes through here.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shlex
import tempfile

log = logging.getLogger("opencompanion.platform")


class PlatformError(RuntimeError):
    pass


def is_termux() -> bool:
    return "com.termux" in os.environ.get("PREFIX", "")


async def run(
    cmd: list[str], *, timeout: float = 10.0, stdin: bytes | None = None, env: dict[str, str] | None = None
) -> tuple[int, bytes, bytes]:
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE if stdin is not None else None,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=None if env is None else {**os.environ, **env},
        )
    except FileNotFoundError as e:
        raise PlatformError(f"{cmd[0]} not found") from e
    try:
        out, err = await asyncio.wait_for(proc.communicate(stdin), timeout)
    except TimeoutError as e:
        proc.kill()
        raise PlatformError(f"{cmd[0]} timed out after {timeout}s") from e
    return proc.returncode or 0, out, err


async def _check(cmd: list[str], **kw) -> bytes:
    code, out, err = await run(cmd, **kw)
    if code != 0:
        raise PlatformError(f"{cmd[0]} exited {code}: {err.decode(errors='replace').strip()}")
    return out


async def _su(command: str, **kw) -> bytes:
    return await _check(["su", "-c", command], **kw)


async def sensor_read(sensors: list[str]) -> dict[str, dict]:
    out = await _check(["termux-sensor", "-s", ",".join(sensors), "-n", "1"])
    return json.loads(out or b"{}")


async def camera_photo(path: str, camera_id: int = 1) -> None:
    await _check(["termux-camera-photo", "-c", str(camera_id), path], timeout=20)


async def notify(title: str, text: str) -> None:
    await _check(["termux-notification", "--title", title, "--content", text])


async def clipboard_set(text: str) -> None:
    await _check(["termux-clipboard-set"], stdin=text.encode())


async def notification_list() -> list[dict]:
    out = await _check(["termux-notification-list"])
    return json.loads(out or b"[]")


async def vibrate(ms: int = 200) -> None:
    """Vibrate via root, falling back to `termux-vibrate`."""
    # Android 12+ silently drops termux-vibrate while Termux is backgrounded.
    try:
        await _su(f"cmd vibrator_manager synced -f oneshot {ms}")
        return
    except Exception as e:
        log.debug("root vibrate failed (%s), falling back to termux-vibrate", e)
    await _check(["termux-vibrate", "-d", str(ms)])


async def battery_status() -> dict:
    return json.loads(await _check(["termux-battery-status"]) or b"{}")


async def launch_face(url: str, browser_package: str) -> None:
    """Open `url` in `browser_package`, or in the default handler when it is empty."""
    cmd = f"am start -a android.intent.action.VIEW -d {shlex.quote(url)}"
    # An empty package must be omitted; `am` rejects the intent if it is passed empty.
    if browser_package:
        cmd += f" {shlex.quote(browser_package)}"
    await _su(cmd)


async def tap(x: int, y: int) -> None:
    await _su(f"input tap {x} {y}")


def rotate_point(x: int, y: int, width: int, height: int, rotation: int) -> tuple[int, int]:
    """Map a point in natural portrait coordinates to display `rotation` (0-3)."""
    if rotation == 1:
        return height - y, x
    if rotation == 2:
        return width - x, height - y
    if rotation == 3:
        return y, width - x
    return x, y


async def display_geometry() -> tuple[int, int, int]:
    """Return (natural width, natural height, rotation 0-3); rotation is 0 if unreadable."""
    out = (await _su("wm size")).decode(errors="replace")
    m = re.search(r"Physical size:\s*(\d+)x(\d+)", out)
    if not m:
        raise PlatformError(f"wm size: {out.strip()!r}")
    w, h = int(m.group(1)), int(m.group(2))
    rotation = 0
    code, dump, _ = await run(["su", "-c", "dumpsys window"], timeout=10)
    rm = re.search(r"mCurrentRotation=ROTATION_(\d+)", dump.decode(errors="replace"))
    if code == 0 and rm:
        rotation = int(rm.group(1)) // 90
    return w, h, rotation


async def tap_natural(x: int, y: int) -> None:
    """Tap a point given in natural (portrait) coordinates, whatever the current rotation."""
    w, h, rotation = await display_geometry()
    rx, ry = rotate_point(x, y, w, h, rotation)
    await tap(rx, ry)


async def stay_on_while_charging() -> None:
    await _su("svc power stayon usb")


async def wake_screen() -> None:
    """Turn the display on."""
    await _su("input keyevent KEYCODE_WAKEUP")


async def wake_lock() -> None:
    """Stop Android from suspending the CPU."""
    await _check(["termux-wake-lock"])


async def wake_unlock() -> None:
    await _check(["termux-wake-unlock"])


async def set_brightness(level: int) -> None:
    level = max(0, min(255, int(level)))
    await _su(f"settings put system screen_brightness {level}")


async def play_wav(path: str) -> None:
    await _check(["paplay", path], timeout=120)


async def play_pcm(rate: int = 24000) -> asyncio.subprocess.Process:
    """Spawn `paplay` reading raw 16-bit mono LE PCM from stdin.

    The caller writes chunks to `proc.stdin`, closes it, then awaits `proc.wait()`.
    """
    cmd = ["paplay", "--raw", f"--rate={rate}", "--format=s16le", "--channels=1"]
    try:
        return await asyncio.create_subprocess_exec(
            *cmd, stdin=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
    except FileNotFoundError as e:
        raise PlatformError(f"{cmd[0]} not found") from e


_pulse_proc: asyncio.subprocess.Process | None = None


def _no_autospawn_env() -> dict[str, str]:
    """Client config that stops `pactl` from autospawning its own server."""
    path = os.path.join(tempfile.gettempdir(), "opencompanion-pulse-client.conf")
    if not os.path.exists(path):
        with open(path, "w") as f:
            f.write("autospawn = no\n")
    return {"PULSE_CLIENTCONFIG": path}


async def pulseaudio_running() -> bool:
    code, _, _ = await run(["pactl", "info"], timeout=5, env=_no_autospawn_env())
    return code == 0


async def start_pulseaudio(cmd: list[str]) -> None:
    """Make sure a PulseAudio server is up, starting one as a tracked child if not."""
    global _pulse_proc
    if await pulseaudio_running():
        return
    if _pulse_proc is not None and _pulse_proc.returncode is None:
        _pulse_proc.kill()
        await _pulse_proc.wait()
    # Termux: `pulseaudio --start` cannot self-exec, so it forks a fresh server
    # every time instead of finding the running one; run it in the foreground.
    argv = ["--daemonize=no" if a == "--start" else a for a in cmd]
    try:
        _pulse_proc = await asyncio.create_subprocess_exec(
            *argv, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
        )
    except FileNotFoundError as e:
        raise PlatformError(f"{argv[0]} not found") from e
    for _ in range(40):
        await asyncio.sleep(0.25)
        if await pulseaudio_running():
            return
        if _pulse_proc.returncode is not None:
            break
    raise PlatformError("pulseaudio did not come up")


async def stop_pulseaudio() -> None:
    global _pulse_proc
    proc, _pulse_proc = _pulse_proc, None
    if proc is not None and proc.returncode is None:
        proc.terminate()
        try:
            await asyncio.wait_for(proc.wait(), 5)
        except TimeoutError:
            proc.kill()
            await proc.wait()


async def ensure_mic_source() -> bool:
    """Ensure PulseAudio's `module-sles-source` is loaded."""
    # Without it `parec` silently reads the sink monitor instead of the mic.
    out = await _check(["pactl", "list", "short", "modules"])
    if b"module-sles-source" not in out:
        await _check(["pactl", "load-module", "module-sles-source"])
    return True


async def spawn_stream(cmd: list[str]) -> asyncio.subprocess.Process:
    try:
        return await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
    except FileNotFoundError as e:
        raise PlatformError(f"{cmd[0]} not found") from e


def getevent_command(device: str) -> list[str]:
    return ["su", "-c", f"getevent -l {shlex.quote(device)}"]


async def find_input_device(name_substring: str) -> str | None:
    """Return the `/dev/input/eventN` path of the first getevent device whose name
    contains `name_substring` (case-insensitive), or None.
    """
    out = await _su("getevent -pl")
    path: str | None = None
    needle = name_substring.lower()
    for raw_line in out.decode(errors="replace").splitlines():
        line = raw_line.strip()
        if line.startswith("add device"):
            _, _, dev_path = line.partition(":")
            path = dev_path.strip()
        elif line.startswith("name:") and path:
            name = line.split(":", 1)[1].strip().strip('"')
            if needle in name.lower():
                return path
    return None
