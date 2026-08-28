# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio
import json
from datetime import date, datetime
from pathlib import Path

import httpx
import pytest
import respx

from opencompanion.brain import Brain, ChatClient, build_brain, image_message
from opencompanion.bus import Error, EventBus, Reply, Say, Transcript
from opencompanion.config import Config, Service
from opencompanion.tools import Tool, ToolRegistry
from opencompanion.tools.memory import Memory

BASE = "https://api.example/v1"
MODEL = "chat-model"
SERVICE = Service(BASE, "key", MODEL)
URL = f"{BASE}/responses"
CHAT_URL = f"{BASE}/chat/completions"


def msg(content=None, tool_calls=None):
    """A Responses-shaped `{"output": [...]}` payload.

    An optional leading `reasoning` item is included (Brain must ignore it),
    then a `function_call` item per tool call, then the final assistant
    `message` if there is text content.
    """
    output = [{"type": "reasoning", "summary": []}]
    for c in tool_calls or []:
        output.append(
            {
                "type": "function_call",
                "name": c["function"]["name"],
                "arguments": c["function"]["arguments"],
                "call_id": c["id"],
                "id": "fc_" + c["id"],
            }
        )
    if content is not None:
        output.append(
            {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": content}]}
        )
    return {"output": output}


def tc(name, args, id="call_1"):
    return {"id": id, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}


class FakeLLM:
    """Scripted stand-in for ChatClient.chat (no network).

    Each scripted entry is a normalized assistant message dict, exactly what
    `ChatClient.chat` returns.
    """

    def __init__(self, *responses: dict) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[list[dict], list[dict] | None]] = []

    async def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        self.calls.append(([dict(m) for m in messages], tools))
        if not self.responses:
            raise AssertionError("FakeLLM ran out of scripted responses")
        return self.responses.pop(0)


@pytest.fixture
def setup(tmp_path: Path):
    (tmp_path / "personality.md").write_text("You are Soc.\n")
    mem = Memory(tmp_path, today=lambda: date(2026, 8, 28))
    mem.remember("The user likes tea")
    bus = EventBus()
    reg = ToolRegistry(bus)

    async def get_time():
        return "noon"

    async def slow():
        await asyncio.sleep(0.05)
        return "done"

    reg.register(Tool("get_time", "d", {"type": "object", "properties": {}}, get_time))
    reg.register(Tool("slow", "d", {"type": "object", "properties": {}}, slow))
    cfg = Config(
        {
            "brain": {"filler_after_s": 0.01, "fillers": ["Hmm."], "max_turns": 2, "session_reset_min": 30},
            "location": {"name": "Somewhere", "timezone": "UTC"},
        },
        {},
    )
    llm = ChatClient(httpx.AsyncClient(), SERVICE, api="responses", reasoning_effort="low")
    brain = Brain(
        bus,
        cfg,
        mem,
        llm,
        reg,
        runtime_info=lambda: {"battery": "80%"},
        now=lambda: datetime(2026, 8, 28, 12, 0),
    )
    return bus, brain, mem


def test_system_prompt_contains_all_blocks(setup):
    bus, brain, mem = setup
    mem.journal_append("user", "earlier line")
    p = brain.build_system_prompt()
    assert "Your name is Soc." in p and "You are Soc." in p
    assert "- The user likes tea" in p
    assert "earlier line" in p
    assert "battery: 80%" in p and "location: Somewhere" in p
    assert "spoken sentence" in p and "no dashes" in p


def test_image_message_shape():
    m = image_message("what is this", b"\xff\xd8abc")
    assert m["role"] == "user"
    assert m["content"][0] == {"type": "text", "text": "what is this"}
    assert m["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


@respx.mock
async def test_simple_reply_publishes_and_journals(setup):
    bus, brain, mem = setup
    respx.post(URL).mock(return_value=httpx.Response(200, json=msg("Hello there.")))
    replies = []
    bus.subscribe(Reply, replies.append)
    assert await brain.handle("hi") == "Hello there."
    assert replies == [Reply("Hello there.")]
    j = mem.journal_today()
    assert "user: hi" in j and "soc: Hello there." in j


@respx.mock
async def test_responses_request_shape(setup):
    bus, brain, mem = setup
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=msg("ok")))
    await brain.handle("hi")
    req = route.calls[0].request
    assert req.headers["authorization"] == "Bearer key"
    assert str(req.url) == URL
    body = json.loads(req.content)
    assert body["model"] == MODEL
    assert body["reasoning"]["effort"] == "low"
    assert "temperature" not in body
    assert "messages" not in body
    assert body["instructions"]
    assert body["input"][-1] == {"role": "user", "content": "hi"}


