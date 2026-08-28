# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""web_search via Exa."""

from __future__ import annotations

import httpx

from opencompanion.tools import Tool, ToolContext, disabled_tool

URL = "https://api.exa.ai/search"
DESC = "Search the web and return the top results with a short text excerpt."
PARAMS = {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}


async def exa_search(
    http: httpx.AsyncClient, api_key: str, query: str, max_results: int, max_chars: int
) -> list[dict]:
    r = await http.post(
        URL,
        headers={"x-api-key": api_key},
        json={
            "query": query,
            "numResults": max_results,
            "type": "auto",
            "contents": {"text": {"maxCharacters": max_chars}},
        },
        timeout=15,
    )
    r.raise_for_status()
    return [
        {"title": x.get("title") or x.get("url", ""), "url": x.get("url", ""), "text": x.get("text", "")}
        for x in r.json().get("results", [])
    ]


def format_results(results: list[dict], max_chars: int) -> str:
    blocks = []
    for i, x in enumerate(results, 1):
        excerpt = " ".join(x["text"].split())[:max_chars]
        blocks.append(f"{i}. {x['title']} - {x['url']}\n{excerpt}")
    return "\n\n".join(blocks) if blocks else "No results."


def make_tools(ctx: ToolContext) -> list[Tool]:
    key = ctx.cfg.key("EXA_API_KEY")
    if not key:
        return [disabled_tool("web_search", DESC, "EXA_API_KEY missing")]
    n = int(ctx.cfg.get("search.max_results", 3))
    chars = int(ctx.cfg.get("search.max_chars", 1500))

    async def web_search(query: str) -> str:
        try:
            return format_results(await exa_search(ctx.http, key, query, n, chars), chars)
        except httpx.HTTPError as e:
            return f"Search failed: {e}"

    return [Tool("web_search", DESC, PARAMS, web_search)]
