# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Chat loop: prompt assembly, tool calling, session window."""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import random
from collections.abc import Callable
from datetime import datetime, timedelta

import httpx

from opencompanion.bus import Error, EventBus, Reply, Say, Transcript
from opencompanion.config import Config, Service, load_config
from opencompanion.retry import ATTEMPTS, retry_delay, should_retry
from opencompanion.tools import ToolContext, ToolRegistry, build_registry
from opencompanion.tools.memory import Memory

log = logging.getLogger("opencompanion.brain")

LENGTH_RULE = (
    "Reply in ONE short spoken sentence (two only if truly needed); the reply is "
    "read aloud and shown as a caption, so be brief and conversational. No markdown, "
    "no lists, no dashes as punctuation: use commas or periods."
)
NO_LLM_REPLY = "My language model is not configured."
OUT_OF_STEPS = "I ran out of steps; ask me again."
SKIPPED_CALL = "Skipped: this turn's tool call budget is spent. Answer with what you already have."
SUMMARY_PROMPT = (
    "Summarize this desk-companion journal into at most 5 short bullets "
    "of facts worth remembering. Output only bullets starting with '- '."
)


def image_message(question: str, jpeg: bytes) -> dict:
    b64 = base64.b64encode(jpeg).decode()
    # chat-completions image shape; _to_responses converts it for the responses API
    return {
        "role": "user",
        "content": [
            {"type": "text", "text": question},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
        ],
    }


def _to_responses(messages: list[dict]) -> tuple[str, list[dict]]:
    """Chat-format messages -> Responses API `instructions` and `input` items."""
    instructions: list[str] = []
    items: list[dict] = []
    for m in messages:
        role = m.get("role")
        content = m.get("content")
        if role == "system":
            if content:
                instructions.append(content)
        elif role == "user":
            if isinstance(content, list):
                text = next((p["text"] for p in content if p.get("type") == "text"), "")
                url = next((p["image_url"]["url"] for p in content if p.get("type") == "image_url"), "")
                parts: list[dict] = [{"type": "input_text", "text": text}]
                if url:
                    parts.append({"type": "input_image", "image_url": url})
                items.append({"role": "user", "content": parts})
            else:
                items.append({"role": "user", "content": content})
        elif role == "assistant":
            if content:
                items.append({"role": "assistant", "content": content})
            for tc in m.get("tool_calls") or []:
                fn = tc["function"]
                items.append(
                    {
                        "type": "function_call",
                        "call_id": tc["id"],
                        "name": fn["name"],
                        "arguments": fn.get("arguments") or "",
                    }
                )
        elif role == "tool":
            items.append({"type": "function_call_output", "call_id": m["tool_call_id"], "output": content})
    return "\n\n".join(instructions), items


def _flatten_tool(spec: dict) -> dict:
    fn = spec["function"]
    return {
        "type": "function",
        "name": fn["name"],
        "description": fn.get("description"),
        "parameters": fn.get("parameters"),
    }


def _parse_responses(data: dict) -> dict:
    """Responses API output -> a chat-format assistant message."""
    tool_calls: list[dict] = []
    texts: list[str] = []
    for item in data.get("output") or []:
        kind = item.get("type")
        if kind == "function_call":
            tool_calls.append(
                {
                    "id": item["call_id"],
                    "type": "function",
                    "function": {"name": item["name"], "arguments": item.get("arguments") or ""},
                }
            )
        elif kind == "message":
            for part in item.get("content") or []:
                if part.get("type") == "output_text":
                    texts.append(part.get("text") or "")
    msg: dict = {"role": "assistant", "content": "".join(texts) or None}
    if tool_calls:
        msg["tool_calls"] = tool_calls
    return msg