@respx.mock
async def test_chat_completions_request_and_tool_calls():
    calls = []

    async def clock(**kw):
        calls.append(kw)
        return "12:00"

    reg = ToolRegistry(EventBus())
    reg.register(Tool("clock", "d", {"type": "object", "properties": {}}, clock))
    route = respx.post(CHAT_URL)
    route.side_effect = [
        httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "type": "function",
                                    "function": {"name": "clock", "arguments": "{}"},
                                }
                            ],
                        }
                    }
                ]
            },
        ),
        httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "It is noon."}}]}),
    ]
    llm = ChatClient(httpx.AsyncClient(), SERVICE)
    messages = [{"role": "system", "content": "sys"}, {"role": "user", "content": "time?"}]
    brain = Brain(EventBus(), Config({}, {}), Memory.__new__(Memory), llm, reg)
    assert await brain._loop(llm, messages) == "It is noon."
    first = json.loads(route.calls[0].request.content)
    assert first["model"] == MODEL and first["messages"][0]["role"] == "system"
    assert first["tools"][0]["function"]["name"] == "clock" and first["tool_choice"] == "auto"
    assert "reasoning_effort" not in first and "reasoning" not in first
    second = json.loads(route.calls[1].request.content)
    assert second["messages"][-1] == {"role": "tool", "tool_call_id": "c1", "content": "12:00"}
    assert calls == [{}]


@respx.mock
async def test_tool_loop_and_filler(setup):
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, [tc("slow", {})])),
        httpx.Response(200, json=msg("It is done.")),
    ]
    says = []
    bus.subscribe(Say, says.append)
    assert await brain.handle("do the slow thing") == "It is done."
    assert says == [Say("Hmm.")]
    second = json.loads(route.calls[1].request.content)
    outputs = [m for m in second["input"] if m.get("type") == "function_call_output"]
    assert outputs == [{"type": "function_call_output", "call_id": "call_1", "output": "done"}]
    assert second["tools"][0]["name"] == "get_time"  # FLAT, not nested under "function"


@respx.mock
async def test_tool_call_round_trips_through_responses_input(setup):
    """A function_call is executed and echoed back to /responses as a
    function_call_output (plus the assistant function_call item) carrying the
    same call_id, and the second response's message text is the final reply."""
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, [tc("get_time", {}, "call_42")])),
        httpx.Response(200, json=msg("It is noon.")),
    ]
    assert await brain.handle("what time is it") == "It is noon."
    second = json.loads(route.calls[1].request.content)
    echoed = [m for m in second["input"] if m.get("type") == "function_call"]
    assert echoed == [{"type": "function_call", "call_id": "call_42", "name": "get_time", "arguments": "{}"}]
    result = [m for m in second["input"] if m.get("type") == "function_call_output"]
    assert result == [{"type": "function_call_output", "call_id": "call_42", "output": "noon"}]


@respx.mock
async def test_fast_tool_call_says_no_filler(setup):
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, [tc("get_time", {})])),
        httpx.Response(200, json=msg("It is noon.")),
    ]
    says = []
    bus.subscribe(Say, says.append)
    assert await brain.handle("what time is it") == "It is noon."
    assert says == []


@respx.mock
async def test_tool_call_cap(setup):
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, [tc("get_time", {}, f"c{i}")])) for i in range(4)
    ] + [httpx.Response(200, json=msg("enough"))]
    assert await brain.handle("loop") == "enough"
    assert route.call_count == 5
    assert "tools" not in json.loads(route.calls[4].request.content)


@respx.mock
async def test_http_error_publishes_error_not_reply(setup):
    bus, brain, mem = setup
    respx.post(URL).mock(return_value=httpx.Response(500))
    errors, replies = [], []
    bus.subscribe(Error, errors.append)
    bus.subscribe(Reply, replies.append)
    assert await brain.handle("hi") == ""
    assert replies == [] and errors[0].source == "the chat model"


@respx.mock
async def test_transport_error_retried_once(setup):
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [httpx.ConnectError("boom"), httpx.Response(200, json=msg("second try"))]
    assert await brain.handle("hi") == "second try"
    assert route.call_count == 2


@respx.mock
async def test_transport_error_gives_up_after_retries(setup, monkeypatch):
    monkeypatch.setattr("opencompanion.brain.asyncio.sleep", _no_sleep)
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = httpx.ConnectError("boom")
    errors = []
    bus.subscribe(Error, errors.append)
    assert await brain.handle("hi") == ""
    assert route.call_count == 3  # 3 attempts (2 retries)
    assert errors[0].source == "the chat model"


