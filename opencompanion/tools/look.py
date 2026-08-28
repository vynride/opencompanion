# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""look: take a photo with the phone camera and ask the vision model about it."""

from __future__ import annotations

import asyncio
import io
import logging
import tempfile
from pathlib import Path

from opencompanion import platform
from opencompanion.tools import Tool, ToolContext, disabled_tool

log = logging.getLogger("opencompanion.tools.look")

CAMERA_IDS = {"front": 1, "back": 0}
DESC = "Take a photo with the phone camera and answer a question about what is visible."
PARAMS = {
    "type": "object",
    "properties": {
        "question": {"type": "string"},
        "camera": {"type": "string", "enum": ["front", "back"], "default": "front"},
    },
    "required": ["question"],
}


def shrink_jpeg(data: bytes, max_px: int = 1024) -> bytes:
    try:
        from PIL import Image
    except ImportError:
        return data
    img = Image.open(io.BytesIO(data))
    if max(img.size) <= max_px:
        return data
    img.thumbnail((max_px, max_px))
    out = io.BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=85)
    return out.getvalue()


def make_tools(ctx: ToolContext) -> list[Tool]:
    ask = ctx.ask_vision
    if ask is None:
        return [disabled_tool("look", DESC, "vision model not configured")]
    max_px = int(ctx.cfg.get("look.max_px", 1024))

    async def look(question: str, camera: str = "front") -> str:
        cam = CAMERA_IDS.get(camera, 1)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "look.jpg"
            raw = b""
            # termux-camera-photo can exit 0 with no file or a truncated one.
            for attempt in range(3):
                try:
                    await platform.camera_photo(str(path), cam)
                except platform.PlatformError as e:
                    return f"Camera failed: {e}"
                raw = path.read_bytes() if path.exists() else b""
                if raw[:2] == b"\xff\xd8":  # valid JPEG
                    break
                log.warning("camera returned %d bytes (not a JPEG), retrying (%d/3)", len(raw), attempt + 1)
                await asyncio.sleep(0.4)
            if raw[:2] != b"\xff\xd8":
                return "The camera didn't return a usable image; please try again."
            data = shrink_jpeg(raw, max_px)
        return await ask(question, data)

    return [Tool("look", DESC, PARAMS, look)]