class ChatClient:
    """Chat against an OpenAI-compatible endpoint via `chat/completions` (default)
    or the `responses` API. Takes and returns chat-format messages either way."""

    def __init__(
        self, http: httpx.AsyncClient, service: Service, api: str = "chat", reasoning_effort: str = ""
    ) -> None:
        self.http = http
        self.service = service
        self.api = api
        self.reasoning_effort = reasoning_effort

    @property
    def model(self) -> str:
        return self.service.model

    def _request(self, messages: list[dict], tools: list[dict] | None) -> tuple[str, dict]:
        if self.api == "responses":
            instructions, items = _to_responses(messages)
            body: dict = {"model": self.model, "input": items}
            if instructions:
                body["instructions"] = instructions
            if tools:
                body["tools"] = [_flatten_tool(t) for t in tools]
                body["tool_choice"] = "auto"
            if self.reasoning_effort:
                body["reasoning"] = {"effort": self.reasoning_effort}
            return self.service.url("responses"), body
        body = {"model": self.model, "messages": messages}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        if self.reasoning_effort:
            body["reasoning_effort"] = self.reasoning_effort
        return self.service.url("chat/completions"), body

    def _parse(self, data: dict) -> dict:
        if self.api == "responses":
            return _parse_responses(data)
        msg = data["choices"][0]["message"]
        out: dict = {"role": "assistant", "content": msg.get("content")}
        if msg.get("tool_calls"):
            out["tool_calls"] = msg["tool_calls"]
        return out

    async def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        url, body = self._request(messages, tools)
        for attempt in range(1, ATTEMPTS + 1):
            try:
                r = await self.http.post(url, json=body, headers=self.service.headers(), timeout=60)
            except httpx.TransportError as e:
                if attempt == ATTEMPTS:
                    raise
                log.warning("chat network error, retrying: %s", e)
                await asyncio.sleep(0.6 * attempt)
                continue
            if should_retry(r, attempt):
                log.warning("chat %d, retrying (%d/%d)", r.status_code, attempt, ATTEMPTS)
                await asyncio.sleep(retry_delay(r, attempt))
                continue
            r.raise_for_status()
            return self._parse(r.json())
        raise AssertionError("unreachable")


