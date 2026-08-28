# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import json
from pathlib import Path

from aiohttp.test_utils import TestClient, TestServer

from opencompanion.bus import Caption, EventBus, Mouth, Reply, StateChanged, Transcript
from opencompanion.face_server import FaceServer
from opencompanion.state import State

FACE_DIR = Path(__file__).resolve().parent.parent / "face"


async def make_client():
    bus = EventBus()
    server = FaceServer(bus, FACE_DIR)
    client = TestClient(TestServer(server.app))
    await client.start_server()
    return bus, server, client


class DeadSocket:
    """A socket whose peer has vanished: every send raises."""

    closed = True

    async def send_str(self, data: str) -> None:
        raise ConnectionResetError("Cannot write to closing transport")


async def test_serves_index_and_assets():
    bus, server, client = await make_client()
    r = await client.get("/")
    assert r.status == 200
    assert "eyes.js" in await r.text()
    assert (await client.get("/eyes.js")).status == 200
    assert (await client.get("/eyes.css")).status == 200
    await client.close()


async def test_unknown_asset_is_404():
    bus, server, client = await make_client()
    assert (await client.get("/nope.js")).status == 404
    await client.close()


async def test_asset_path_traversal_is_blocked():
    bus, server, client = await make_client()
    assert (await client.get("/..%2Fpyproject.toml")).status == 404
    await client.close()


def test_url_property():
    bus = EventBus()
    server = FaceServer(bus, FACE_DIR, host="0.0.0.0", port=9999)
    assert server.url == "http://0.0.0.0:9999/"


async def test_ws_gets_state_on_connect_then_events():
    bus, server, client = await make_client()
    ws = await client.ws_connect("/ws")
    first = json.loads(await ws.receive_str())
    assert first == {"type": "state", "state": "idle"}

    await bus.publish(StateChanged(State.LISTENING))
    assert json.loads(await ws.receive_str()) == {"type": "state", "state": "listening"}

    await bus.publish(Mouth(0.5))
    assert json.loads(await ws.receive_str()) == {"type": "mouth", "level": 0.5}

    await bus.publish(Transcript("hello"))
    assert json.loads(await ws.receive_str()) == {"type": "caption", "who": "heard", "text": "hello"}

    await bus.publish(Caption("hi there", 1.5))
    assert json.loads(await ws.receive_str()) == {
        "type": "caption",
        "who": "said",
        "text": "hi there",
        "seconds": 1.5,
    }

    # Reply broadcasts nothing, so the next frame received is the mouth event.
    await bus.publish(Reply("no caption for this"))
    await bus.publish(Mouth(0.25))
    assert json.loads(await ws.receive_str()) == {"type": "mouth", "level": 0.25}
    await ws.close()
    await client.close()


async def test_late_client_gets_the_current_state():
    bus, server, client = await make_client()
    await bus.publish(StateChanged(State.THINKING))
    ws = await client.ws_connect("/ws")
    assert json.loads(await ws.receive_str()) == {"type": "state", "state": "thinking"}
    await ws.close()
    await client.close()


async def test_broadcast_survives_closed_socket():
    bus, server, client = await make_client()
    alive = await client.ws_connect("/ws")
    await alive.receive_str()
    dead = DeadSocket()
    server._sockets.add(dead)

    await server.broadcast({"type": "state", "state": "idle"})

    assert dead not in server._sockets
    assert json.loads(await alive.receive_str()) == {"type": "state", "state": "idle"}
    await alive.close()
    await client.close()


def test_clean_replaces_dashes_for_the_screen():
    from opencompanion.face_server import _clean

    assert _clean("It's 27C — overcast — and humid") == "It's 27C, overcast, and humid"
    assert _clean("a–b") == "a, b"
    assert _clean("no dashes here") == "no dashes here"