async def _no_sleep(*_a, **_k):
    return None


@respx.mock
async def test_rate_limit_429_is_retried_then_succeeds(setup, monkeypatch):
    monkeypatch.setattr("opencompanion.brain.asyncio.sleep", _no_sleep)
    bus, brain, mem = setup
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(429, headers={"retry-after": "1"}),
        httpx.Response(200, json=msg("here you go")),
    ]
    assert await brain.handle("hi") == "here you go"
    assert route.call_count == 2


@respx.mock
async def test_persistent_429_gives_up_with_error(setup, monkeypatch):
    monkeypatch.setattr("opencompanion.brain.asyncio.sleep", _no_sleep)
    bus, brain, mem = setup
    respx.post(URL).mock(return_value=httpx.Response(429))
    errors = []
    bus.subscribe(Error, errors.append)
    assert await brain.handle("hi") == ""
    assert errors[0].source == "the chat model"


@respx.mock
async def test_history_window_and_session_reset(setup):
    bus, brain, mem = setup
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=msg("ok")))
    for i in range(3):
        await brain.handle(f"turn {i}")
    sent = json.loads(route.calls[-1].request.content)["input"]
    users = [m["content"] for m in sent if m.get("role") == "user"]
    assert users == ["turn 1", "turn 2"]  # max_turns=2 history pairs incl. current
    brain.now = lambda: datetime(2026, 8, 28, 13, 0)
    await brain.handle("after break")
    sent = json.loads(route.calls[-1].request.content)["input"]
    assert [m["content"] for m in sent if m.get("role") == "user"] == ["after break"]


@respx.mock
async def test_ask_vision_sends_image_to_the_chat_model(setup):
    bus, brain, mem = setup
    assert brain.vision is True
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=msg("a cup")))
    assert await brain.ask_vision("what?", b"\xff\xd8") == "a cup"
    body = json.loads(route.calls[0].request.content)
    assert "tools" not in body
    last = body["input"][-1]
    assert last["content"][0] == {"type": "input_text", "text": "what?"}
    assert last["content"][1]["type"] == "input_image"
    assert last["content"][1]["image_url"].startswith("data:image/jpeg;base64,")
    assert body["model"] == MODEL
    assert body["instructions"]


async def test_ask_vision_when_vision_disabled_skips_the_api(setup):
    bus, brain, mem = setup
    brain.vision = False
    reply = await brain.ask_vision("what?", b"\xff\xd8")
    assert "see" in reply.lower() or "vision" in reply.lower()


@respx.mock
async def test_summarize_yesterday_once(setup):
    bus, brain, mem = setup
    mem.journal_path(date(2026, 8, 27)).parent.mkdir(parents=True, exist_ok=True)
    mem.journal_path(date(2026, 8, 27)).write_text("- 10:00 user: set a tea timer\n")
    route = respx.post(URL).mock(
        return_value=httpx.Response(200, json=msg("- set a tea timer\n- nothing else"))
    )
    await brain.maybe_summarize_yesterday()
    await brain.maybe_summarize_yesterday()
    assert route.call_count == 1
    assert mem.has_summary(date(2026, 8, 27))
    assert "- set a tea timer" in mem.facts()


@respx.mock
async def test_summarize_yesterday_skipped_without_journal(setup):
    bus, brain, mem = setup
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=msg("- nope")))
    await brain.maybe_summarize_yesterday()
    assert route.call_count == 0
    assert not mem.has_summary(date(2026, 8, 27))


async def test_no_llm_says_so(setup):
    bus, brain, mem = setup
    brain.llm = None
    replies = []
    bus.subscribe(Reply, replies.append)
    await brain.handle("hi")
    assert "not configured" in replies[0].text


async def test_no_llm_speaks_only_once(setup):
    bus, brain, mem = setup
    brain.llm = None
    replies = []
    bus.subscribe(Reply, replies.append)
    await brain.handle("hi")
    await brain.handle("hi again")
    assert len(replies) == 1


@respx.mock
async def test_transcript_event_triggers_handle(setup):
    bus, brain, mem = setup
    respx.post(URL).mock(return_value=httpx.Response(200, json=msg("yo")))
    brain.start()
    replies = []
    bus.subscribe(Reply, replies.append)
    await bus.publish(Transcript("hey"))
    assert replies == [Reply("yo")]


