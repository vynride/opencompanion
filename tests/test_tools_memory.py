# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date
from pathlib import Path

import httpx
import pytest

from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.memory import Memory, make_tools


@pytest.fixture
def mem(tmp_path: Path):
    (tmp_path / "personality.md").write_text("# Soc\nbe kind\n")
    return Memory(tmp_path, max_facts_lines=5, today=lambda: date(2026, 8, 28))


def test_personality_and_empty_facts(mem):
    assert "be kind" in mem.personality()
    assert mem.facts() == ""


def test_remember_and_recall(mem):
    mem.remember("The plant is watered on Mondays")
    mem.remember("Coffee beans are in the top drawer")
    assert mem.recall("coffee") == ["- Coffee beans are in the top drawer"]
    assert mem.recall("nothing") == []
    assert mem.facts().count("\n") == 2


def test_prune_keeps_newest(mem, caplog):
    for i in range(8):
        mem.remember(f"fact {i}")
    lines = mem.facts().strip().splitlines()
    assert lines == [f"- fact {i}" for i in range(3, 8)]
    assert "pruned" in caplog.text


def test_journal_append_and_read(mem):
    mem.journal_append("user", "what time is it")
    mem.journal_append("opencompanion", "it is noon")
    today = mem.journal_today()
    assert "user: what time is it" in today and "opencompanion: it is noon" in today
    assert mem.journal_for(date(2026, 8, 27)) == ""


def test_journal_failure_is_logged_not_raised(tmp_path, caplog):
    blocked = tmp_path / "file-not-dir"
    blocked.write_text("x")
    m = Memory(blocked, today=lambda: date(2026, 8, 28))
    m.journal_append("user", "hi")
    assert "journal" in caplog.text.lower()


def test_daily_summary(mem):
    assert not mem.has_summary(date(2026, 8, 27))
    mem.add_daily_summary(date(2026, 8, 27), ["talked about tea", "set two timers"])
    assert mem.has_summary(date(2026, 8, 27))
    assert "## 2026-08-27" in mem.facts()
    assert "- set two timers" in mem.facts()


async def test_tools_use_ctx_memory(mem):
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient(), memory=mem)
    tools = {t.name: t for t in make_tools(ctx)}
    assert "Remembered" in await tools["remember"].func(text="keys on the hook")
    assert "keys on the hook" in await tools["recall"].func(query="keys")
    assert "Nothing" in await tools["recall"].func(query="zebra")


def test_make_tools_raises_when_memory_missing():
    ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient(), memory=None)
    with pytest.raises(RuntimeError):
        make_tools(ctx)
