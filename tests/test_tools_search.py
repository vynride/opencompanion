# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import json

import httpx
import respx

from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.search import format_results, make_tools

EXA = {
    "results": [
        {"title": "A", "url": "https://example.com/a", "text": "alpha " * 400},
        {"title": "B", "url": "https://example.com/b", "text": "beta"},
    ]
}


def test_format_results_truncates():
    text = format_results(EXA["results"], max_chars=20)
    assert "1. A - https://example.com/a" in text
    assert len(text.split("\n\n")[0]) < 80


@respx.mock
async def test_web_search_posts_expected_body():
    route = respx.post("https://api.exa.ai/search").mock(return_value=httpx.Response(200, json=EXA))
    cfg = Config({"search": {"max_results": 3, "max_chars": 1500}}, {"EXA_API_KEY": "k"})
    ctx = ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient())
    text = await make_tools(ctx)[0].func(query="desk companions")
    assert "https://example.com/b" in text
    req = route.calls[0].request
    assert req.headers["x-api-key"] == "k"
    body = json.loads(req.content)
    assert body["query"] == "desk companions"
    assert body["numResults"] == 3
    assert body["contents"] == {"text": {"maxCharacters": 1500}}


async def test_web_search_disabled_without_key():
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient())
    tool = make_tools(ctx)[0]
    assert tool.name == "web_search"
    assert "disabled" in await tool.func(query="x")


@respx.mock
async def test_web_search_reports_http_failure():
    respx.post("https://api.exa.ai/search").mock(return_value=httpx.Response(503))
    ctx = ToolContext(cfg=Config({}, {"EXA_API_KEY": "k"}), bus=EventBus(), http=httpx.AsyncClient())
    assert (await make_tools(ctx)[0].func(query="x")).startswith("Search failed")