async def test_fake_llm_drives_a_tool_turn(setup):
    """The scripted stand-in exercises the same loop with no HTTP at all."""
    bus, brain, mem = setup
    brain.llm = FakeLLM(
        {"role": "assistant", "content": None, "tool_calls": [tc("get_time", {})]},
        {"role": "assistant", "content": "It is noon."},
    )
    replies = []
    bus.subscribe(Reply, replies.append)
    assert await brain.handle("what time is it") == "It is noon."
    assert replies == [Reply("It is noon.")]
    second_messages, second_tools = brain.llm.calls[1]
    assert second_messages[-1] == {"role": "tool", "tool_call_id": "call_1", "content": "noon"}
    assert second_tools is not None


async def test_fake_llm_unknown_tool_reports_back(setup):
    bus, brain, mem = setup
    brain.llm = FakeLLM(
        {"role": "assistant", "content": None, "tool_calls": [tc("nope", {})]},
        {"role": "assistant", "content": "I cannot do that."},
    )
    assert await brain.handle("do a thing") == "I cannot do that."
    assert brain.llm.calls[1][0][-1]["content"] == "Unknown tool: nope"


async def test_blank_input_is_ignored(setup):
    bus, brain, mem = setup
    brain.llm = FakeLLM()
    replies = []
    bus.subscribe(Reply, replies.append)
    assert await brain.handle("   ") == ""
    assert replies == [] and brain.llm.calls == []


@respx.mock
async def test_parallel_tool_calls_respect_the_budget(setup):
    """One assistant message asking for 6 tools must still spend only max_tool_calls."""
    bus, brain, mem = setup
    runs = []

    async def counted():
        runs.append(1)
        return "ran"

    brain.tools.register(Tool("counted", "d", {"type": "object", "properties": {}}, counted))
    batch = [tc("counted", {}, f"c{i}") for i in range(6)]
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, batch)),
        httpx.Response(200, json=msg("that is plenty")),
    ]
    assert await brain.handle("do six things") == "that is plenty"
    assert len(runs) == brain.max_tool_calls == 4
    second = json.loads(route.calls[1].request.content)
    results = [m["output"] for m in second["input"] if m.get("type") == "function_call_output"]
    assert results[:4] == ["ran"] * 4
    assert all("Skipped" in r for r in results[4:]) and len(results) == 6
    assert "tools" not in second  # budget spent, so no more tools are offered


@respx.mock
async def test_budget_spent_and_model_still_asks_for_tools(setup):
    bus, brain, mem = setup
    batch = [tc("get_time", {}, f"c{i}") for i in range(4)]
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=msg(None, batch)),
        httpx.Response(200, json=msg(None, [tc("get_time", {}, "late")])),
    ]
    assert await brain.handle("go") == "I ran out of steps; ask me again."
    assert route.call_count == 2


async def test_build_brain_wires_client_and_tools(tmp_path):
    cfg = Config({"openai": {"chat_model": "m"}}, {"OPENAI_API_KEY": "secret"}, root=tmp_path)
    async with httpx.AsyncClient() as http:
        brain = build_brain(cfg, EventBus(), http)
    assert brain.llm is not None and brain.llm.service.api_key == "secret"
    assert brain.llm.model == "m" and brain.llm.api == "chat"
    assert set(brain.tools.names()) == {
        "get_time",
        "set_timer",
        "remember",
        "recall",
        "weather",
        "web_search",
        "look",
        "check_host",
        "laptop_notify",
        "laptop_clipboard",
        "laptop_notifications",
    }


async def test_build_brain_without_key_is_disabled_and_says_so_once(tmp_path):
    cfg = Config({}, {}, root=tmp_path)
    bus = EventBus()
    replies = []
    bus.subscribe(Reply, replies.append)
    async with httpx.AsyncClient() as http:
        brain = build_brain(cfg, bus, http)
        assert brain.llm is None
        assert await brain.handle("hi") == "My language model is not configured."
        assert await brain.handle("hi again") == ""
    assert len(replies) == 1


async def test_build_brain_without_chat_model_is_disabled(tmp_path):
    cfg = Config({"openai": {"chat_model": ""}}, {"OPENAI_API_KEY": "secret"}, root=tmp_path)
    async with httpx.AsyncClient() as http:
        brain = build_brain(cfg, EventBus(), http)
    assert brain.llm is None


@respx.mock
async def test_reasoning_effort_comes_from_config(tmp_path):
    cfg = Config(
        {
            "brain": {"reasoning_effort": "high", "api": "responses"},
            "openai": {"base_url": BASE, "chat_model": MODEL},
        },
        {"OPENAI_API_KEY": "secret"},
        root=tmp_path,
    )
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=msg("ok")))
    async with httpx.AsyncClient() as http:
        brain = build_brain(cfg, EventBus(), http)
        await brain.handle("hi")
    body = json.loads(route.calls[0].request.content)
    assert body["reasoning"]["effort"] == "high"
    assert "temperature" not in body