class Brain:
    """Turns a Transcript into a Reply: prompt assembly, tool loop, session window."""

    def __init__(
        self,
        bus: EventBus,
        cfg: Config,
        memory: Memory,
        llm: ChatClient | None,
        tools: ToolRegistry | None,
        runtime_info: Callable[[], dict] = lambda: {},
        now: Callable[[], datetime] = datetime.now,
    ) -> None:
        self.bus = bus
        self.cfg = cfg
        self.memory = memory
        self.llm = llm
        self.tools = tools or ToolRegistry(bus)
        self.runtime_info = runtime_info
        self.now = now
        self.history: list[dict] = []
        self.last_turn: datetime | None = None
        self._said_no_llm = False
        self.name = str(cfg.get("companion.name", "Soc"))
        self.vision = bool(cfg.get("brain.vision", True))
        self.max_turns = int(cfg.get("brain.max_turns", 20))
        self.max_tool_calls = int(cfg.get("brain.max_tool_calls", 4))
        self.filler_after_s = float(cfg.get("brain.filler_after_s", 2.0))
        self.fillers = list(cfg.get("brain.fillers", ["One moment."]))
        self.session_reset = timedelta(minutes=float(cfg.get("brain.session_reset_min", 30)))

    def build_system_prompt(self) -> str:
        runtime = {
            "local time": self.now().strftime("%A %H:%M"),
            "location": self.cfg.get("location.name", "unknown"),
        }
        runtime.update(self.runtime_info())
        blocks = [
            f"Your name is {self.name}.",
            self.memory.personality().strip(),
            "## Facts\n" + (self.memory.facts().strip() or "(none yet)"),
            "## Today's journal\n" + (self.memory.journal_today().strip() or "(nothing yet)"),
            "## Runtime\n" + "\n".join(f"{k}: {v}" for k, v in runtime.items()),
            LENGTH_RULE,
        ]
        return "\n\n".join(blocks)

    def build_messages(self, user_text: str) -> list[dict]:
        keep = self.history[-(self.max_turns - 1) * 2 :] if self.max_turns > 1 else []
        return [
            {"role": "system", "content": self.build_system_prompt()},
            *keep,
            {"role": "user", "content": user_text},
        ]

    def _maybe_reset_session(self) -> None:
        if self.last_turn and self.now() - self.last_turn > self.session_reset:
            log.info("session reset after idle")
            self.history.clear()
        self.last_turn = self.now()

    async def _call_tool_with_filler(self, name: str, args: dict) -> str:
        """Run a tool, speaking a short filler if it is still going after filler_after_s."""
        task = asyncio.ensure_future(self.tools.call(name, args))
        done, _ = await asyncio.wait({task}, timeout=self.filler_after_s)
        if not done:
            await self.bus.publish(Say(random.choice(self.fillers)))
        return await task

    async def handle(self, text: str) -> str:
        text = text.strip()
        if not text:
            return ""
        if self.llm is None:
            if self._said_no_llm:
                log.debug("brain disabled, ignoring: %s", text)
                return ""
            self._said_no_llm = True
            log.warning("chat model not configured")
            await self.bus.publish(Reply(NO_LLM_REPLY))
            return NO_LLM_REPLY
        self._maybe_reset_session()
        self.memory.journal_append("user", text)
        messages = self.build_messages(text)
        try:
            reply = await self._loop(self.llm, messages)
        except httpx.HTTPError as e:
            log.error("chat failed: %s", e)
            await self.bus.publish(Error("the chat model", str(e)))
            return ""
        self.history += [{"role": "user", "content": text}, {"role": "assistant", "content": reply}]
        self.memory.journal_append(self.name.lower(), reply)
        await self.bus.publish(Reply(reply))
        return reply

    async def _loop(self, llm: ChatClient, messages: list[dict]) -> str:
        """Chat until the model answers without tool calls or the budget is spent.

        max_tool_calls counts executed calls, not round trips.
        """
        specs = self.tools.specs()
        spent = 0
        while True:
            budget = self.max_tool_calls - spent
            tools = specs if (specs and budget > 0) else None
            msg = await llm.chat(messages, tools)
            calls = msg.get("tool_calls") or []
            content = (msg.get("content") or "").strip()
            if not calls:
                return content
            if tools is None:
                # no tools offered but it asked anyway
                log.warning("model asked for tools with no budget left")
                return content or OUT_OF_STEPS
            messages.append({"role": "assistant", "content": msg.get("content"), "tool_calls": calls})
            for i, call in enumerate(calls):
                if i >= budget:
                    log.warning("tool call budget spent, skipping %s", call["function"].get("name"))
                    messages.append({"role": "tool", "tool_call_id": call["id"], "content": SKIPPED_CALL})
                    continue
                fn = call["function"]
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except json.JSONDecodeError:
                    log.warning("tool %s got unparseable arguments", fn.get("name"))
                    args = {}
                result = await self._call_tool_with_filler(fn["name"], args)
                messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
                spent += 1

    async def ask_vision(self, question: str, jpeg: bytes) -> str:
        if self.llm is None:
            return "Vision is not configured."
        if not self.vision:
            return "I can't see images right now (vision is disabled)."
        msg = await self.llm.chat(
            [{"role": "system", "content": self.build_system_prompt()}, image_message(question, jpeg)]
        )
        return (msg.get("content") or "").strip()

    async def maybe_summarize_yesterday(self) -> None:
        """On the first turn of a new day, fold yesterday's journal into facts.md."""
        if self.llm is None:
            return
        yesterday = self.memory.today() - timedelta(days=1)
        journal = self.memory.journal_for(yesterday).strip()
        if not journal or self.memory.has_summary(yesterday):
            return
        try:
            msg = await self.llm.chat(
                [{"role": "system", "content": SUMMARY_PROMPT}, {"role": "user", "content": journal}]
            )
        except httpx.HTTPError as e:
            log.warning("journal summary failed: %s", e)
            return
        bullets = [ln for ln in (msg.get("content") or "").splitlines() if ln.strip().startswith("-")]
        self.memory.add_daily_summary(yesterday, bullets or ["(no summary produced)"])

    def start(self) -> None:
        async def on_transcript(e: Transcript) -> None:
            await self.maybe_summarize_yesterday()
            await self.handle(e.text)

        self.bus.subscribe(Transcript, on_transcript)


def build_brain(
    cfg: Config, bus: EventBus, http: httpx.AsyncClient, runtime_info: Callable[[], dict] = lambda: {}
) -> Brain:
    memory = Memory(cfg.root / cfg.get("memory.dir", "memory"), int(cfg.get("memory.max_facts_lines", 150)))
    service = cfg.service("chat")
    if service is None:
        log.warning("chat disabled: set openai.chat_model and OPENAI_API_KEY")
    llm = (
        ChatClient(http, service, cfg.get("brain.api", "chat"), cfg.get("brain.reasoning_effort") or "")
        if service
        else None
    )
    # look needs ask_vision, so build Brain before the registry.
    ctx = ToolContext(cfg=cfg, bus=bus, http=http, memory=memory)
    brain = Brain(bus, cfg, memory, llm, None, runtime_info)
    ctx.ask_vision = brain.ask_vision
    brain.tools = build_registry(ctx)
    return brain


async def _cli(text: str) -> None:
    cfg = load_config()
    bus = EventBus()
    async with httpx.AsyncClient() as http:
        brain = build_brain(cfg, bus, http)
        print(await brain.handle(text))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--text", required=True)
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO)
    asyncio.run(_cli(a.text))


if __name__ == "__main__":
    main()
