# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Supervisor: wires every module to the bus and restarts crashed loops."""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import time
from collections.abc import Awaitable, Callable
from pathlib import Path

import httpx

from opencompanion import audio, platform, senses, stt, tts, wakeword
from opencompanion.brain import build_brain
from opencompanion.bus import EventBus
from opencompanion.config import Config, load_config
from opencompanion.face_server import FaceServer
from opencompanion.state import StateMachine

log = logging.getLogger("opencompanion.main")

# A run lasting at least this long counts as healthy and resets the backoff.
BACKOFF_RESET_S = 60.0


async def supervise(
    name: str,
    factory: Callable[[], Awaitable[None]],
    *,
    min_backoff: float = 1.0,
    max_backoff: float = 30.0,
    sleep=asyncio.sleep,
) -> None:
    """Run `factory()`, restarting it with exponential backoff when it raises.

    A clean return ends supervision; cancellation propagates.
    """
    backoff = min_backoff
    while True:
        started = time.monotonic()
        try:
            await factory()
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("%s crashed", name)
        else:
            log.info("%s finished; not restarting", name)
            return
        if time.monotonic() - started >= BACKOFF_RESET_S:
            backoff = min_backoff
        log.warning("restarting %s in %.0fs", name, backoff)
        await sleep(backoff)
        backoff = min(backoff * 2, max_backoff)


class App:
    """Everything opencompanion is made of, wired onto one EventBus."""

    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.bus = EventBus()
        self.senses: senses.Senses | None = None
        self.ready = asyncio.Event()  # set once every module is wired and running
        self._stop = asyncio.Event()

    def stop(self) -> None:
        """Ask `run()` to shut down."""
        self._stop.set()

    def runtime_info(self) -> dict:
        last = self.senses.last if self.senses else {}
        return {
            "battery": last.get("battery", "unknown"),
            "last presence": (
                "unknown"
                if "proximity" not in last
                else "near"
                if last["proximity"] < float(self.cfg.get("senses.proximity_near_cm", 5.0))
                else "away"
            ),
        }

    async def run(self) -> None:
        cfg = self.cfg
        self._install_signal_handlers()
        state_machine = StateMachine(
            self.bus,
            idle_to_sleep_s=float(cfg.get("state.idle_to_sleep_s", 300)),
            error_hold_s=float(cfg.get("state.error_hold_s", 3)),
            happy_hold_s=float(cfg.get("state.happy_hold_s", 1)),
            noticing_hold_s=float(cfg.get("state.noticing_hold_s", 1.5)),
            followup_s=float(cfg.get("state.followup_s", 6.0)),
            listen_timeout_s=float(cfg.get("state.listen_timeout_s", 14.0)),
        )
        face = FaceServer(
            self.bus, cfg.root / "face", cfg.get("face.host", "127.0.0.1"), int(cfg.get("face.port", 8080))
        )

        tasks: list[asyncio.Task] = []
        wake_locked = False
        async with httpx.AsyncClient() as http:
            # Inside the try so a failure still runs teardown; handler order matters.
            try:
                state_machine.start()
                await face.start()
                tts.Speaker(self.bus, tts.from_config(cfg, http)).start()
                build_brain(cfg, self.bus, http, self.runtime_info).start()
                stt.Listener(self.bus, cfg, stt.from_config(cfg, http)).start()

                if platform.is_termux():
                    wake_locked = await self._take_power()
                    capture = audio.from_config(self.bus, cfg)
                    tasks.append(asyncio.create_task(supervise("audio", capture.run)))
                    self._start_wake_detector(state_machine)
                    self.senses, touch = senses.from_config(self.bus, cfg)
                    tasks.append(asyncio.create_task(supervise("senses", self.senses.run)))
                    if touch.device:
                        tasks.append(asyncio.create_task(supervise("touch", touch.run)))
                    else:
                        log.info("senses.touch_device is not set: touch watcher disabled")
                    await self._show_face(face.url)
                else:
                    log.info(
                        "not on Termux: audio capture, wake word and senses disabled; "
                        "run the modules standalone to test them"
                    )

                log.info("opencompanion running; face at %s", face.url)
                self.ready.set()
                await self._stop.wait()
            finally:
                log.info("shutting down")
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                state_machine.stop()
                await face.stop()
                await platform.stop_pulseaudio()
                if wake_locked:
                    await self._release_power()

    def _install_signal_handlers(self) -> None:
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self.stop)
            except (NotImplementedError, RuntimeError, ValueError):
                log.debug("cannot install a handler for %s here", sig.name)

    def _start_wake_detector(self, state_machine: StateMachine) -> None:
        try:
            wakeword.from_config(self.bus, self.cfg, armed=state_machine.is_armed).start()
        except Exception:
            log.exception("wake word disabled")

    async def _take_power(self) -> bool:
        """Keep the screen on while charging and the CPU awake; True if locked."""
        try:
            await platform.stay_on_while_charging()
        except platform.PlatformError as e:
            log.warning("could not keep the screen on: %s", e)
        try:
            await platform.wake_lock()
        except platform.PlatformError as e:
            log.warning("could not take a wake lock: %s", e)
            return False
        return True

    async def _release_power(self) -> None:
        try:
            await platform.wake_unlock()
        except platform.PlatformError as e:
            log.warning("could not release the wake lock: %s", e)

    async def _show_face(self, url: str) -> None:
        """Open the face page in a browser and tap it; failures are logged, never fatal."""
        if not self.cfg.get("face.open_browser", True):
            log.info("face.open_browser is off: not launching the browser")
            return
        # requestFullscreen() needs a user gesture, hence the injected tap.
        try:
            await platform.wake_screen()
            await platform.launch_face(url, self.cfg.get("face.browser_package", "") or "")
            await asyncio.sleep(float(self.cfg.get("face.tap_delay_s", 2.0)))
            await platform.wake_screen()
            x, y = self.cfg.get("face.tap_xy", [360, 640])
            await platform.tap_natural(int(x), int(y))
        except (platform.PlatformError, TypeError, ValueError) as e:
            log.warning("could not show the face page: %s", e)


def main() -> None:
    p = argparse.ArgumentParser(description="Run the opencompanion desk companion daemon")
    p.add_argument("--config", default="config.yaml")
    p.add_argument("--env", default=".env")
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    cfg = load_config(Path(a.config), Path(a.env))
    try:
        asyncio.run(App(cfg).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
