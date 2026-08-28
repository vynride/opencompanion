# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serves face/ and pushes state, mouth and caption updates over /ws."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import random
from pathlib import Path

from aiohttp import WSMsgType, web

from opencompanion.bus import Caption, EventBus, Mouth, Reply, StateChanged, Transcript
from opencompanion.state import State

log = logging.getLogger("opencompanion.face")


def _clean(text: str) -> str:
    """Replace em/en dashes with commas so captions read cleanly on screen."""
    return text.replace(" — ", ", ").replace(" – ", ", ").replace("—", ", ").replace("–", ", ").strip()


class FaceServer:
    def __init__(self, bus: EventBus, face_dir: Path, host: str = "127.0.0.1", port: int = 8080) -> None:
        self.bus = bus
        self.face_dir = Path(face_dir)
        self.host = host
        self.port = port
        self.state = State.IDLE
        self._sockets: set[web.WebSocketResponse] = set()
        self._runner: web.AppRunner | None = None
        self.app = web.Application()
        self.app.router.add_get("/", self._index)
        self.app.router.add_get("/ws", self._ws)
        self.app.router.add_get("/{name}", self._asset)
        bus.subscribe(StateChanged, self._on_state)
        bus.subscribe(Mouth, lambda e: self.broadcast({"type": "mouth", "level": round(e.level, 3)}))
        # "said" rides Caption rather than Reply so it reveals in sync with the voice.
        bus.subscribe(
            Transcript, lambda e: self.broadcast({"type": "caption", "who": "heard", "text": _clean(e.text)})
        )
        bus.subscribe(
            Caption,
            lambda e: self.broadcast(
                {"type": "caption", "who": "said", "text": _clean(e.text), "seconds": round(e.seconds, 3)}
            ),
        )

    @property
    def url(self) -> str:
        return f"http://{self.host}:{self.port}/"

    async def _index(self, request: web.Request) -> web.StreamResponse:
        return web.FileResponse(self.face_dir / "index.html")

    async def _asset(self, request: web.Request) -> web.StreamResponse:
        path = self.face_dir / Path(request.match_info["name"]).name  # .name blocks traversal
        if not path.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(path)

    async def _ws(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=10)
        await ws.prepare(request)
        self._sockets.add(ws)
        await ws.send_str(json.dumps({"type": "state", "state": self.state.value}))
        try:
            async for msg in ws:
                if msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            self._sockets.discard(ws)
        return ws

    async def _on_state(self, e: StateChanged) -> None:
        self.state = e.state
        await self.broadcast({"type": "state", "state": e.state.value})

    async def broadcast(self, msg: dict) -> None:
        data = json.dumps(msg)
        for ws in list(self._sockets):
            try:
                await ws.send_str(data)
            except Exception:
                self._sockets.discard(ws)

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        await web.TCPSite(self._runner, self.host, self.port).start()
        log.info("face at %s", self.url)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()
            self._runner = None


async def demo(face_dir: Path, port: int) -> None:
    bus = EventBus()
    server = FaceServer(bus, face_dir, host="127.0.0.1", port=port)
    await server.start()
    print(f"open {server.url} and watch the states cycle; Ctrl-C to stop")
    while True:
        for state in State:
            await bus.publish(StateChanged(state))
            if state == State.SPEAKING:
                line = "Hello, I am the demo voice."
                await bus.publish(Reply(line))
                await bus.publish(Caption(line, 2.0))
                for _ in range(40):
                    await bus.publish(Mouth(random.random()))
                    await asyncio.sleep(0.05)
            elif state == State.LISTENING:
                await bus.publish(Transcript("what time is it"))
                await asyncio.sleep(2)
            else:
                await asyncio.sleep(2)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--demo", action="store_true")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--face-dir", default=str(Path(__file__).resolve().parent.parent / "face"))
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO)
    if not a.demo:
        p.error("only --demo is supported standalone; the daemon starts the server itself")
    try:
        asyncio.run(demo(Path(a.face_dir), a.port))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
