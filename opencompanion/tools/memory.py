# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Plain-markdown memory: personality.md, facts.md, journal/YYYY-MM-DD.md."""

from __future__ import annotations

import logging
from collections.abc import Callable
from datetime import date, datetime
from pathlib import Path

from opencompanion.tools import Tool, ToolContext

log = logging.getLogger("opencompanion.memory")


class Memory:
    def __init__(
        self, root: Path, max_facts_lines: int = 150, today: Callable[[], date] = date.today
    ) -> None:
        self.root = Path(root)
        self.max_facts_lines = max_facts_lines
        self.today = today

    @property
    def facts_path(self) -> Path:
        return self.root / "facts.md"

    def _read(self, path: Path) -> str:
        try:
            return path.read_text()
        except FileNotFoundError:
            return ""

    def personality(self) -> str:
        return self._read(self.root / "personality.md")

    def facts(self) -> str:
        return self._read(self.facts_path)

    def remember(self, text: str) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        with self.facts_path.open("a") as f:
            f.write(f"- {text.strip()}\n")
        self.prune_facts()

    def recall(self, query: str) -> list[str]:
        q = query.lower()
        return [ln for ln in self.facts().splitlines() if ln.strip() and q in ln.lower()]

    def prune_facts(self) -> int:
        """Drop the oldest lines from facts.md until it is at the cap."""
        lines = self.facts().splitlines()
        excess = len(lines) - self.max_facts_lines
        if excess <= 0:
            return 0
        self.facts_path.write_text("\n".join(lines[excess:]) + "\n")
        log.warning("facts.md pruned: dropped %d oldest lines", excess)
        return excess

    def journal_path(self, day: date) -> Path:
        return self.root / "journal" / f"{day.isoformat()}.md"

    def journal_append(self, role: str, text: str) -> None:
        try:
            path = self.journal_path(self.today())
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a") as f:
                f.write(f"- {datetime.now().strftime('%H:%M')} {role}: {text.strip()}\n")
        except OSError as e:
            log.error("journal write failed: %s", e)

    def journal_for(self, day: date) -> str:
        return self._read(self.journal_path(day))

    def journal_today(self) -> str:
        return self.journal_for(self.today())

    def has_summary(self, day: date) -> bool:
        return f"## {day.isoformat()}" in self.facts()

    def add_daily_summary(self, day: date, bullets: list[str]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        body = "\n".join(f"- {b.strip().lstrip('-').strip()}" for b in bullets[:5])
        with self.facts_path.open("a") as f:
            f.write(f"\n## {day.isoformat()}\n{body}\n")
        self.prune_facts()


def make_tools(ctx: ToolContext) -> list[Tool]:
    mem = ctx.memory
    if mem is None:
        raise RuntimeError("ToolContext.memory is required for memory tools")

    async def remember(text: str) -> str:
        mem.remember(text)
        return f"Remembered: {text}"

    async def recall(query: str) -> str:
        hits = mem.recall(query)
        return "\n".join(hits) if hits else f"Nothing remembered about '{query}'."

    return [
        Tool(
            "remember",
            "Save a fact to long-term memory.",
            {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
            remember,
        ),
        Tool(
            "recall",
            "Search long-term memory for a keyword.",
            {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
            recall,
        ),
    ]
