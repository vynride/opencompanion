# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import io
from unittest.mock import AsyncMock

import httpx
from PIL import Image

from opencompanion import platform
from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.look import make_tools, shrink_jpeg


def jpeg(w, h):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (10, 20, 30)).save(buf, "JPEG")
    return buf.getvalue()


def test_shrink_jpeg_limits_longest_side():
    out = shrink_jpeg(jpeg(2048, 1024), max_px=1024)
    assert Image.open(io.BytesIO(out)).size == (1024, 512)
    small = jpeg(300, 200)
    assert shrink_jpeg(small, 1024) == small


async def test_look_takes_photo_and_asks_vision(monkeypatch, tmp_path):
    async def fake_photo(path, camera_id=1):
        assert camera_id == 1
        open(path, "wb").write(jpeg(64, 64))

    monkeypatch.setattr(platform, "camera_photo", fake_photo)
    vision = AsyncMock(return_value="a grey wall")
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient(), ask_vision=vision)
    tool = make_tools(ctx)[0]
    assert await tool.func(question="what do you see?") == "a grey wall"
    q, data = vision.call_args.args
    assert q == "what do you see?" and data[:2] == b"\xff\xd8"


async def test_look_disabled_without_vision():
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient())
    assert "disabled" in await make_tools(ctx)[0].func(question="x")


async def test_look_reports_camera_failure(monkeypatch):
    monkeypatch.setattr(platform, "camera_photo", AsyncMock(side_effect=platform.PlatformError("no camera")))
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient(), ask_vision=AsyncMock())
    assert "no camera" in await make_tools(ctx)[0].func(question="x", camera="back")


async def test_look_uses_configured_max_px(monkeypatch):
    async def fake_photo(path, camera_id=1):
        open(path, "wb").write(jpeg(2048, 1024))

    monkeypatch.setattr(platform, "camera_photo", fake_photo)
    vision = AsyncMock(return_value="ok")
    cfg = Config({"look": {"max_px": 256}}, {})
    ctx = ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient(), ask_vision=vision)
    tool = make_tools(ctx)[0]
    await tool.func(question="what do you see?")
    _, data = vision.call_args.args
    assert Image.open(io.BytesIO(data)).size == (256, 128)
